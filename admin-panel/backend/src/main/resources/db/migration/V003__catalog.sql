-- V003 — catalog: products.
--
-- Каталог продуктов фармы. На MVP — плоская таблица: brand + vendor + mnn
-- хранятся как строки прямо в продуктах (а не отдельными таблицами).
-- Когда появится Brand Manager Console (Этап 3.5+ или Module 3 PIM) — нормализуем
-- бренды в отдельную таблицу с FK. Сейчас лишняя ceremony не окупается.
--
-- ID — slug ('p_aqm_norm_s'), а не UUID. Эти коды стабильны, используются в
-- rules.trigger.value, в чеках, в POSM-событиях. UUID не даёт здесь никакой
-- ценности кроме непрозрачности.
--
-- mnn — Международное непатентованное наименование, ВОЗ-стандарт.
-- Rules с trigger.kind='mnn' матчат всех продуктов с одинаковой mnn.

CREATE TABLE products (
    id          VARCHAR(64) PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    brand       VARCHAR(128) NOT NULL,
    vendor      VARCHAR(64)  NOT NULL,
    mnn         VARCHAR(128) NOT NULL,
    price       INT          NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

ALTER TABLE products
    ADD CONSTRAINT ck_products_price_non_negative CHECK (price >= 0);

CREATE INDEX ix_products_brand ON products (brand);
CREATE INDEX ix_products_mnn ON products (mnn);
