-- V008 — payout_batches + payout_items.
--
-- PayoutBatch — батч выплат за период (1-15 / 16-31 числа), пер. ТЗ §3.6.
-- Cron Этапа 4 будет генерировать pending batch 1-го и 16-го числа.
-- HQ-админ ревьюит, аппрувит → деньги уходят на ЭлКарт (out-of-scope, мы только
-- меняем status в БД).
--
-- PayoutItem — строка в батче по конкретному фармацевту. denormalized name +
-- pharmacy + city для read-fast (admin не делает join при показе батча).
-- flag — текст-предупреждение от reconcile engine («Слишком высокий бонус» итд).

CREATE TABLE payout_batches (
    id              VARCHAR(64) PRIMARY KEY,
    period          VARCHAR(64)  NOT NULL,
    status          VARCHAR(32)  NOT NULL,
    pharmacists     INT          NOT NULL DEFAULT 0,
    amount          BIGINT       NOT NULL DEFAULT 0,
    items           INT          NOT NULL DEFAULT 0,
    reviewer_id     VARCHAR(64),
    reviewer_name   VARCHAR(255),
    approved_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

ALTER TABLE payout_batches
    ADD CONSTRAINT ck_payout_batches_status CHECK (status IN ('pending', 'approved'));

CREATE INDEX ix_payout_batches_status     ON payout_batches (status);
CREATE INDEX ix_payout_batches_created_at ON payout_batches (created_at DESC);

CREATE TABLE payout_items (
    id              VARCHAR(64) PRIMARY KEY,
    batch_id        VARCHAR(64) NOT NULL REFERENCES payout_batches (id) ON DELETE CASCADE,
    pharmacist_id   VARCHAR(64) NOT NULL REFERENCES pharmacists (id) ON DELETE RESTRICT,
    pharmacist_name VARCHAR(255) NOT NULL,
    pharmacy        VARCHAR(255) NOT NULL,
    city            VARCHAR(128) NOT NULL DEFAULT '',
    receipts        INT          NOT NULL DEFAULT 0,
    rules           INT          NOT NULL DEFAULT 0,
    amount          BIGINT       NOT NULL DEFAULT 0,
    flag            VARCHAR(255),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_payout_items_batch_id      ON payout_items (batch_id);
CREATE INDEX ix_payout_items_pharmacist_id ON payout_items (pharmacist_id);
