-- Журнал показов и продаж сортируется по фактическому времени события и id.
-- Отдельные индексы позволяют PostgreSQL быстро собирать очередную страницу из
-- recommendation_events и pos_sales без полного чтения 90-дневного журнала.

CREATE INDEX IF NOT EXISTS idx_rec_events_journal_time
    ON recommendation_events ((COALESCE(displayed_at, shown_at)) DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_pos_sales_journal_time
    ON pos_sales (printed_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_rec_events_sale_attribution
    ON recommendation_events (sold_at, sale_id, seconds_to_sale, id)
    WHERE sale_id IS NOT NULL AND seconds_to_sale IS NOT NULL;
