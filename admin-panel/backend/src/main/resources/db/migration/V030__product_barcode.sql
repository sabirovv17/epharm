-- V030 — штрих-код (EAN-13) как ключ матчинга POSM-кассы.
--
-- Касса Стандарт-Н сканирует товар → C# вытаскивает EAN-13 из лога → /api/posm/recommend.
-- Бэкенд матчит корзину к нашему productId по штрих-коду (а не по бесполезному GUID-sku
-- Medusa). barcode заполняется для кампанийных/правиловых товаров из Medusa
-- (variant.barcode) при сохранении правил и ежедневном рефреше.
--
-- products.barcode  — EAN-13 товара каталога (PRIMARY ключ матчинга кассы).
-- promos.barcode     — EAN-13 продвигаемого товара кампании (снимок для авторинга/админки).
--
-- Удаляем product_pos_codes: матчинг по iPartID отменён в пользу штрих-кода
-- (таблица пустая в проде — писалась только тестом).

ALTER TABLE products ADD COLUMN barcode VARCHAR(32);
CREATE INDEX ix_products_barcode ON products (barcode) WHERE barcode IS NOT NULL;

ALTER TABLE promos ADD COLUMN barcode VARCHAR(32);

DROP TABLE IF EXISTS product_pos_codes;
