-- V007 — pharmacists registry. Каждый фармацевт привязан к одной аптеке.
--
-- Tier (Silver/Gold/Platinum) определяет multiplier бонуса в Этапе 4:
--   Silver = 1.0×, Gold = 1.1×, Platinum = 1.2× (после сдачи AI-экзамена).
--
-- IIN — Индивидуальный Идентификационный Номер (12 цифр, Казахстан).
-- Хранится как VARCHAR(12) для compatibility с международными residentid.
--
-- Status: active / pending / blocked.
--   pending — зарегистрирован через POSM CDP-form, ждёт активации (Этап 5)
--   blocked — нарушения правил, manual ban через админ-консоль

CREATE TABLE pharmacists (
    id                  VARCHAR(64) PRIMARY KEY,
    name                VARCHAR(255) NOT NULL,
    iin                 VARCHAR(12)  NOT NULL,
    phone               VARCHAR(32)  NOT NULL,
    pharmacy_id         VARCHAR(64)  NOT NULL REFERENCES pharmacies (id) ON DELETE RESTRICT,
    pharmacy_name       VARCHAR(255) NOT NULL,
    city                VARCHAR(128) NOT NULL DEFAULT '',
    tier                VARCHAR(16)  NOT NULL,
    receipts_30d        INT          NOT NULL DEFAULT 0,
    rules_accepted_30d  INT          NOT NULL DEFAULT 0,
    earned_30d          BIGINT       NOT NULL DEFAULT 0,
    balance             BIGINT       NOT NULL DEFAULT 0,
    courses_done        INT          NOT NULL DEFAULT 0,
    courses_total       INT          NOT NULL DEFAULT 0,
    status              VARCHAR(32)  NOT NULL,
    joined_at           DATE         NOT NULL DEFAULT now(),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

ALTER TABLE pharmacists
    ADD CONSTRAINT ck_pharmacists_tier   CHECK (tier   IN ('Silver', 'Gold', 'Platinum')),
    ADD CONSTRAINT ck_pharmacists_status CHECK (status IN ('active', 'pending', 'blocked')),
    ADD CONSTRAINT ck_pharmacists_metrics_nonneg
        CHECK (receipts_30d >= 0 AND rules_accepted_30d >= 0 AND earned_30d >= 0
               AND courses_done >= 0 AND courses_total >= 0);

CREATE UNIQUE INDEX ux_pharmacists_iin       ON pharmacists (iin);
CREATE UNIQUE INDEX ux_pharmacists_phone     ON pharmacists (phone);
CREATE INDEX ix_pharmacists_pharmacy_id ON pharmacists (pharmacy_id);
CREATE INDEX ix_pharmacists_status      ON pharmacists (status);
CREATE INDEX ix_pharmacists_tier        ON pharmacists (tier);
