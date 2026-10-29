-- New attempts keep their own evidence; source observations never become fresh submissions.
CREATE TABLE merch_task_bulk_receipts (
    id uuid PRIMARY KEY,
    created_by uuid NOT NULL REFERENCES admin_users(id) ON DELETE RESTRICT,
    request_payload jsonb NOT NULL CHECK(jsonb_typeof(request_payload)='object'),
    dispatch_ids jsonb NOT NULL CHECK(jsonb_typeof(dispatch_ids)='array'),
    created_at timestamptz NOT NULL
);
ALTER TABLE merch_task_items ADD COLUMN stock_products jsonb NOT NULL DEFAULT '[]'::jsonb CHECK(jsonb_typeof(stock_products)='array');
CREATE TABLE merch_task_stock_observations (
    attempt_id uuid PRIMARY KEY REFERENCES merch_task_attempts(id) ON DELETE RESTRICT,
    item_id uuid NOT NULL REFERENCES merch_task_items(id) ON DELETE RESTRICT,
    counts jsonb NOT NULL CHECK(jsonb_typeof(counts)='object'),
    shortage_reason text NOT NULL CHECK(length(shortage_reason)<=1200),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);
CREATE TABLE merch_task_source_stock (
    item_id uuid PRIMARY KEY REFERENCES merch_task_items(id) ON DELETE RESTRICT,
    counts jsonb NOT NULL CHECK(jsonb_typeof(counts)='object'),
    shortage_reason text NOT NULL,
    source_payload jsonb NOT NULL CHECK(jsonb_typeof(source_payload)='object'),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);
CREATE TABLE merch_instruction_materials (
    id uuid PRIMARY KEY,
    source_system text NOT NULL CHECK(source_system IN ('EPHARM','MERCH_CRM')),
    source_id uuid NOT NULL CHECK(source_id=id),
    source_payload jsonb NOT NULL DEFAULT '{}'::jsonb CHECK(jsonb_typeof(source_payload)='object'),
    producer_id uuid REFERENCES merch_producers(id) ON DELETE RESTRICT,
    title text NOT NULL CHECK(length(btrim(title)) BETWEEN 2 AND 160),
    instructions text NOT NULL CHECK(length(instructions) BETWEEN 3 AND 4000),
    due_at timestamptz NOT NULL,
    status text NOT NULL CHECK(status IN ('pending','approved','rejected')),
    revision bigint NOT NULL DEFAULT 0,
    storage_backend text NOT NULL CHECK(storage_backend IN ('private_host','legacy_reference')),
    object_key text NOT NULL,
    sha256 text,
    byte_size bigint CHECK(byte_size>0),
    original_filename text,
    created_by_admin uuid REFERENCES admin_users(id) ON DELETE RESTRICT,
    source_created_by uuid,
    reviewed_by_admin uuid REFERENCES admin_users(id) ON DELETE RESTRICT,
    source_reviewed_by uuid,
    review_note text,
    reviewed_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CHECK(storage_backend<>'private_host' OR (sha256 ~ '^[a-f0-9]{64}$' AND byte_size IS NOT NULL))
);
CREATE TABLE merch_instruction_plans (
    material_id uuid NOT NULL REFERENCES merch_instruction_materials(id) ON DELETE RESTRICT,
    plan_id uuid NOT NULL REFERENCES merch_media_plans(id) ON DELETE RESTRICT,
    PRIMARY KEY(material_id,plan_id)
);
CREATE TABLE merch_instruction_events (
    id uuid PRIMARY KEY,
    material_id uuid NOT NULL REFERENCES merch_instruction_materials(id) ON DELETE RESTRICT,
    actor_admin_id uuid REFERENCES admin_users(id) ON DELETE RESTRICT,
    actor_producer_id uuid REFERENCES merch_producers(id) ON DELETE RESTRICT,
    operation text NOT NULL,
    details jsonb NOT NULL CHECK(jsonb_typeof(details)='object'),
    created_at timestamptz NOT NULL
);
CREATE FUNCTION preserve_merch_instruction_events() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Instruction events are append-only' USING ERRCODE='23514'; END;
$$;
CREATE TRIGGER immutable_merch_instruction_events BEFORE UPDATE OR DELETE ON merch_instruction_events
    FOR EACH ROW EXECUTE FUNCTION preserve_merch_instruction_events();
CREATE UNIQUE INDEX ux_merch_instruction_packet ON merch_task_dispatches(branch_id,(source_payload->>'instructionMaterialId'))
    WHERE source_system='EPHARM' AND source_payload->>'instructionMaterialId' IS NOT NULL;
