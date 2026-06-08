-- V004 — rules: подсказки замены и кросс-сейл, главный объект Rules Engine.
--
-- Trigger хранится как jsonb для гибкости:
--   {kind:'product',     value:'p_aql_norm_s'}
--   {kind:'mnn',         value:'Морская вода', exclude:['p_aqm_norm_s', ...]}
--   {kind:'product_any', value:['p_aqm_norm_s','p_aqm_strong']}
--
-- На MVP — каждое правило рекомендует один конкретный продукт (recommend). FK на
-- products гарантирует целостность. Когда появится multi-recommend
-- (несколько товаров с весами) — перейдём на jsonb.
--
-- advantages — массив строк («Изотонический раствор», «Бонус 520 ₸», ...) для
-- preview-блока «как фармацевт увидит на кассе».
--
-- ab_test — null (без AB) или {variant:'B', share:50}.
--
-- Метрики (impressions/accepts/convRate/revenue/payout) — denormalized inline.
-- На MVP правил мало (десятки), агрегации update'ятся редко из ETL Этапа 4. Позже
-- вынесем в rule_metrics_daily если потребуется честная time-series аналитика.
--
-- ID — slug ('r_001', 'r_a3') а не UUID, аналогично products. Сервер генерирует
-- 'r_<short-uuid>' для новых правил.

CREATE TABLE rules (
    id              VARCHAR(64) PRIMARY KEY,
    type            VARCHAR(32)   NOT NULL,
    status          VARCHAR(32)   NOT NULL,
    trigger         JSONB         NOT NULL,
    recommend       VARCHAR(64)   NOT NULL REFERENCES products (id) ON DELETE RESTRICT,
    bonus           INT           NOT NULL DEFAULT 0,
    script          TEXT          NOT NULL DEFAULT '',
    advantages      JSONB         NOT NULL DEFAULT '[]'::jsonb,
    ab_test         JSONB,
    pharmacies      INT           NOT NULL DEFAULT 0,
    impressions     INT           NOT NULL DEFAULT 0,
    accepts         INT           NOT NULL DEFAULT 0,
    conv_rate       NUMERIC(5,2)  NOT NULL DEFAULT 0,
    revenue         BIGINT        NOT NULL DEFAULT 0,
    payout          BIGINT        NOT NULL DEFAULT 0,
    created_by      VARCHAR(64)   NOT NULL DEFAULT '',
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

ALTER TABLE rules
    ADD CONSTRAINT ck_rules_type   CHECK (type   IN ('substitution', 'crosssell')),
    ADD CONSTRAINT ck_rules_status CHECK (status IN ('active', 'paused', 'draft', 'archived')),
    ADD CONSTRAINT ck_rules_bonus_non_negative CHECK (bonus >= 0);

CREATE INDEX ix_rules_type_status ON rules (type, status);
CREATE INDEX ix_rules_recommend   ON rules (recommend);
CREATE INDEX ix_rules_updated_at  ON rules (updated_at DESC);
