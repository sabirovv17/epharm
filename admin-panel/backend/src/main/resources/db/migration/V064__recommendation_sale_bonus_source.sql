-- Existing accepted-click reservations stay unbound. Never backfill historical display/sale data.
ALTER TABLE pending_bonuses ADD COLUMN source_sale_id VARCHAR(64)
    REFERENCES pos_sales (id);
CREATE UNIQUE INDEX uq_pending_bonus_source_sale_sku
    ON pending_bonuses (source_sale_id, sku) WHERE source_sale_id IS NOT NULL;
