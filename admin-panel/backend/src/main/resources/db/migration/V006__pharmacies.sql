-- V006 — chains + pharmacies. Аптеки сгруппированы в сети.
--
-- Group (pilot/control/rolled) — фундамент для lift-аналитики (Этап 3.6+):
--   pilot   — пилотный участник, видит Rules + получает бонусы
--   control — контрольная группа, без Rules — измерение baseline'а
--   rolled  — массовая раскатка после успешного пилота
--
-- Метрики (receipts_30d, gmv_30d, lift_pct, rules_accepted) — denormalized.
-- Их пишет ETL из Этапа 4 (receipt flow). На MVP — из DevDataSeeder.

CREATE TABLE chains (
    id            VARCHAR(64) PRIMARY KEY,
    name          VARCHAR(255) NOT NULL,
    color         VARCHAR(16)  NOT NULL DEFAULT '',
    points        INT          NOT NULL DEFAULT 0,
    "group"       VARCHAR(32)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

ALTER TABLE chains
    ADD CONSTRAINT ck_chains_group CHECK ("group" IN ('pilot', 'control', 'rolled'));

CREATE TABLE pharmacies (
    id              VARCHAR(64) PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    chain_id        VARCHAR(64)  NOT NULL REFERENCES chains (id) ON DELETE RESTRICT,
    chain_name      VARCHAR(255) NOT NULL,
    city            VARCHAR(128) NOT NULL,
    district        VARCHAR(128) NOT NULL DEFAULT '',
    addr            VARCHAR(255) NOT NULL DEFAULT '',
    "group"         VARCHAR(32)  NOT NULL,
    pharmacists     INT          NOT NULL DEFAULT 0,
    receipts_30d    INT          NOT NULL DEFAULT 0,
    gmv_30d         BIGINT       NOT NULL DEFAULT 0,
    lift_pct        NUMERIC(5,2) NOT NULL DEFAULT 0,
    rules_accepted  INT          NOT NULL DEFAULT 0,
    active          BOOLEAN      NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

ALTER TABLE pharmacies
    ADD CONSTRAINT ck_pharmacies_group        CHECK ("group" IN ('pilot', 'control', 'rolled')),
    ADD CONSTRAINT ck_pharmacies_metrics_nonneg
        CHECK (receipts_30d >= 0 AND gmv_30d >= 0 AND rules_accepted >= 0 AND pharmacists >= 0);

CREATE INDEX ix_pharmacies_chain_id ON pharmacies (chain_id);
CREATE INDEX ix_pharmacies_group    ON pharmacies ("group");
CREATE INDEX ix_pharmacies_city     ON pharmacies (city);
