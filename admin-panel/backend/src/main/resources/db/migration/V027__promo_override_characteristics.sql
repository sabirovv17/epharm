-- Ручное переопределение ХАРАКТЕРИСТИК товара в кампании (как override_description/image).
-- Свободный текст (по строке = одна характеристика). Пусто → берём keyFacts из Medusa.
-- Показывается фармацевту в детали товара вместо данных Medusa, если задано.
ALTER TABLE promos ADD COLUMN IF NOT EXISTS override_characteristics TEXT;
