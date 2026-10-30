-- Additive opt-in access control. Existing accounts stay LEGACY; no data/roles are rewritten.
ALTER TABLE admin_users ADD COLUMN access_mode varchar(16) NOT NULL DEFAULT 'LEGACY'
    CHECK (access_mode IN ('LEGACY', 'MANAGED'));

CREATE TABLE unified_persons (
    id uuid PRIMARY KEY,
    verification_reference text NOT NULL CHECK (length(verification_reference) > 0)
);

CREATE TABLE unified_admin_profiles (
    account_id uuid PRIMARY KEY REFERENCES admin_users(id) ON DELETE CASCADE,
    person_id uuid NOT NULL REFERENCES unified_persons(id),
    job_title varchar(255) NOT NULL DEFAULT '',
    version bigint NOT NULL DEFAULT 0,
    grants_json text NOT NULL DEFAULT '[]',
    updated_at timestamptz NOT NULL DEFAULT now()
);
-- person_id is an explicit human binding; multiple accounts may belong to the same person.
-- It is never inferred from email, phone, IIN or a job title.
CREATE INDEX ix_unified_admin_person ON unified_admin_profiles(person_id);

CREATE TABLE unified_admin_access_audit (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL,
    target_id uuid NOT NULL,
    operation varchar(32) NOT NULL,
    target_version bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
-- No password, token, IIN, phone or credential values in the access audit.
