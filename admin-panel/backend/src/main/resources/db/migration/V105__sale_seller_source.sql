-- Optional receipt-time native identity. No historical attribution or financial backfill.
ALTER TABLE pos_sales ADD COLUMN seller_source JSONB;
