-- Recoverable learner copies are separate from password authentication and contain no plaintext.
CREATE TABLE learner_password_vault (
    pharmacist_id varchar(64) PRIMARY KEY REFERENCES pharmacists(id) ON DELETE RESTRICT,
    credential_fingerprint varchar(64) NOT NULL CHECK(credential_fingerprint ~ '^[0-9a-f]{64}$'),
    format_version integer NOT NULL CHECK(format_version=1),
    state text NOT NULL CHECK(state IN ('captured','unavailable')),
    key_id varchar(64), nonce bytea, ciphertext bytea,
    captured_at timestamptz NOT NULL,
    CHECK((state='captured' AND key_id IS NOT NULL AND octet_length(nonce)=12 AND octet_length(ciphertext)>=16)
       OR (state='unavailable' AND key_id IS NULL AND nonce IS NULL AND ciphertext IS NULL))
);
CREATE TABLE learner_credential_exports (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL REFERENCES admin_users(id) ON DELETE RESTRICT,
    authority_fingerprint varchar(64) NOT NULL,
    purpose text NOT NULL CHECK(length(purpose) BETWEEN 3 AND 200),
    pharmacist_ids jsonb NOT NULL CHECK(jsonb_typeof(pharmacist_ids)='array'),
    row_fingerprints jsonb NOT NULL CHECK(jsonb_typeof(row_fingerprints)='object'),
    snapshot_fingerprint varchar(64) NOT NULL,
    key_id varchar(64) NOT NULL, nonce bytea NOT NULL CHECK(octet_length(nonce)=12),
    ciphertext bytea NOT NULL CHECK(octet_length(ciphertext)>=16),
    row_count integer NOT NULL CHECK(row_count BETWEEN 1 AND 25),
    created_at timestamptz NOT NULL, expires_at timestamptz NOT NULL,
    CHECK(expires_at>created_at)
);
CREATE INDEX ix_learner_exports_expiry ON learner_credential_exports(expires_at,id);
CREATE TABLE learner_credential_export_attempts (
    id uuid PRIMARY KEY,actor_id uuid NOT NULL REFERENCES admin_users(id) ON DELETE RESTRICT,
    operation text NOT NULL CHECK(operation IN ('prepare','download')),created_at timestamptz NOT NULL
);
CREATE INDEX ix_learner_export_attempt_rate ON learner_credential_export_attempts(actor_id,operation,created_at);
CREATE TABLE learner_credential_export_audit (
    id uuid PRIMARY KEY, actor_id uuid NOT NULL REFERENCES admin_users(id) ON DELETE RESTRICT,
    export_id uuid, operation text NOT NULL CHECK(operation IN ('prepared','downloaded','revoked','denied','expired','invalidated')),
    purpose text NOT NULL CHECK(length(purpose)<=200),
    scope_fingerprint varchar(64) NOT NULL,
    row_count integer NOT NULL CHECK(row_count BETWEEN 0 AND 25),
    outcome text NOT NULL CHECK(length(outcome)<=80), created_at timestamptz NOT NULL
);
CREATE INDEX ix_learner_export_actor_rate ON learner_credential_export_audit(actor_id,created_at);
CREATE TRIGGER learner_credential_audit_append_only BEFORE UPDATE OR DELETE ON learner_credential_export_audit
    FOR EACH ROW EXECUTE FUNCTION reject_merch_inventory_audit_mutation();
