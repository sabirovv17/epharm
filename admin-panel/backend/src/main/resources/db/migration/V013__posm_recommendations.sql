-- ТЗ §4 — POSM Rules Engine: рекомендации замены / cross-sell + фиксация факта и результата.
--
-- product_pos_codes:   связь артикула кассы Стандарт-Н (iPartID, int) с нашим productId.
--                      POSM-клиент конвертит код перед отправкой корзины в /api/posm/recommend.
-- recommendation_events: доказательная база бонуса. Каждая показанная рекомендация = строка
--                      (outcome='shown'); результат фиксируется через /outcome (accepted/rejected).
--                      При accepted создаётся pending_bonus (V012) → дальше сверка чеков.

CREATE TABLE product_pos_codes (
    pos_code   BIGINT PRIMARY KEY,
    product_id VARCHAR(64) NOT NULL REFERENCES products (id) ON DELETE CASCADE
);
CREATE INDEX idx_product_pos_codes_product ON product_pos_codes (product_id);

CREATE TABLE recommendation_events (
    id               VARCHAR(64) PRIMARY KEY,
    session_id       VARCHAR(64) NOT NULL,
    pharmacist_id    VARCHAR(64) NOT NULL,
    pharmacy_id      VARCHAR(64) NOT NULL,
    rule_id          VARCHAR(64) NOT NULL,
    kind             VARCHAR(32) NOT NULL
                     CHECK (kind IN ('substitution', 'crosssell')),
    trigger_sku      VARCHAR(64),
    recommend_sku    VARCHAR(64) NOT NULL,
    recommend_name   VARCHAR(255) NOT NULL DEFAULT '',
    expected_amount  BIGINT NOT NULL DEFAULT 0,
    bonus            BIGINT NOT NULL DEFAULT 0,
    outcome          VARCHAR(32) NOT NULL DEFAULT 'shown'
                     CHECK (outcome IN ('shown', 'accepted', 'rejected', 'expired')),
    pending_bonus_id VARCHAR(64) REFERENCES pending_bonuses (id) ON DELETE SET NULL,
    shown_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at       TIMESTAMPTZ
);
CREATE INDEX idx_rec_events_session ON recommendation_events (session_id);
CREATE INDEX idx_rec_events_pharmacist ON recommendation_events (pharmacist_id);
CREATE INDEX idx_rec_events_outcome ON recommendation_events (outcome);
