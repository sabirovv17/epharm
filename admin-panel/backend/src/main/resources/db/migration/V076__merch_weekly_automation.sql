-- The only automatic writer is opt-in. Importers project verified source cycle metadata without replaying it.
ALTER TABLE merch_task_dispatches
    ADD COLUMN recurring_cycle_number integer CHECK(recurring_cycle_number>0),
    ADD COLUMN cycle_starts_at timestamptz,
    ADD COLUMN next_cycle_at timestamptz,
    ADD COLUMN native_automation_generated boolean NOT NULL DEFAULT false,
    ADD COLUMN automation_owner_admin_id uuid REFERENCES admin_users(id) ON DELETE RESTRICT,
    ADD COLUMN automation_blocked_reason text,
    ADD CONSTRAINT ck_merch_native_weekly_identity CHECK(NOT native_automation_generated OR
        (source_system='EPHARM' AND automation_kind IS NOT DISTINCT FROM 'weekly_placement_photo_report' AND media_plan_id IS NOT NULL
          AND recurring_cycle_number IS NOT NULL AND cycle_starts_at IS NOT NULL));
ALTER TABLE merch_task_items
    ADD COLUMN media_plan_item_id uuid REFERENCES merch_media_plan_items(id) ON DELETE RESTRICT,
    ADD COLUMN shelf_id uuid REFERENCES merch_shelves(id) ON DELETE RESTRICT,
    ADD COLUMN recurring_cycle_number integer CHECK(recurring_cycle_number>0),
    ADD COLUMN automation_kind text,
    ADD COLUMN reference_image_ref text;
-- File references are projected from the actual source photo tables, never invented inside source_payload.
ALTER TABLE merch_media_plan_items ADD COLUMN client_photo_ref text;
ALTER TABLE merch_shelves ADD COLUMN first_photo_ref text;
CREATE UNIQUE INDEX ux_merch_weekly_dispatch_cycle ON merch_task_dispatches(media_plan_id,branch_id,recurring_cycle_number)
    WHERE automation_kind='weekly_placement_photo_report' AND media_plan_id IS NOT NULL AND recurring_cycle_number IS NOT NULL;
CREATE UNIQUE INDEX ux_merch_weekly_item_cycle ON merch_task_items(media_plan_item_id,recurring_cycle_number)
    WHERE automation_kind='weekly_placement_photo_report' AND media_plan_item_id IS NOT NULL AND recurring_cycle_number IS NOT NULL;
CREATE INDEX ix_merch_weekly_next ON merch_task_dispatches(next_cycle_at,media_plan_id)
    WHERE automation_kind='weekly_placement_photo_report' AND status<>'cancelled';

-- An operational assignment is not an authorization grant or a verified reward recipient.
CREATE TABLE merch_automation_branch_owners (
    branch_id uuid PRIMARY KEY REFERENCES merch_branches(id) ON DELETE RESTRICT,
    source_system varchar(32) NOT NULL CHECK(source_system IN ('EPHARM','MERCH_CRM')),
    source_owner_id uuid,
    admin_account_id uuid REFERENCES admin_users(id) ON DELETE RESTRICT,
    person_id uuid REFERENCES unified_persons(id) ON DELETE RESTRICT,
    active boolean NOT NULL,
    revision bigint NOT NULL CHECK(revision>0),
    verification_reference text NOT NULL CHECK(length(btrim(verification_reference)) BETWEEN 1 AND 1000),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CHECK((source_system='MERCH_CRM' AND source_owner_id IS NOT NULL) OR
        (source_system='EPHARM' AND source_owner_id IS NULL AND admin_account_id IS NOT NULL AND person_id IS NOT NULL)),
    CHECK((admin_account_id IS NULL)=(person_id IS NULL))
);
CREATE TABLE merch_automation_owner_audit (
    id uuid PRIMARY KEY,
    branch_id uuid NOT NULL REFERENCES merch_branches(id) ON DELETE RESTRICT,
    revision bigint NOT NULL CHECK(revision>0),
    actor_admin_id uuid REFERENCES admin_users(id) ON DELETE RESTRICT,
    operation varchar(32) NOT NULL,
    details jsonb NOT NULL CHECK(jsonb_typeof(details)='object'),
    created_at timestamptz NOT NULL,
    UNIQUE(branch_id,revision)
);
CREATE FUNCTION preserve_merch_automation_owner_audit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Automation owner evidence is append-only' USING ERRCODE='23514'; END;
$$;
CREATE TRIGGER immutable_merch_automation_owner_audit BEFORE UPDATE OR DELETE ON merch_automation_owner_audit
    FOR EACH ROW EXECUTE FUNCTION preserve_merch_automation_owner_audit();
