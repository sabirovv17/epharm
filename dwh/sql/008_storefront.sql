CREATE TABLE IF NOT EXISTS dwh_raw.storefront_catalog_product
(
    batch_id UUID,
    source_product_id String,
    ware_id String DEFAULT '',
    mapping_source LowCardinality(String) DEFAULT '',
    handle String,
    title String,
    brand String DEFAULT '',
    manufacturer String DEFAULT '',
    country String DEFAULT '',
    mnn String DEFAULT '',
    atc String DEFAULT '',
    rx_otc String DEFAULT '',
    active Bool,
    source_created_at Nullable(DateTime64(3, 'UTC')),
    source_updated_at Nullable(DateTime64(3, 'UTC')),
    source_seen_at DateTime64(3, 'UTC'),
    ingested_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(ingested_at)
ORDER BY source_product_id;

CREATE TABLE IF NOT EXISTS dwh_raw.storefront_catalog_variant
(
    batch_id UUID,
    source_variant_id String,
    source_product_id String,
    title String,
    sku String DEFAULT '',
    barcode String DEFAULT '',
    price_amount Nullable(Decimal(18, 2)),
    original_price_amount Nullable(Decimal(18, 2)),
    currency_code LowCardinality(String) DEFAULT 'KZT',
    active Bool,
    source_updated_at DateTime64(3, 'UTC'),
    ingested_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(ingested_at)
ORDER BY source_variant_id;

CREATE TABLE IF NOT EXISTS dwh_raw.storefront_catalog_category
(
    batch_id UUID,
    source_category_id String,
    parent_source_category_id String DEFAULT '',
    handle String DEFAULT '',
    category_name String,
    rank Int32 DEFAULT 0,
    active Bool,
    source_updated_at DateTime64(3, 'UTC'),
    ingested_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(ingested_at)
ORDER BY source_category_id;

CREATE TABLE IF NOT EXISTS dwh_raw.storefront_product_category
(
    batch_id UUID,
    source_product_id String,
    source_category_id String,
    is_primary Bool,
    captured_at DateTime64(3, 'UTC'),
    ingested_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(ingested_at)
PARTITION BY toYYYYMM(captured_at)
ORDER BY (source_product_id, source_category_id, captured_at);

CREATE TABLE IF NOT EXISTS dwh_raw.storefront_pharmacy
(
    batch_id UUID,
    source_pharmacy_id String,
    external_pharmacy_id String DEFAULT '',
    pharmacy_name String,
    city String DEFAULT '',
    address String DEFAULT '',
    latitude Nullable(Decimal(9, 6)),
    longitude Nullable(Decimal(9, 6)),
    active Bool,
    source_updated_at DateTime64(3, 'UTC'),
    ingested_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(ingested_at)
ORDER BY source_pharmacy_id;

CREATE TABLE IF NOT EXISTS dwh_raw.storefront_pharmacy_offer
(
    batch_id UUID,
    source_product_id String,
    source_variant_id String DEFAULT '',
    source_pharmacy_id String,
    price_amount Decimal(18, 2),
    currency_code LowCardinality(String),
    stock_quantity Nullable(Decimal(20, 4)),
    in_stock Bool,
    source_updated_at Nullable(DateTime64(3, 'UTC')),
    captured_at DateTime64(3, 'UTC'),
    ingested_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(ingested_at)
PARTITION BY toYYYYMM(captured_at)
ORDER BY (source_product_id, source_pharmacy_id, captured_at);

CREATE TABLE IF NOT EXISTS dwh_raw.storefront_warehouse_stock
(
    batch_id UUID,
    source_product_id String,
    source_variant_id String DEFAULT '',
    warehouse_code String,
    city String DEFAULT '',
    stock_quantity Decimal(20, 4),
    in_transit_quantity Decimal(20, 4),
    source_updated_at DateTime64(3, 'UTC'),
    captured_at DateTime64(3, 'UTC'),
    ingested_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(ingested_at)
PARTITION BY toYYYYMM(captured_at)
ORDER BY (source_product_id, warehouse_code, city, captured_at);

CREATE TABLE IF NOT EXISTS dwh_raw.storefront_site_order
(
    batch_id UUID,
    source_order_id String,
    commerce_source LowCardinality(String),
    source_commerce_order_id String,
    display_number UInt64,
    placed_at DateTime64(3, 'UTC'),
    source_updated_at DateTime64(3, 'UTC'),
    total_amount Decimal(20, 2),
    currency_code LowCardinality(String),
    order_status LowCardinality(String),
    status_version UInt64,
    item_count UInt32,
    delivery_method LowCardinality(String),
    fulfillment_type LowCardinality(String) DEFAULT '',
    fulfillment_status LowCardinality(String) DEFAULT '',
    fulfillment_version UInt64 DEFAULT 0,
    pharmacy_id String DEFAULT '',
    channel LowCardinality(String) DEFAULT 'web',
    payment_method LowCardinality(String) DEFAULT '',
    payment_status LowCardinality(String) DEFAULT '',
    pricing_state LowCardinality(String) DEFAULT '',
    city String DEFAULT '',
    promo_code String DEFAULT '',
    is_demo Bool DEFAULT false,
    customer_key_hash FixedString(64),
    ingested_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(ingested_at)
PARTITION BY toYYYYMM(placed_at)
ORDER BY source_order_id;

CREATE TABLE IF NOT EXISTS dwh_raw.storefront_site_order_item
(
    batch_id UUID,
    source_order_id String,
    line_number UInt32,
    source_product_id String DEFAULT '',
    source_variant_id String DEFAULT '',
    sku String DEFAULT '',
    title String DEFAULT '',
    quantity Decimal(20, 4),
    unit_price Nullable(Decimal(18, 2)),
    line_total Nullable(Decimal(20, 2)),
    placed_at DateTime64(3, 'UTC'),
    ingested_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(ingested_at)
PARTITION BY toYYYYMM(placed_at)
ORDER BY (source_order_id, line_number);

CREATE TABLE IF NOT EXISTS dwh_raw.storefront_customer_event
(
    batch_id UUID,
    source_event_id UUID,
    idempotency_key String,
    event_name LowCardinality(String),
    event_version UInt32,
    profile_key_hash FixedString(64),
    customer_key_hash FixedString(64),
    anonymous_id_hash String DEFAULT '',
    session_id_hash String DEFAULT '',
    event_source LowCardinality(String),
    occurred_at DateTime64(3, 'UTC'),
    received_at DateTime64(3, 'UTC'),
    source_product_id String DEFAULT '',
    source_variant_id String DEFAULT '',
    source_order_id String DEFAULT '',
    category_id String DEFAULT '',
    quantity Nullable(Decimal(20, 4)),
    amount Nullable(Decimal(20, 2)),
    currency_code LowCardinality(String) DEFAULT '',
    page_path String DEFAULT '',
    campaign String DEFAULT '',
    analytics_consent Bool DEFAULT false,
    ingested_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(ingested_at)
PARTITION BY toYYYYMM(occurred_at)
ORDER BY source_event_id;

CREATE TABLE IF NOT EXISTS dwh_raw.storefront_snapshot_manifest
(
    batch_id UUID,
    captured_at DateTime64(3, 'UTC'),
    offer_count UInt64,
    warehouse_stock_count UInt64,
    product_category_count UInt64,
    ingested_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(ingested_at)
ORDER BY batch_id;

ALTER TABLE dwh_core.fact_order_item
    ADD COLUMN IF NOT EXISTS is_price_missing Bool DEFAULT false;

CREATE TABLE IF NOT EXISTS dwh_core.dim_category
(
    category_key String,
    source_system LowCardinality(String),
    source_category_id String,
    parent_category_key Nullable(String),
    category_name String,
    handle String DEFAULT '',
    rank Int32 DEFAULT 0,
    is_active Bool,
    loaded_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(loaded_at)
ORDER BY category_key;

CREATE TABLE IF NOT EXISTS dwh_core.bridge_product_category_snapshot
(
    source_system LowCardinality(String),
    captured_at DateTime64(3, 'UTC'),
    snapshot_date Date,
    product_key String,
    category_key String,
    is_primary Bool,
    loaded_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(loaded_at)
PARTITION BY toYYYYMM(snapshot_date)
ORDER BY (snapshot_date, product_key, category_key, captured_at);

CREATE TABLE IF NOT EXISTS dwh_core.fact_storefront_offer_snapshot
(
    offer_snapshot_key String,
    source_system LowCardinality(String),
    captured_at DateTime64(3, 'UTC'),
    snapshot_date Date,
    source_updated_at Nullable(DateTime64(3, 'UTC')),
    product_key String,
    pharmacy_key String,
    source_product_id String,
    source_variant_id String DEFAULT '',
    source_pharmacy_id String,
    price_amount Decimal(18, 2),
    currency FixedString(3),
    stock_quantity Nullable(Decimal(20, 4)),
    in_stock Bool,
    loaded_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(loaded_at)
PARTITION BY toYYYYMM(snapshot_date)
ORDER BY (snapshot_date, offer_snapshot_key);

CREATE TABLE IF NOT EXISTS dwh_core.fact_storefront_order
(
    order_key String,
    source_system LowCardinality(String),
    order_date Date,
    commerce_source LowCardinality(String),
    customer_key_hash FixedString(64),
    delivery_method LowCardinality(String),
    fulfillment_type LowCardinality(String) DEFAULT '',
    fulfillment_status LowCardinality(String) DEFAULT '',
    status_version UInt64,
    fulfillment_version UInt64,
    payment_method LowCardinality(String) DEFAULT '',
    payment_status LowCardinality(String) DEFAULT '',
    pricing_state LowCardinality(String) DEFAULT '',
    city String DEFAULT '',
    promo_code String DEFAULT '',
    is_demo Bool,
    item_count UInt32,
    loaded_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(loaded_at)
PARTITION BY toYYYYMM(order_date)
ORDER BY order_key;

CREATE TABLE IF NOT EXISTS dwh_core.fact_storefront_warehouse_snapshot
(
    warehouse_snapshot_key String,
    source_system LowCardinality(String),
    captured_at DateTime64(3, 'UTC'),
    snapshot_date Date,
    source_updated_at DateTime64(3, 'UTC'),
    product_key String,
    warehouse_key String,
    source_product_id String,
    source_variant_id String DEFAULT '',
    stock_quantity Decimal(20, 4),
    in_transit_quantity Decimal(20, 4),
    loaded_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(loaded_at)
PARTITION BY toYYYYMM(snapshot_date)
ORDER BY (snapshot_date, warehouse_snapshot_key);

CREATE TABLE IF NOT EXISTS dwh_core.fact_storefront_event
(
    event_key String,
    source_system LowCardinality(String),
    event_name LowCardinality(String),
    event_version UInt32,
    event_source LowCardinality(String),
    occurred_at DateTime64(3, 'UTC'),
    event_date Date,
    profile_key_hash FixedString(64),
    customer_key_hash FixedString(64),
    anonymous_id_hash String DEFAULT '',
    session_id_hash String DEFAULT '',
    product_key Nullable(String),
    order_key Nullable(String),
    category_id Nullable(String),
    quantity Nullable(Decimal(20, 4)),
    amount Nullable(Decimal(20, 2)),
    currency Nullable(FixedString(3)),
    page_path String DEFAULT '',
    campaign String DEFAULT '',
    analytics_consent Bool,
    loaded_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(loaded_at)
PARTITION BY toYYYYMM(event_date)
ORDER BY (event_date, event_key);

CREATE TABLE IF NOT EXISTS dwh_core.storefront_snapshot_manifest
(
    batch_id UUID,
    source_system LowCardinality(String),
    captured_at DateTime64(3, 'UTC'),
    offer_count UInt64,
    warehouse_stock_count UInt64,
    product_category_count UInt64,
    loaded_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(loaded_at)
ORDER BY batch_id;
