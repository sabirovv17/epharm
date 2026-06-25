-- V031 — iPartID Стандарт-Н как дополнительный точный ключ матчинга POSM-кассы.
--
-- C# POSM-клиент отправляет iPartID в cart[].sku. Backend матчит корзину к productId
-- по ipart_id и barcode, а fallback по имени оставляет только для неполных кассовых логов.

ALTER TABLE products ADD COLUMN ipart_id VARCHAR(64);
CREATE INDEX ix_products_ipart_id ON products (ipart_id) WHERE ipart_id IS NOT NULL;

ALTER TABLE promos ADD COLUMN ipart_id VARCHAR(64);
