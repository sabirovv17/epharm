-- V023 — индекс под фактический запрос мобильной ленты промо.
--
-- V022 создал ix_promos_active_window(status, date_end), но реальный запрос ленты —
-- findAllByStatusRawAndMedusaProductIdIsNotNullOrderByUpdatedAtDesc — фильтрует по
-- status + (medusa_product_id IS NOT NULL) и сортирует по updated_at DESC; date_end в
-- запросе не участвует (окно дат проверяется в сервисе на загруженном наборе). Тот индекс
-- не использовался. Заменяем его на частичный под реальный предикат.

DROP INDEX IF EXISTS ix_promos_active_window;

CREATE INDEX ix_promos_feed ON promos (status, updated_at DESC)
    WHERE medusa_product_id IS NOT NULL;
