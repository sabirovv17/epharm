-- V022 — промо-кампания становится товарной акцией (Medusa product + пороги + даты).
--
-- Концепция: админ в разделе «Промо» выбирает товар из витрины Medusa и сам
-- заполняет акцию: диапазон дат + ценовые пороги по количеству с бонусом за порог.
-- Эти промо едут в мобильную ленту фармацевта (GET /api/mobile/promotions),
-- к ним применяются фильтры/поиск, и из них же фармацевт выбирает при загрузке чека.
--
-- Старые поля кампании (brand/period/budget/spent/kpi/cover/pharmacies) остаются —
-- не ломаем существующие записи и админ-форму. brand теперь авто-заполняется
-- брендом выбранного товара Medusa (но колонка та же).

ALTER TABLE promos
    -- Линк на товар витрины Medusa (id вида 'prod_...'). NULL у старых кампаний.
    ADD COLUMN medusa_product_id VARCHAR(64),
    -- Снимок товара на момент привязки — для списка в админке и деградации,
    -- если Medusa недоступна (мобилка всё равно сначала тянет живые данные по id).
    ADD COLUMN product_name      VARCHAR(255) NOT NULL DEFAULT '',
    ADD COLUMN product_image     VARCHAR(1024),
    -- Структурный диапазон дат акции (заменяет free-text period для фильтра активности).
    ADD COLUMN date_start        DATE,
    ADD COLUMN date_end          DATE,
    -- Ценовые пороги акции: JSON-массив [{minQty:int, price:bigint, bonus:bigint}].
    -- price — цена за единицу при достижении порога; bonus — разовый бонус за порог
    -- (0 у первого порога). Пример: [{1,500,0},{10,600,900},{20,700,1900}].
    ADD COLUMN tiers             JSONB NOT NULL DEFAULT '[]'::jsonb;

-- Поиск промо по привязанному товару + выборка активных для мобильной ленты.
CREATE INDEX ix_promos_product ON promos (medusa_product_id);
CREATE INDEX ix_promos_active_window ON promos (status, date_end);
