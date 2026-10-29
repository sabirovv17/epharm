-- Delivery is opt-in; preserved CRM capabilities are not silently claimed by this runtime.
ALTER TABLE merch_task_dispatches
    ADD COLUMN source_media_plan_id uuid,
    ADD COLUMN media_plan_id uuid REFERENCES merch_media_plans(id) ON DELETE RESTRICT,
    ADD COLUMN automation_kind text,
    ADD COLUMN sent_at timestamptz,
    ADD COLUMN assignment_ready_at timestamptz,
    ADD COLUMN sla_started_at timestamptz,
    ADD CONSTRAINT ck_merch_dispatch_source_plan CHECK
      (source_system <> 'MERCH_CRM' OR media_plan_id IS NULL OR
        (source_media_plan_id IS NOT NULL AND media_plan_id=source_media_plan_id));
ALTER TABLE merch_task_items ADD COLUMN training_material_id uuid;
ALTER TABLE merch_task_links
    ADD COLUMN native_owned boolean NOT NULL DEFAULT false,
    ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
    ADD CONSTRAINT ck_merch_native_delivery_channel CHECK
      (NOT native_owned OR issued_channel IS NOT DISTINCT FROM 'pharmapay_posm');
CREATE INDEX ix_merch_native_delivery_links ON merch_task_links(dispatch_id,created_at DESC)
    WHERE native_owned;
ALTER TABLE merch_task_attempts ADD COLUMN capability_link_id uuid
    REFERENCES merch_task_links(id) ON DELETE RESTRICT;
ALTER TABLE merch_task_attempts ADD CONSTRAINT ck_merch_capability_attempt_identity CHECK
    (capability_link_id IS NULL OR (executor_person_id IS NULL AND executor_account_id IS NULL
        AND pharmacist_id IS NULL AND binding_id IS NULL));
