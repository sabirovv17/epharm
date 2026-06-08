-- V018 — богатая карточка рекомендации (Фаза 2): наполнение карточки фармацевта из админки.
--
-- Контракт «всё из админки»: таблица сравнения, бейдж «партнёр», цель — задаются в правиле
-- (Rules Engine) и пуллятся на кассу. Часть данных (название/цена/объём товара) берём из каталога.
--
-- products.volume — атрибут товара («150 мл»), показывается в строке «покупатель попросил».
-- rules.card (jsonb, nullable) — презентационные поля правила:
--   { partnerLabel, comparison:[{label,triggerValue,recommendValue,recommendHighlight}],
--     goalLabel, goalTarget, goalBonus }
-- null = у правила нет богатой карточки (касса покажет базовые поля + advantages).
-- Цель-прогресс «7/10» считается динамически из recommendation_events (не хранится).

ALTER TABLE products ADD COLUMN volume VARCHAR(64) NOT NULL DEFAULT '';

ALTER TABLE rules ADD COLUMN card JSONB;
