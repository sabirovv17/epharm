CREATE TABLE merch_producer_challenges (
    id uuid PRIMARY KEY,
    purpose varchar(16) NOT NULL CHECK(purpose='login'),
    customer_id uuid REFERENCES merch_producers(id) ON DELETE RESTRICT,
    phone varchar(32) NOT NULL,
    eligible boolean NOT NULL,
    provider varchar(64) NOT NULL,
    code_hash varchar(64),
    attempts integer NOT NULL DEFAULT 0 CHECK(attempts>=0),
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    CHECK(NOT eligible OR customer_id IS NOT NULL)
);
CREATE INDEX merch_producer_challenge_phone_idx ON merch_producer_challenges(phone,created_at);
CREATE TABLE merch_producer_sessions (
    id uuid PRIMARY KEY,
    customer_id uuid NOT NULL REFERENCES merch_producers(id) ON DELETE RESTRICT,
    access_token_hash varchar(64) NOT NULL UNIQUE,
    phone_fingerprint varchar(64) NOT NULL,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz
);
CREATE TABLE merch_producer_refresh_tokens (
    token_hash varchar(64) PRIMARY KEY,
    session_id uuid NOT NULL REFERENCES merch_producer_sessions(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz
);
CREATE INDEX merch_producer_refresh_session_idx ON merch_producer_refresh_tokens(session_id);
CREATE TABLE merch_producer_auth_limits (
    scope_hash varchar(64) PRIMARY KEY,
    window_start timestamptz NOT NULL,
    attempts integer NOT NULL CHECK(attempts>0)
);
