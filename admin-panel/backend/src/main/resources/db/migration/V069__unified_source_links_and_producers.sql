-- Verified source references are data lineage, never a grant or an inferred human match.
CREATE TABLE unified_source_links (
    source_system varchar(32) NOT NULL CHECK (source_system IN ('EPHARM','MERCH_CRM')),
    source_origin text NOT NULL,
    source_entity_type varchar(64) NOT NULL,
    source_id text NOT NULL CHECK (length(source_id) > 0),
    canonical_entity_type varchar(64) NOT NULL,
    canonical_id text NOT NULL CHECK (length(canonical_id) > 0),
    verification_reference text NOT NULL CHECK (length(btrim(verification_reference)) > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (source_system,source_origin,source_entity_type,source_id),
    CHECK ((source_system='EPHARM' AND source_origin='https://epharm.inkar.kz') OR
           (source_system='MERCH_CRM' AND source_origin='https://crm.inkar.kz'))
);
CREATE INDEX ix_unified_source_canonical ON unified_source_links(canonical_entity_type,canonical_id);
ALTER TABLE unified_persons ADD COLUMN display_name varchar(255);

CREATE TABLE unified_external_accounts (
    id uuid PRIMARY KEY,
    source_system varchar(32) NOT NULL CHECK (source_system='MERCH_CRM'),
    source_origin text NOT NULL CHECK (source_origin='https://crm.inkar.kz'),
    source_payload jsonb NOT NULL CHECK (jsonb_typeof(source_payload)='object'),
    email text,
    full_name text NOT NULL,
    phone text,
    source_role text NOT NULL,
    active boolean NOT NULL,
    password_hash text,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);
CREATE TABLE unified_external_account_bindings (
    source_account_id uuid PRIMARY KEY REFERENCES unified_external_accounts(id) ON DELETE RESTRICT,
    admin_account_id uuid NOT NULL REFERENCES admin_users(id) ON DELETE RESTRICT,
    person_id uuid NOT NULL REFERENCES unified_persons(id) ON DELETE RESTRICT,
    verification_reference text NOT NULL CHECK (length(btrim(verification_reference)) BETWEEN 1 AND 1000),
    verified_by uuid NOT NULL REFERENCES admin_users(id) ON DELETE RESTRICT,
    verified_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE unified_identity_audit (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL REFERENCES admin_users(id) ON DELETE RESTRICT,
    entity_type varchar(64) NOT NULL,
    entity_id text NOT NULL,
    operation varchar(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE pharmacist_person_links (
    pharmacist_id varchar(64) PRIMARY KEY REFERENCES pharmacists(id) ON DELETE RESTRICT,
    person_id uuid NOT NULL REFERENCES unified_persons(id) ON DELETE RESTRICT,
    verification_reference text NOT NULL CHECK (length(btrim(verification_reference)) > 0),
    verified_by uuid NOT NULL REFERENCES admin_users(id) ON DELETE RESTRICT,
    verified_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE unified_import_runs (
    operation_key varchar(160) PRIMARY KEY,
    source_sha256 varchar(64) NOT NULL CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
    identity_sha256 varchar(64) NOT NULL CHECK (identity_sha256 ~ '^[0-9a-f]{64}$'),
    result_json jsonb NOT NULL CHECK (jsonb_typeof(result_json)='object'),
    completed_at timestamptz NOT NULL DEFAULT now()
);

-- CRM advertisers are customer resources, distinct from pharmacy organizations and administrators.
CREATE TABLE merch_producers (
    id uuid PRIMARY KEY,
    source_system varchar(32) NOT NULL CHECK (source_system IN ('EPHARM','MERCH_CRM')),
    source_origin text NOT NULL,
    source_id uuid NOT NULL,
    source_payload jsonb NOT NULL CHECK (jsonb_typeof(source_payload)='object'),
    name text NOT NULL CHECK (length(btrim(name)) > 0),
    phone text,
    phone_normalized varchar(32),
    phone_verified_at timestamptz,
    source_account_role text NOT NULL DEFAULT 'advertiser',
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    UNIQUE(source_system,source_origin,source_id),
    CHECK (source_id=id AND ((source_system='EPHARM' AND source_origin='https://epharm.inkar.kz') OR
           (source_system='MERCH_CRM' AND source_origin='https://crm.inkar.kz')))
);

CREATE TABLE promo_producer_binding_versions (
    promo_id varchar(64) PRIMARY KEY REFERENCES promos(id) ON DELETE RESTRICT,
    revision bigint NOT NULL CHECK (revision>=0)
);
CREATE TABLE promo_producer_bindings (
    id uuid PRIMARY KEY,
    promo_id varchar(64) NOT NULL REFERENCES promos(id) ON DELETE RESTRICT,
    producer_id uuid NOT NULL REFERENCES merch_producers(id) ON DELETE RESTRICT,
    revision bigint NOT NULL CHECK (revision>0),
    whole_history boolean NOT NULL DEFAULT false,
    date_from date,
    date_to date,
    verification_reference text NOT NULL CHECK (length(btrim(verification_reference)) BETWEEN 1 AND 1000),
    verified_by uuid NOT NULL REFERENCES admin_users(id) ON DELETE RESTRICT,
    verified_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(promo_id,revision),
    CHECK ((whole_history AND date_from IS NULL AND date_to IS NULL) OR
           (NOT whole_history AND date_from IS NOT NULL AND date_to IS NOT NULL AND date_from<=date_to))
);
CREATE INDEX ix_promo_producer_scope ON promo_producer_bindings(producer_id,promo_id,date_from,date_to);
CREATE TABLE promo_producer_binding_retractions (
    binding_id uuid PRIMARY KEY REFERENCES promo_producer_bindings(id) ON DELETE RESTRICT,
    revision bigint NOT NULL CHECK (revision>0),
    verification_reference text NOT NULL CHECK (length(btrim(verification_reference)) BETWEEN 1 AND 1000),
    retracted_by uuid NOT NULL REFERENCES admin_users(id) ON DELETE RESTRICT,
    retracted_at timestamptz NOT NULL DEFAULT now()
);

CREATE FUNCTION preserve_unified_source_evidence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Source/import/producer evidence is append-only' USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER immutable_unified_source_links BEFORE UPDATE OR DELETE ON unified_source_links
    FOR EACH ROW EXECUTE FUNCTION preserve_unified_source_evidence();
CREATE TRIGGER immutable_unified_import_runs BEFORE UPDATE OR DELETE ON unified_import_runs
    FOR EACH ROW EXECUTE FUNCTION preserve_unified_source_evidence();
CREATE TRIGGER immutable_promo_producer_bindings BEFORE UPDATE OR DELETE ON promo_producer_bindings
    FOR EACH ROW EXECUTE FUNCTION preserve_unified_source_evidence();
CREATE TRIGGER immutable_promo_producer_retractions BEFORE UPDATE OR DELETE ON promo_producer_binding_retractions
    FOR EACH ROW EXECUTE FUNCTION preserve_unified_source_evidence();
CREATE TRIGGER immutable_pharmacist_person_links BEFORE UPDATE OR DELETE ON pharmacist_person_links
    FOR EACH ROW EXECUTE FUNCTION preserve_unified_source_evidence();
CREATE TRIGGER immutable_external_account_bindings BEFORE UPDATE OR DELETE ON unified_external_account_bindings
    FOR EACH ROW EXECUTE FUNCTION preserve_unified_source_evidence();
CREATE TRIGGER immutable_unified_identity_audit BEFORE UPDATE OR DELETE ON unified_identity_audit
    FOR EACH ROW EXECUTE FUNCTION preserve_unified_source_evidence();
