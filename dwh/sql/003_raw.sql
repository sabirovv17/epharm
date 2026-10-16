CREATE TABLE IF NOT EXISTS dwh_raw.event_envelope
(
    source_system LowCardinality(String),
    source_entity LowCardinality(String),
    source_record_id String,
    source_updated_at Nullable(DateTime64(3, 'UTC')),
    operation LowCardinality(String) DEFAULT 'snapshot',
    payload String,
    payload_hash FixedString(64),
    batch_id UUID,
    ingested_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(ingested_at)
PARTITION BY toYYYYMM(ingested_at)
ORDER BY (source_system, source_entity, source_record_id, payload_hash);

CREATE TABLE IF NOT EXISTS dwh_raw.standardn_pharmacy_daily
(
    snapshot_date Date,
    source_file LowCardinality(String),
    source_row UInt64,
    part_id String,
    ware_id String,
    sname String,
    profile_id UInt64,
    caption String,
    return_qty Float64,
    goods_received Float64,
    goods_transferred Float64,
    transferred_out Float64,
    adjustments Float64,
    sales Float64,
    retail_price Nullable(Decimal(18, 2)),
    cost_price Nullable(Decimal(18, 2)),
    stock_begin Float64,
    stock_end Float64,
    supplier String,
    series String,
    expiration_date Nullable(Date),
    quant_opt Float64,
    revenue Decimal(20, 2),
    loaded_at DateTime64(3, 'UTC'),
    source_retail_price Nullable(Decimal(18, 2)),
    source_cost_price Nullable(Decimal(18, 2)),
    source_revenue Nullable(Decimal(20, 2)),
    ingested_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(ingested_at)
PARTITION BY toYYYYMM(snapshot_date)
ORDER BY (snapshot_date, profile_id, ware_id, part_id, source_file, source_row);
