ALTER TABLE merch_media_plans
    ADD COLUMN producer_id uuid REFERENCES merch_producers(id) ON DELETE RESTRICT,
    ADD COLUMN revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
    ADD COLUMN created_by_admin uuid REFERENCES admin_users(id) ON DELETE RESTRICT,
    ADD COLUMN created_by_producer uuid REFERENCES merch_producers(id) ON DELETE RESTRICT,
    ADD COLUMN reviewed_by_admin uuid REFERENCES admin_users(id) ON DELETE RESTRICT,
    ADD COLUMN reviewed_at timestamptz,
    ADD COLUMN review_note text CHECK (char_length(review_note) <= 1000),
    ADD COLUMN assignment_note text CHECK (char_length(assignment_note) <= 500);
CREATE INDEX merch_plan_producer_idx ON merch_media_plans(producer_id,created_at,id);

CREATE TABLE merch_placement_audit (
    id uuid PRIMARY KEY,
    plan_id uuid NOT NULL REFERENCES merch_media_plans(id) ON DELETE RESTRICT,
    revision bigint NOT NULL,
    actor_type varchar(16) NOT NULL CHECK (actor_type IN ('ADMIN','PRODUCER','SYSTEM')),
    admin_account_id uuid REFERENCES admin_users(id) ON DELETE RESTRICT,
    producer_id uuid REFERENCES merch_producers(id) ON DELETE RESTRICT,
    action varchar(32) NOT NULL,
    occurred_at timestamptz NOT NULL,
    details jsonb NOT NULL CHECK (jsonb_typeof(details)='object'),
    UNIQUE(plan_id,revision),
    CHECK ((actor_type='ADMIN' AND admin_account_id IS NOT NULL AND producer_id IS NULL) OR
           (actor_type='PRODUCER' AND admin_account_id IS NULL AND producer_id IS NOT NULL) OR
           (actor_type='SYSTEM' AND admin_account_id IS NULL AND producer_id IS NULL))
);
CREATE FUNCTION reject_merch_placement_audit_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Placement audit is append-only' USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER merch_placement_audit_append_only BEFORE UPDATE OR DELETE ON merch_placement_audit
    FOR EACH ROW EXECUTE FUNCTION reject_merch_placement_audit_mutation();
