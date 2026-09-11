CREATE DATABASE IF NOT EXISTS pharmacy_analytics;

CREATE TABLE IF NOT EXISTS pharmacy_analytics.fact_pharmacy_daily
(
    snapshot_date Date CODEC(Delta, ZSTD(1)),
    source_file LowCardinality(String),
    source_row UInt32 CODEC(Delta, ZSTD(1)),
    part_id UInt64 CODEC(Delta, ZSTD(1)),
    ware_id LowCardinality(String),
    sname LowCardinality(String),
    profile_id UInt32 CODEC(Delta, ZSTD(1)),
    caption LowCardinality(String),
    return_qty Float64 CODEC(Gorilla, ZSTD(1)),
    goods_received Float64 CODEC(Gorilla, ZSTD(1)),
    goods_transferred Float64 CODEC(Gorilla, ZSTD(1)),
    transferred_out Float64 CODEC(Gorilla, ZSTD(1)),
    adjustments Float64 CODEC(Gorilla, ZSTD(1)),
    sales Float64 CODEC(Gorilla, ZSTD(1)),
    retail_price Float64 CODEC(Gorilla, ZSTD(1)),
    cost_price Float64 CODEC(Gorilla, ZSTD(1)),
    stock_begin Float64 CODEC(Gorilla, ZSTD(1)),
    stock_end Float64 CODEC(Gorilla, ZSTD(1)),
    supplier LowCardinality(String),
    series String CODEC(ZSTD(1)),
    expiration_date Nullable(DateTime) CODEC(DoubleDelta, ZSTD(1)),
    quant_opt Float64 CODEC(Gorilla, ZSTD(1)),
    revenue Float64 CODEC(Gorilla, ZSTD(1)),
    loaded_at DateTime64(3, 'UTC') CODEC(DoubleDelta, ZSTD(1)),
    source_retail_price Nullable(Float64),
    source_cost_price Nullable(Float64),
    source_revenue Nullable(Float64)
)
ENGINE = MergeTree
PARTITION BY snapshot_date
ORDER BY (profile_id, ware_id, part_id, series, source_row)
SETTINGS index_granularity = 8192;

CREATE TABLE IF NOT EXISTS pharmacy_analytics.daily_summary
(
    snapshot_date Date,
    row_count UInt64,
    pharmacies UInt64,
    products UInt64,
    sales Float64,
    revenue Float64,
    goods_received Float64,
    returns Float64,
    stock_end Float64,
    negative_sales_rows UInt64,
    negative_revenue_rows UInt64,
    blank_ware_rows UInt64,
    loaded_at DateTime64(3, 'UTC')
)
ENGINE = MergeTree
PARTITION BY snapshot_date
ORDER BY snapshot_date;

CREATE TABLE IF NOT EXISTS pharmacy_analytics.pharmacy_daily_summary
(
    snapshot_date Date,
    profile_id UInt32,
    caption LowCardinality(String),
    rows UInt64,
    products UInt64,
    sales Float64,
    revenue Float64,
    goods_received Float64,
    returns Float64,
    stock_end Float64,
    loaded_at DateTime64(3, 'UTC')
)
ENGINE = MergeTree
PARTITION BY snapshot_date
ORDER BY (profile_id, caption);

CREATE TABLE IF NOT EXISTS pharmacy_analytics.product_daily_summary
(
    snapshot_date Date,
    ware_id LowCardinality(String),
    sname LowCardinality(String),
    supplier LowCardinality(String),
    rows UInt64,
    pharmacies UInt64,
    sales Float64,
    revenue Float64,
    goods_received Float64,
    stock_end Float64,
    loaded_at DateTime64(3, 'UTC')
)
ENGINE = MergeTree
PARTITION BY snapshot_date
ORDER BY (ware_id, sname);

CREATE TABLE IF NOT EXISTS pharmacy_analytics.etl_file_log
(
    file_name String,
    snapshot_date Date,
    status LowCardinality(String),
    row_count UInt64,
    byte_size UInt64,
    file_sha256 String,
    error String,
    updated_at DateTime64(3, 'UTC')
)
ENGINE = ReplacingMergeTree(updated_at)
ORDER BY file_name;
