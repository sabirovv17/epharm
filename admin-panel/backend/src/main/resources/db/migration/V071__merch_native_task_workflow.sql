-- Source snapshots never execute a worker, replay an approval or credit a wallet.
CREATE TABLE merch_task_dispatches (
    id uuid PRIMARY KEY,
    source_system varchar(32) NOT NULL CHECK (source_system IN ('EPHARM','MERCH_CRM')),
    source_origin text NOT NULL CHECK (source_origin IN ('https://epharm.inkar.kz','https://crm.inkar.kz')),
    source_id uuid NOT NULL CHECK (source_id=id),
    source_payload jsonb NOT NULL CHECK (jsonb_typeof(source_payload)='object'),
    branch_id uuid NOT NULL REFERENCES merch_branches(id) ON DELETE RESTRICT,
    title text NOT NULL CHECK (length(btrim(title)) BETWEEN 2 AND 160),
    status text NOT NULL CHECK (status IN ('ready','sent','in_progress','submitted','revision_required','completed','cancelled')),
    priority text NOT NULL DEFAULT 'normal' CHECK (priority IN ('low','normal','high','urgent')),
    task_type text NOT NULL DEFAULT 'photo_report',
    available_from timestamptz,
    due_at timestamptz,
    reward_amount_kzt bigint NOT NULL DEFAULT 0 CHECK (reward_amount_kzt BETWEEN 0 AND 1000000),
    historical_reward_preserved boolean NOT NULL DEFAULT false,
    assigned_pharmacist_id varchar(64) REFERENCES pharmacists(id) ON DELETE RESTRICT,
    created_by uuid REFERENCES admin_users(id) ON DELETE RESTRICT,
    source_created_by uuid,
    source_mass_owner_id uuid,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    UNIQUE(source_system,source_origin,source_id),
    CHECK ((source_system='EPHARM' AND source_origin='https://epharm.inkar.kz') OR
           (source_system='MERCH_CRM' AND source_origin='https://crm.inkar.kz'))
);
CREATE INDEX ix_merch_tasks_branch_status ON merch_task_dispatches(branch_id,status,due_at,id);

CREATE TABLE merch_task_items (
    id uuid PRIMARY KEY,
    dispatch_id uuid NOT NULL REFERENCES merch_task_dispatches(id) ON DELETE RESTRICT,
    source_id uuid NOT NULL CHECK (source_id=id),
    source_payload jsonb NOT NULL CHECK (jsonb_typeof(source_payload)='object'),
    title text NOT NULL CHECK (length(btrim(title)) BETWEEN 2 AND 240),
    instructions text NOT NULL DEFAULT '',
    task_type text NOT NULL,
    available_from timestamptz,
    due_at timestamptz,
    status text NOT NULL CHECK (status IN ('draft','assigned','accepted','in_progress','submitted','revision_required','completed','blocked','cancelled')),
    required boolean NOT NULL DEFAULT true,
    required_photo_count integer NOT NULL CHECK (required_photo_count BETWEEN 0 AND 12),
    checklist jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(checklist)='array'),
    source_assignee_id uuid,
    source_reviewer_id uuid,
    current_attempt integer NOT NULL DEFAULT 0 CHECK (current_attempt>=0),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);
CREATE INDEX ix_merch_task_items_dispatch ON merch_task_items(dispatch_id,id);

CREATE TABLE merch_task_attempts (
    id uuid PRIMARY KEY,
    item_id uuid NOT NULL REFERENCES merch_task_items(id) ON DELETE RESTRICT,
    attempt_no integer NOT NULL CHECK (attempt_no>0),
    executor_person_id uuid REFERENCES unified_persons(id) ON DELETE RESTRICT,
    executor_account_id uuid REFERENCES admin_users(id) ON DELETE RESTRICT,
    pharmacist_id varchar(64) REFERENCES pharmacists(id) ON DELETE RESTRICT,
    binding_id uuid REFERENCES merch_executor_bindings(id) ON DELETE RESTRICT,
    status text NOT NULL CHECK (status IN ('draft','submitted','approved','revision_required','source_preserved')),
    completed_checklist_ids jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(completed_checklist_ids)='array'),
    employee_comment text NOT NULL DEFAULT '' CHECK (length(employee_comment)<=1200),
    submitted_at timestamptz,
    created_at timestamptz NOT NULL,
    source_payload jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(source_payload)='object'),
    UNIQUE(item_id,attempt_no)
);

CREATE TABLE merch_task_evidence (
    id uuid PRIMARY KEY,
    item_id uuid NOT NULL REFERENCES merch_task_items(id) ON DELETE RESTRICT,
    attempt_id uuid REFERENCES merch_task_attempts(id) ON DELETE RESTRICT,
    client_upload_id uuid,
    storage_backend text NOT NULL CHECK (storage_backend IN ('private_host','legacy_reference')),
    object_key text NOT NULL,
    mime_type text NOT NULL,
    byte_size bigint NOT NULL CHECK (byte_size>0),
    sha256 varchar(64) CHECK (sha256 IS NULL OR sha256 ~ '^[0-9a-f]{64}$'),
    original_filename text,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(metadata)='object'),
    source_uploaded_by uuid,
    uploaded_by_person_id uuid REFERENCES unified_persons(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    UNIQUE(attempt_id,client_upload_id),
    UNIQUE(storage_backend,object_key),
    CHECK (storage_backend<>'private_host' OR (attempt_id IS NOT NULL AND client_upload_id IS NOT NULL AND sha256 IS NOT NULL))
);

CREATE TABLE merch_task_reviews (
    id uuid PRIMARY KEY,
    item_id uuid NOT NULL REFERENCES merch_task_items(id) ON DELETE RESTRICT,
    attempt_id uuid NOT NULL UNIQUE REFERENCES merch_task_attempts(id) ON DELETE RESTRICT,
    reviewer_account_id uuid NOT NULL REFERENCES admin_users(id) ON DELETE RESTRICT,
    reviewer_person_id uuid NOT NULL REFERENCES unified_persons(id) ON DELETE RESTRICT,
    decision text NOT NULL CHECK (decision IN ('approve','revision')),
    reason text NOT NULL CHECK (length(btrim(reason)) BETWEEN 3 AND 1200),
    created_at timestamptz NOT NULL
);

CREATE TABLE merch_task_events (
    id uuid PRIMARY KEY,
    dispatch_id uuid NOT NULL REFERENCES merch_task_dispatches(id) ON DELETE RESTRICT,
    item_id uuid REFERENCES merch_task_items(id) ON DELETE RESTRICT,
    actor_account_id uuid REFERENCES admin_users(id) ON DELETE RESTRICT,
    actor_person_id uuid REFERENCES unified_persons(id) ON DELETE RESTRICT,
    source_actor_id uuid,
    event_type text NOT NULL,
    details jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(details)='object'),
    created_at timestamptz NOT NULL,
    source_payload jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(source_payload)='object')
);

-- Legacy raw capabilities stay private: possession is task access, never verified human identity.
CREATE TABLE merch_task_links (
    id uuid PRIMARY KEY,
    dispatch_id uuid NOT NULL REFERENCES merch_task_dispatches(id) ON DELETE RESTRICT,
    token_hash text NOT NULL UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    integration_access_token text,
    issued_channel text,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    submitted_at timestamptz,
    source_payload jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(source_payload)='object')
);
CREATE TABLE merch_task_delivery_receipts (
    link_id uuid NOT NULL REFERENCES merch_task_links(id) ON DELETE RESTRICT,
    dispatch_id uuid NOT NULL REFERENCES merch_task_dispatches(id) ON DELETE RESTRICT,
    device_id text NOT NULL,
    shown_at timestamptz NOT NULL,
    PRIMARY KEY(link_id,device_id)
);

CREATE TABLE merch_task_rewards (
    dispatch_id uuid PRIMARY KEY REFERENCES merch_task_dispatches(id) ON DELETE RESTRICT,
    pharmacist_id varchar(64) REFERENCES pharmacists(id) ON DELETE RESTRICT,
    executor_person_id uuid REFERENCES unified_persons(id) ON DELETE RESTRICT,
    historical_pharmacy_id varchar(64) REFERENCES pharmacies(id) ON DELETE RESTRICT,
    amount_kzt bigint NOT NULL CHECK (amount_kzt BETWEEN 0 AND 1000000),
    state text NOT NULL CHECK (state IN ('credited','already_credited','held','historical_preserved','not_monetary')),
    reason text NOT NULL,
    wallet_entry_id uuid UNIQUE REFERENCES pharmacist_wallet_entries(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    CHECK ((state IN ('credited','already_credited') AND wallet_entry_id IS NOT NULL) OR
           (state NOT IN ('credited','already_credited') AND wallet_entry_id IS NULL))
);
CREATE FUNCTION preserve_merch_task_evidence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Task evidence and review history are append-only' USING ERRCODE='23514'; END;
$$;
CREATE TRIGGER immutable_merch_task_evidence BEFORE UPDATE OR DELETE ON merch_task_evidence
    FOR EACH ROW EXECUTE FUNCTION preserve_merch_task_evidence();
CREATE TRIGGER immutable_merch_task_reviews BEFORE UPDATE OR DELETE ON merch_task_reviews
    FOR EACH ROW EXECUTE FUNCTION preserve_merch_task_evidence();
CREATE TRIGGER immutable_merch_task_events BEFORE UPDATE OR DELETE ON merch_task_events
    FOR EACH ROW EXECUTE FUNCTION preserve_merch_task_evidence();
CREATE TRIGGER immutable_merch_task_rewards BEFORE UPDATE OR DELETE ON merch_task_rewards
    FOR EACH ROW EXECUTE FUNCTION preserve_merch_task_evidence();

CREATE FUNCTION preserve_merch_task_source() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.source_id IS DISTINCT FROM OLD.source_id OR
       NEW.source_payload IS DISTINCT FROM OLD.source_payload THEN
        RAISE EXCEPTION 'Original task identity and source snapshot are immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER immutable_merch_task_dispatch_source BEFORE UPDATE ON merch_task_dispatches
    FOR EACH ROW EXECUTE FUNCTION preserve_merch_task_source();
CREATE TRIGGER immutable_merch_task_item_source BEFORE UPDATE ON merch_task_items
    FOR EACH ROW EXECUTE FUNCTION preserve_merch_task_source();
