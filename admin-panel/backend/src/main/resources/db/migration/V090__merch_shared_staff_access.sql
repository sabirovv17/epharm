-- Shared monthly access is a limited organization/branch scope, never a verified person.
CREATE TABLE merch_staff_access_codes (
    id uuid PRIMARY KEY,
    source_system varchar(32) NOT NULL CHECK (source_system IN ('EPHARM','MERCH_CRM')),
    source_origin text NOT NULL,
    source_id uuid NOT NULL CHECK (source_id=id),
    source_payload jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(source_payload)='object'),
    organization_id uuid NOT NULL REFERENCES merch_organizations(id) ON DELETE RESTRICT,
    placement_month date NOT NULL CHECK (extract(day FROM placement_month)=1),
    code_hash text NOT NULL CHECK (code_hash ~ '^\$2[aby]\$10\$[./A-Za-z0-9]{53}$'),
    code_lookup_hash varchar(64) CHECK (code_lookup_hash IS NULL OR code_lookup_hash ~ '^[0-9a-f]{64}$'),
    all_org_branches boolean NOT NULL DEFAULT false,
    deadline_at timestamptz,
    status varchar(16) NOT NULL CHECK (status IN ('active','revoked')),
    max_uses integer NOT NULL DEFAULT 1 CHECK (max_uses>0),
    use_count integer NOT NULL DEFAULT 0 CHECK (use_count>=0),
    qr_token_hash varchar(64) UNIQUE CHECK (qr_token_hash IS NULL OR qr_token_hash ~ '^[0-9a-f]{64}$'),
    qr_use_count bigint NOT NULL DEFAULT 0 CHECK (qr_use_count>=0),
    qr_created_at timestamptz,
    qr_last_used_at timestamptz,
    last_used_at timestamptz,
    revoked_at timestamptz,
    manual_revoked_at timestamptz,
    created_by_account_id uuid REFERENCES admin_users(id) ON DELETE RESTRICT,
    revoked_by_account_id uuid REFERENCES admin_users(id) ON DELETE RESTRICT,
    note text,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CHECK ((source_system='EPHARM' AND source_origin='https://epharm.inkar.kz') OR
           (source_system='MERCH_CRM' AND source_origin='https://crm.inkar.kz'))
);
CREATE INDEX ix_merch_staff_codes_lookup ON merch_staff_access_codes(code_lookup_hash);
CREATE INDEX ix_merch_staff_codes_org_month ON merch_staff_access_codes(organization_id,placement_month,id);

CREATE TABLE merch_staff_access_code_branches (
    code_id uuid NOT NULL REFERENCES merch_staff_access_codes(id) ON DELETE RESTRICT,
    branch_id uuid NOT NULL REFERENCES merch_branches(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    PRIMARY KEY(code_id,branch_id)
);
CREATE TABLE merch_staff_access_sessions (
    id uuid PRIMARY KEY,
    code_id uuid NOT NULL REFERENCES merch_staff_access_codes(id) ON DELETE RESTRICT,
    token_hash varchar(64) NOT NULL UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL CHECK (expires_at>created_at),
    revoked_at timestamptz
);
CREATE INDEX ix_merch_staff_sessions_code ON merch_staff_access_sessions(code_id,id);
CREATE TABLE merch_staff_access_attempts (
    id uuid PRIMARY KEY,
    access_type varchar(8) NOT NULL CHECK (access_type IN ('code','qr')),
    lookup_hash varchar(64) NOT NULL CHECK (lookup_hash ~ '^[0-9a-f]{64}$'),
    ip_hash varchar(64) NOT NULL CHECK (ip_hash ~ '^[0-9a-f]{64}$'),
    success boolean NOT NULL,
    bcrypt_cost integer NOT NULL DEFAULT 0 CHECK (bcrypt_cost BETWEEN 0 AND 17),
    code_id uuid REFERENCES merch_staff_access_codes(id) ON DELETE RESTRICT,
    failure_reason text,
    created_at timestamptz NOT NULL,
    source_id uuid CHECK (source_id IS NULL OR source_id=id),
    source_payload jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(source_payload)='object')
);
CREATE INDEX ix_merch_staff_attempts_ip ON merch_staff_access_attempts(access_type,ip_hash,created_at);
CREATE INDEX ix_merch_staff_attempts_time ON merch_staff_access_attempts(created_at,id);

-- One web report is shared by every authorized code for the same branch/month.
-- Per-code deadlines remain in the authorization context, not in this shared report.
CREATE TABLE merch_staff_visits (
    id uuid PRIMARY KEY,
    branch_id uuid NOT NULL REFERENCES merch_branches(id) ON DELETE RESTRICT,
    placement_month date NOT NULL CHECK (extract(day FROM placement_month)=1),
    dispatch_id uuid NOT NULL UNIQUE REFERENCES merch_task_dispatches(id) ON DELETE RESTRICT,
    employee_name text NOT NULL CHECK (length(btrim(employee_name)) BETWEEN 2 AND 120),
    notes text NOT NULL DEFAULT '' CHECK (length(notes)<=1000),
    submitted_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    UNIQUE(branch_id,placement_month)
);
ALTER TABLE merch_task_attempts ADD COLUMN staff_visit_id uuid REFERENCES merch_staff_visits(id) ON DELETE RESTRICT;
ALTER TABLE merch_task_attempts ADD COLUMN staff_submitted_name text;
ALTER TABLE merch_task_attempts ADD CONSTRAINT ck_merch_staff_attempt_identity CHECK (
    (staff_visit_id IS NULL AND staff_submitted_name IS NULL) OR
    (staff_visit_id IS NOT NULL AND staff_submitted_name IS NOT NULL
        AND length(btrim(staff_submitted_name)) BETWEEN 2 AND 120
        AND executor_person_id IS NULL AND executor_account_id IS NULL AND pharmacist_id IS NULL
        AND binding_id IS NULL AND capability_link_id IS NULL)
);
CREATE INDEX ix_merch_task_staff_visit ON merch_task_attempts(staff_visit_id) WHERE staff_visit_id IS NOT NULL;

CREATE FUNCTION preserve_merch_staff_source() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN
        IF TG_TABLE_NAME='merch_staff_access_codes' OR OLD.source_id IS NOT NULL THEN
            RAISE EXCEPTION 'Shared access history is preserved' USING ERRCODE='23514';
        END IF;
        RETURN OLD;
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.source_id IS DISTINCT FROM OLD.source_id OR
       NEW.source_payload IS DISTINCT FROM OLD.source_payload THEN
        RAISE EXCEPTION 'Shared access source identity is immutable' USING ERRCODE='23514';
    END IF;
    IF TG_TABLE_NAME='merch_staff_access_codes' THEN
        IF NEW.source_system IS DISTINCT FROM OLD.source_system OR NEW.source_origin IS DISTINCT FROM OLD.source_origin THEN
            RAISE EXCEPTION 'Shared access source origin is immutable' USING ERRCODE='23514';
        END IF;
    ELSIF OLD.source_id IS NOT NULL AND to_jsonb(NEW) IS DISTINCT FROM to_jsonb(OLD) THEN
        RAISE EXCEPTION 'Imported shared access attempts are immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER preserve_merch_staff_code_source BEFORE UPDATE OR DELETE ON merch_staff_access_codes
    FOR EACH ROW EXECUTE FUNCTION preserve_merch_staff_source();
CREATE TRIGGER preserve_merch_staff_attempt_source BEFORE UPDATE OR DELETE ON merch_staff_access_attempts
    FOR EACH ROW EXECUTE FUNCTION preserve_merch_staff_source();
