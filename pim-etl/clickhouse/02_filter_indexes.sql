-- Exact multi-select filters used by the pharmacy dashboard.
-- Existing data is materialized explicitly; new inserts receive the indexes automatically.

ALTER TABLE pharmacy_analytics.fact_pharmacy_daily
    ADD INDEX IF NOT EXISTS idx_ware_id_bf ware_id
    TYPE bloom_filter(0.01) GRANULARITY 1;

ALTER TABLE pharmacy_analytics.fact_pharmacy_daily
    ADD INDEX IF NOT EXISTS idx_supplier_bf supplier
    TYPE bloom_filter(0.01) GRANULARITY 1;

ALTER TABLE pharmacy_analytics.fact_pharmacy_daily
    ADD INDEX IF NOT EXISTS idx_series_bf series
    TYPE bloom_filter(0.01) GRANULARITY 1;

ALTER TABLE pharmacy_analytics.fact_pharmacy_daily
    MATERIALIZE INDEX idx_ware_id_bf;

ALTER TABLE pharmacy_analytics.fact_pharmacy_daily
    MATERIALIZE INDEX idx_supplier_bf;

ALTER TABLE pharmacy_analytics.fact_pharmacy_daily
    MATERIALIZE INDEX idx_series_bf;
