-- A recovery grant is distinct from the phone-wide OTP used by existing mobile clients.
CREATE TABLE mobile_password_reset_challenges (
    id UUID PRIMARY KEY,
    iin_hash VARCHAR(64) NOT NULL,
    pharmacist_id VARCHAR(64) REFERENCES pharmacists(id) ON DELETE CASCADE,
    phone VARCHAR(32),
    password_fingerprint VARCHAR(64),
    eligible BOOLEAN NOT NULL,
    provider VARCHAR(32) NOT NULL,
    code_hash VARCHAR(64),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    expires_at TIMESTAMPTZ NOT NULL,
    verified_at TIMESTAMPTZ,
    reset_token_hash VARCHAR(64) UNIQUE,
    reset_expires_at TIMESTAMPTZ,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (NOT eligible OR (pharmacist_id IS NOT NULL AND phone IS NOT NULL AND password_fingerprint IS NOT NULL))
);
CREATE INDEX ix_mobile_password_reset_phone ON mobile_password_reset_challenges(phone) WHERE consumed_at IS NULL;
CREATE INDEX ix_mobile_password_reset_expiry ON mobile_password_reset_challenges(expires_at);
CREATE TABLE mobile_password_reset_rate_limits (
    scope_hash VARCHAR(64) PRIMARY KEY,
    window_start TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL
);
