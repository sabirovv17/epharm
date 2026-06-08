-- V005 — promo campaigns: связка «бренд + период + бюджет + KPI».
--
-- На MVP — плоская таблица. m2m `promo_pharmacies` появится в Этапе 3.4 когда
-- будет реальная `pharmacies` таблица. Сейчас держим denormalized counter
-- `pharmacies INT` (сколько аптек участвует).
--
-- period — VARCHAR ('01.05 — 31.05') а не DATE-диапазон: real-world промо
-- бывают с multi-stage сроками, free-text проще и достаточен для UI.
-- Позже добавим period_start/period_end для analytics-фильтров.
--
-- ID — slug ('pr_<short>') аналогично rules + products.

CREATE TABLE promos (
    id           VARCHAR(64) PRIMARY KEY,
    title        VARCHAR(255) NOT NULL,
    status       VARCHAR(32)  NOT NULL,
    brand        VARCHAR(128) NOT NULL,
    period       VARCHAR(64)  NOT NULL DEFAULT '',
    pharmacies   INT          NOT NULL DEFAULT 0,
    budget       BIGINT       NOT NULL DEFAULT 0,
    spent        BIGINT       NOT NULL DEFAULT 0,
    kpi          VARCHAR(255) NOT NULL DEFAULT '',
    cover        VARCHAR(16)  NOT NULL DEFAULT '',
    created_by   VARCHAR(64)  NOT NULL DEFAULT '',
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

ALTER TABLE promos
    ADD CONSTRAINT ck_promos_status        CHECK (status IN ('active', 'draft', 'paused', 'archived')),
    ADD CONSTRAINT ck_promos_budget_nonneg CHECK (budget >= 0),
    ADD CONSTRAINT ck_promos_spent_nonneg  CHECK (spent  >= 0),
    ADD CONSTRAINT ck_promos_pharmacies_nonneg CHECK (pharmacies >= 0);

CREATE INDEX ix_promos_status     ON promos (status);
CREATE INDEX ix_promos_brand      ON promos (brand);
CREATE INDEX ix_promos_updated_at ON promos (updated_at DESC);
