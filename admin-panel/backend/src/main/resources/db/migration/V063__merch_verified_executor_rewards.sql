-- A QR scan is not an identity proof or a credit. Only a fresh, purpose-bound OTP
-- produces a short-lived grant; an authenticated CRM approval credits the wallet.
CREATE TABLE merch_executor_otp_challenges (
    id UUID PRIMARY KEY,
    challenge_hash VARCHAR(64) NOT NULL,
    pharmacist_id VARCHAR(64) NOT NULL REFERENCES pharmacists(id) ON DELETE RESTRICT,
    phone VARCHAR(32) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    code_hash VARCHAR(64),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ
);
CREATE INDEX ix_merch_otp_phone ON merch_executor_otp_challenges(phone, created_at);

CREATE TABLE merch_executor_bindings (
    id UUID PRIMARY KEY,
    dispatch_id UUID NOT NULL UNIQUE,
    pharmacist_id VARCHAR(64) NOT NULL REFERENCES pharmacists(id) ON DELETE RESTRICT,
    pharmacy_id VARCHAR(64) NOT NULL REFERENCES pharmacies(id) ON DELETE RESTRICT,
    reward_kzt BIGINT NOT NULL CHECK (reward_kzt > 0),
    task_due_at TIMESTAMPTZ NOT NULL,
    verified_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TRIGGER merch_bindings_append_only BEFORE UPDATE OR DELETE ON merch_executor_bindings
FOR EACH ROW EXECUTE FUNCTION reject_wallet_entry_mutation();

CREATE TABLE merch_executor_grants (
    token_hash VARCHAR(64) PRIMARY KEY,
    otp_challenge_id UUID NOT NULL UNIQUE REFERENCES merch_executor_otp_challenges(id) ON DELETE RESTRICT,
    dispatch_id UUID NOT NULL,
    pharmacist_id VARCHAR(64) NOT NULL REFERENCES pharmacists(id) ON DELETE RESTRICT,
    pharmacy_id VARCHAR(64) NOT NULL REFERENCES pharmacies(id) ON DELETE RESTRICT,
    phone_hash VARCHAR(64) NOT NULL,
    nonce_hash VARCHAR(64) NOT NULL,
    reward_kzt BIGINT NOT NULL CHECK (reward_kzt > 0),
    task_due_at TIMESTAMPTZ NOT NULL,
    verified_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    redeemed_at TIMESTAMPTZ,
    binding_id UUID REFERENCES merch_executor_bindings(id) ON DELETE RESTRICT
);

CREATE TABLE merch_reward_events (
    event_id UUID PRIMARY KEY,
    dispatch_id UUID NOT NULL UNIQUE,
    binding_id UUID NOT NULL REFERENCES merch_executor_bindings(id) ON DELETE RESTRICT,
    pharmacist_id VARCHAR(64) NOT NULL REFERENCES pharmacists(id) ON DELETE RESTRICT,
    pharmacy_id VARCHAR(64) NOT NULL REFERENCES pharmacies(id) ON DELETE RESTRICT,
    amount_kzt BIGINT NOT NULL CHECK (amount_kzt > 0),
    submitted_at TIMESTAMPTZ NOT NULL,
    approved_at TIMESTAMPTZ NOT NULL,
    approved_by_crm_user_id UUID NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    wallet_entry_id UUID NOT NULL UNIQUE REFERENCES pharmacist_wallet_entries(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TRIGGER merch_reward_events_append_only BEFORE UPDATE OR DELETE ON merch_reward_events
FOR EACH ROW EXECUTE FUNCTION reject_wallet_entry_mutation();
