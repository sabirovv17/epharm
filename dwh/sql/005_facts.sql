CREATE TABLE IF NOT EXISTS dwh_core.fact_stock_snapshot
(
    source_snapshot_id String,
    source_system LowCardinality(String),
    snapshot_at DateTime64(3, 'UTC'),
    snapshot_date Date,
    snapshot_granularity LowCardinality(String),
    product_key String,
    pharmacy_key Nullable(String),
    branch_key Nullable(String),
    warehouse_key Nullable(String),
    batch_number String DEFAULT '',
    series String DEFAULT '',
    expiration_date Nullable(Date),
    quantity_on_hand Decimal(20, 4),
    quantity_reserved Decimal(20, 4) DEFAULT 0,
    quantity_available Decimal(20, 4),
    retail_price Nullable(Decimal(18, 2)),
    cost_price Nullable(Decimal(18, 2)),
    row_hash FixedString(64),
    loaded_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(loaded_at)
PARTITION BY toYYYYMM(snapshot_date)
ORDER BY (snapshot_date, source_system, source_snapshot_id);

CREATE TABLE IF NOT EXISTS dwh_core.fact_stock_movement
(
    source_movement_id String,
    source_system LowCardinality(String),
    occurred_at DateTime64(3, 'UTC'),
    movement_date Date,
    movement_type LowCardinality(String),
    product_key String,
    pharmacy_key Nullable(String),
    branch_key Nullable(String),
    warehouse_key Nullable(String),
    quantity Decimal(20, 4),
    unit_cost Nullable(Decimal(18, 2)),
    amount Nullable(Decimal(20, 2)),
    related_document_id String DEFAULT '',
    loaded_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(loaded_at)
PARTITION BY toYYYYMM(movement_date)
ORDER BY (movement_date, source_system, source_movement_id);

CREATE TABLE IF NOT EXISTS dwh_core.fact_sales_receipt
(
    receipt_key String,
    source_system LowCardinality(String),
    source_receipt_id String,
    receipt_number String DEFAULT '',
    sold_at DateTime64(3, 'UTC'),
    sale_date Date,
    pharmacy_key String,
    branch_key Nullable(String),
    employee_key Nullable(String),
    cash_register_key Nullable(String),
    sales_channel_key LowCardinality(String),
    receipt_status LowCardinality(String),
    gross_amount Decimal(20, 2),
    discount_amount Decimal(20, 2) DEFAULT 0,
    net_amount Decimal(20, 2),
    paid_amount Decimal(20, 2),
    currency FixedString(3) DEFAULT 'KZT',
    loaded_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(loaded_at)
PARTITION BY toYYYYMM(sale_date)
ORDER BY (sale_date, source_system, receipt_key);

CREATE TABLE IF NOT EXISTS dwh_core.fact_sales_item
(
    sales_item_key String,
    source_system LowCardinality(String),
    source_sales_item_id String,
    receipt_key String,
    sale_date Date,
    sold_at DateTime64(3, 'UTC'),
    product_key String,
    pharmacy_key String,
    branch_key Nullable(String),
    employee_key Nullable(String),
    sales_channel_key LowCardinality(String),
    quantity Decimal(20, 4),
    unit_price Decimal(18, 2),
    gross_amount Decimal(20, 2),
    discount_amount Decimal(20, 2) DEFAULT 0,
    net_amount Decimal(20, 2),
    cost_amount Nullable(Decimal(20, 2)),
    is_return Bool DEFAULT false,
    loaded_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(loaded_at)
PARTITION BY toYYYYMM(sale_date)
ORDER BY (sale_date, source_system, sales_item_key);

-- Temporary aggregate-grain bridge for the existing Standard-N daily feed.
-- It must not be joined to receipt-level facts without filtering source_grain.
CREATE TABLE IF NOT EXISTS dwh_core.fact_sales_daily
(
    sales_daily_key String,
    source_system LowCardinality(String),
    sale_date Date,
    product_key String,
    pharmacy_key String,
    sales_quantity Decimal(20, 4),
    return_quantity Decimal(20, 4),
    goods_received_quantity Decimal(20, 4),
    goods_transferred_quantity Decimal(20, 4),
    transferred_out_quantity Decimal(20, 4),
    adjustment_quantity Decimal(20, 4),
    revenue Decimal(20, 2),
    loaded_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(loaded_at)
PARTITION BY toYYYYMM(sale_date)
ORDER BY (sale_date, source_system, sales_daily_key);

CREATE TABLE IF NOT EXISTS dwh_core.fact_order
(
    order_key String,
    source_system LowCardinality(String),
    source_order_id String,
    order_number String DEFAULT '',
    created_at DateTime64(3, 'UTC'),
    order_date Date,
    updated_at Nullable(DateTime64(3, 'UTC')),
    pharmacy_key Nullable(String),
    branch_key Nullable(String),
    employee_key Nullable(String),
    sales_channel_key LowCardinality(String),
    order_status LowCardinality(String),
    gross_amount Decimal(20, 2),
    discount_amount Decimal(20, 2) DEFAULT 0,
    net_amount Decimal(20, 2),
    currency FixedString(3) DEFAULT 'KZT',
    loaded_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(loaded_at)
PARTITION BY toYYYYMM(order_date)
ORDER BY (order_date, source_system, order_key);

CREATE TABLE IF NOT EXISTS dwh_core.fact_order_item
(
    order_item_key String,
    source_system LowCardinality(String),
    source_order_item_id String,
    order_key String,
    order_date Date,
    product_key String,
    pharmacy_key Nullable(String),
    sales_channel_key LowCardinality(String),
    quantity Decimal(20, 4),
    fulfilled_quantity Decimal(20, 4) DEFAULT 0,
    unit_price Decimal(18, 2),
    gross_amount Decimal(20, 2),
    discount_amount Decimal(20, 2) DEFAULT 0,
    net_amount Decimal(20, 2),
    loaded_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(loaded_at)
PARTITION BY toYYYYMM(order_date)
ORDER BY (order_date, source_system, order_item_key);

CREATE TABLE IF NOT EXISTS dwh_core.fact_order_status_event
(
    status_event_key String,
    source_system LowCardinality(String),
    order_key String,
    occurred_at DateTime64(3, 'UTC'),
    previous_status LowCardinality(String) DEFAULT '',
    new_status LowCardinality(String),
    employee_key Nullable(String),
    loaded_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(loaded_at)
PARTITION BY toYYYYMM(occurred_at)
ORDER BY (source_system, order_key, occurred_at, status_event_key);

CREATE TABLE IF NOT EXISTS dwh_core.fact_bonus_accrual
(
    bonus_accrual_key String,
    source_system LowCardinality(String),
    source_accrual_id String,
    accrued_at DateTime64(3, 'UTC'),
    accrual_date Date,
    employee_key String,
    pharmacy_key Nullable(String),
    branch_key Nullable(String),
    sales_item_key Nullable(String),
    bonus_rule_key Nullable(String),
    base_amount Decimal(20, 2),
    bonus_amount Decimal(20, 2),
    accrual_status LowCardinality(String),
    loaded_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(loaded_at)
PARTITION BY toYYYYMM(accrual_date)
ORDER BY (accrual_date, source_system, bonus_accrual_key);

CREATE TABLE IF NOT EXISTS dwh_core.fact_merch_task
(
    merch_task_key String,
    source_system LowCardinality(String),
    source_task_id String,
    assigned_at DateTime64(3, 'UTC'),
    due_at Nullable(DateTime64(3, 'UTC')),
    completed_at Nullable(DateTime64(3, 'UTC')),
    pharmacy_key String,
    branch_key Nullable(String),
    employee_key Nullable(String),
    task_type LowCardinality(String),
    task_status LowCardinality(String),
    loaded_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(loaded_at)
PARTITION BY toYYYYMM(assigned_at)
ORDER BY (source_system, merch_task_key);

CREATE TABLE IF NOT EXISTS dwh_core.fact_merch_report
(
    merch_report_key String,
    source_system LowCardinality(String),
    source_report_id String,
    merch_task_key String,
    submitted_at DateTime64(3, 'UTC'),
    employee_key Nullable(String),
    report_status LowCardinality(String),
    photo_object_key String DEFAULT '',
    comment String DEFAULT '',
    loaded_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(loaded_at)
PARTITION BY toYYYYMM(submitted_at)
ORDER BY (source_system, merch_report_key);

CREATE TABLE IF NOT EXISTS dwh_core.fact_training_assignment
(
    training_assignment_key String,
    source_system LowCardinality(String),
    source_assignment_id String,
    assigned_at DateTime64(3, 'UTC'),
    due_at Nullable(DateTime64(3, 'UTC')),
    started_at Nullable(DateTime64(3, 'UTC')),
    completed_at Nullable(DateTime64(3, 'UTC')),
    employee_key String,
    pharmacy_key Nullable(String),
    branch_key Nullable(String),
    training_program_key String,
    assignment_status LowCardinality(String),
    progress_percent Decimal(5, 2) DEFAULT 0,
    loaded_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(loaded_at)
PARTITION BY toYYYYMM(assigned_at)
ORDER BY (source_system, training_assignment_key);

CREATE TABLE IF NOT EXISTS dwh_core.fact_training_progress
(
    training_progress_key String,
    source_system LowCardinality(String),
    source_progress_id String,
    occurred_at DateTime64(3, 'UTC'),
    progress_date Date,
    training_assignment_key String,
    training_course_key String,
    employee_key String,
    stage_type LowCardinality(String),
    stage_status LowCardinality(String),
    progress_percent Decimal(5, 2),
    duration_seconds UInt64 DEFAULT 0,
    loaded_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(loaded_at)
PARTITION BY toYYYYMM(progress_date)
ORDER BY (progress_date, source_system, training_progress_key);

CREATE TABLE IF NOT EXISTS dwh_core.fact_training_result
(
    training_result_key String,
    source_system LowCardinality(String),
    source_result_id String,
    completed_at DateTime64(3, 'UTC'),
    result_date Date,
    training_assignment_key String,
    training_course_key String,
    employee_key String,
    attempt_number UInt32,
    score Nullable(Decimal(7, 2)),
    passed Nullable(Bool),
    certificate_key Nullable(String),
    loaded_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(loaded_at)
PARTITION BY toYYYYMM(result_date)
ORDER BY (result_date, source_system, training_result_key);
