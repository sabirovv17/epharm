-- Existing pharmacists.balance is the opening checkpoint; do not invent historical credits.
CREATE TABLE pharmacist_wallet_checkpoints (
    pharmacist_id VARCHAR(64) PRIMARY KEY REFERENCES pharmacists(id) ON DELETE RESTRICT,
    balance_kzt BIGINT NOT NULL CHECK (balance_kzt >= 0),
    earned_30d_kzt BIGINT NOT NULL CHECK (earned_30d_kzt >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE pharmacist_wallet_entries (
    id UUID PRIMARY KEY,
    pharmacist_id VARCHAR(64) NOT NULL REFERENCES pharmacists(id) ON DELETE RESTRICT,
    source VARCHAR(32) NOT NULL CHECK (source IN
        ('receipt_bonus', 'training_bonus', 'merch_task', 'admin_adjustment', 'payout_reservation')),
    source_id VARCHAR(128) NOT NULL,
    amount_kzt BIGINT NOT NULL CHECK (amount_kzt <> 0),
    earned_delta_kzt BIGINT NOT NULL CHECK (earned_delta_kzt >= 0),
    currency VARCHAR(3) NOT NULL DEFAULT 'KZT' CHECK (currency = 'KZT'),
    context_key VARCHAR(255) NOT NULL,
    balance_after_kzt BIGINT NOT NULL CHECK (balance_after_kzt >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_wallet_source UNIQUE (source, source_id),
    CONSTRAINT ck_wallet_credit_direction CHECK (
        (source IN ('receipt_bonus', 'training_bonus', 'merch_task') AND amount_kzt > 0)
        OR (source = 'payout_reservation' AND amount_kzt < 0 AND earned_delta_kzt = 0)
        OR (source = 'admin_adjustment' AND earned_delta_kzt = 0)
    )
);
CREATE INDEX ix_wallet_pharmacist_created ON pharmacist_wallet_entries(pharmacist_id, created_at, id);

CREATE FUNCTION reject_wallet_entry_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Wallet entries are append-only' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER wallet_entries_append_only BEFORE UPDATE OR DELETE ON pharmacist_wallet_entries
FOR EACH ROW EXECUTE FUNCTION reject_wallet_entry_mutation();
CREATE TRIGGER wallet_checkpoints_append_only BEFORE UPDATE OR DELETE ON pharmacist_wallet_checkpoints
FOR EACH ROW EXECUTE FUNCTION reject_wallet_entry_mutation();
