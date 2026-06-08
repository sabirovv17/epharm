-- ТЗ §3.5 — Загрузка и сверка чеков (доказательная база начисления бонуса).
--
-- pending_bonuses: POSM регистрирует переключение (фармацевт принял замену/cross-sell).
--   Бонус НЕ начислен — ждёт подтверждения чеком. Источник — Module 2 (POSM, Этап 5);
--   на MVP заполняется seed'ом и сабмитом чека.
-- receipts: чек, загруженный фармацевтом (фото в MinIO или QR фискального чека).
--   OCR+ОФД извлекают поля, ReconcileService сверяет с pending_bonus и решает ветку.

CREATE TABLE pending_bonuses (
    id              VARCHAR(64) PRIMARY KEY,
    pharmacist_id   VARCHAR(64) NOT NULL,
    pharmacist_name VARCHAR(255) NOT NULL DEFAULT '',
    pharmacy_id     VARCHAR(64) NOT NULL,
    pharmacy_name   VARCHAR(255) NOT NULL DEFAULT '',
    sku             VARCHAR(64) NOT NULL,
    product_name    VARCHAR(255) NOT NULL DEFAULT '',
    expected_amount BIGINT NOT NULL DEFAULT 0,
    bonus           BIGINT NOT NULL DEFAULT 0,
    rule_id         VARCHAR(64),
    status          VARCHAR(32) NOT NULL DEFAULT 'awaiting_receipt'
                    CHECK (status IN ('awaiting_receipt', 'matched', 'expired')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_pending_bonuses_pharmacist ON pending_bonuses (pharmacist_id, status);
CREATE INDEX idx_pending_bonuses_sku ON pending_bonuses (sku);

CREATE TABLE receipts (
    id              VARCHAR(64) PRIMARY KEY,
    pharmacist_id   VARCHAR(64) NOT NULL,
    pharmacist_name VARCHAR(255) NOT NULL DEFAULT '',
    pharmacy_id     VARCHAR(64) NOT NULL,
    pharmacy_name   VARCHAR(255) NOT NULL DEFAULT '',
    photo_url       VARCHAR(512),
    qr_raw          VARCHAR(512),
    fiscal_id       VARCHAR(128),
    parsed_sku      VARCHAR(64) NOT NULL DEFAULT '',
    parsed_amount   BIGINT NOT NULL DEFAULT 0,
    parsed_cashier  VARCHAR(255) NOT NULL DEFAULT '',
    parsed_at       TIMESTAMPTZ,
    ocr_score       NUMERIC(5,2) NOT NULL DEFAULT 0,
    -- ветка сверки: pending (ручная модерация) / flagged (анти-фрод) / approved / rejected
    status          VARCHAR(32) NOT NULL DEFAULT 'pending'
                    CHECK (status IN ('pending', 'flagged', 'approved', 'rejected')),
    auto_approved   BOOLEAN NOT NULL DEFAULT false,
    flag_reason     VARCHAR(64),
    pending_bonus_id VARCHAR(64) REFERENCES pending_bonuses (id) ON DELETE SET NULL,
    bonus_credited  BIGINT NOT NULL DEFAULT 0,
    reviewer        VARCHAR(64),
    reviewed_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_receipts_status ON receipts (status);
CREATE INDEX idx_receipts_pharmacist ON receipts (pharmacist_id);
-- Для анти-фрод детекта дубля фискального чека.
CREATE INDEX idx_receipts_fiscal ON receipts (fiscal_id);
