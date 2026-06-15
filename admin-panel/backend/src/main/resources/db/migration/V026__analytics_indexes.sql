-- Аналитика на реальных данных (ТЗ §3.2): Dashboard/Lift теперь агрегируют
-- recommendation_events и pos_sales вместо денормализованных полей сущностей.
-- Под эти GROUP BY / countBy... нужны индексы, иначе full scan при росте данных.

-- Группировки аналитики по правилу / аптеке / товару.
CREATE INDEX IF NOT EXISTS idx_rec_events_rule ON recommendation_events (rule_id);
CREATE INDEX IF NOT EXISTS idx_rec_events_pharmacy ON recommendation_events (pharmacy_id);
CREATE INDEX IF NOT EXISTS idx_rec_events_recommend_sku ON recommendation_events (recommend_sku);
CREATE INDEX IF NOT EXISTS idx_rec_events_shown_at ON recommendation_events (shown_at);

-- Прогресс цели «N/target»: countByPharmacistIdAndRuleIdAndOutcomeRawAndDecidedAtAfter.
CREATE INDEX IF NOT EXISTS idx_rec_events_goal
    ON recommendation_events (pharmacist_id, rule_id, outcome, decided_at);

-- receipts30d по аптеке за окно: countByPharmacySince / countByPharmaciesSince.
CREATE INDEX IF NOT EXISTS idx_pos_sales_pharmacy_printed
    ON pos_sales (pharmacy_id, printed_at);

-- Горячий путь /recommend: findFirstBySessionIdAndRuleIdOrderByShownAtDesc
-- (идемпотентный показ). Составной индекс вместо двух одиночных.
CREATE INDEX IF NOT EXISTS idx_rec_events_session_rule
    ON recommendation_events (session_id, rule_id, shown_at DESC);
