-- An exact source capability is not a POSM issuer or a verified human identity.
CREATE TABLE merch_legacy_capability_aliases (
    link_id uuid PRIMARY KEY REFERENCES merch_task_links(id) ON DELETE RESTRICT,
    dispatch_id uuid NOT NULL REFERENCES merch_task_dispatches(id) ON DELETE RESTRICT,
    branch_id uuid NOT NULL REFERENCES merch_branches(id) ON DELETE RESTRICT,
    token_hash varchar(64) NOT NULL CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    source_channel text CHECK (source_channel IS NULL OR source_channel='pharmapay_posm'),
    source_expires_at timestamptz NOT NULL,
    source_payload_sha256 varchar(64) NOT NULL CHECK (source_payload_sha256 ~ '^[0-9a-f]{64}$'),
    source_artifact_sha256 varchar(64) NOT NULL CHECK (source_artifact_sha256 ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER immutable_merch_legacy_capability_aliases
    BEFORE UPDATE OR DELETE ON merch_legacy_capability_aliases
    FOR EACH ROW EXECUTE FUNCTION preserve_unified_source_evidence();
