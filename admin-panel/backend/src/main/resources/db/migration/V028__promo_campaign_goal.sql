-- Цель кампании (одна на всю кампанию) как первоклассные поля promos.
-- Раньше цель ехала только в rules.card (jsonb), что теряло её при кампании без пар.
-- Теперь источник истины — promos.*, а в rules.card цель денормализуется для POSM-кассы.
ALTER TABLE promos ADD COLUMN IF NOT EXISTS goal_label VARCHAR(120);
ALTER TABLE promos ADD COLUMN IF NOT EXISTS goal_target INTEGER;
ALTER TABLE promos ADD COLUMN IF NOT EXISTS goal_bonus INTEGER;
