CREATE TABLE IF NOT EXISTS dwh_core.dim_branch
(
    branch_key String,
    source_system LowCardinality(String),
    source_branch_id String,
    branch_code String DEFAULT '',
    branch_name String,
    legal_entity_name String DEFAULT '',
    city String DEFAULT '',
    region String DEFAULT '',
    valid_from DateTime64(3, 'UTC'),
    valid_to Nullable(DateTime64(3, 'UTC')),
    is_current Bool,
    loaded_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(loaded_at)
ORDER BY (branch_key, valid_from);

CREATE TABLE IF NOT EXISTS dwh_core.dim_pharmacy
(
    pharmacy_key String,
    source_system LowCardinality(String),
    source_pharmacy_id String,
    branch_key Nullable(String),
    pharmacy_code String DEFAULT '',
    pharmacy_name String,
    address String DEFAULT '',
    city String DEFAULT '',
    region String DEFAULT '',
    timezone LowCardinality(String) DEFAULT 'Asia/Almaty',
    latitude Nullable(Decimal(9, 6)),
    longitude Nullable(Decimal(9, 6)),
    is_active Bool DEFAULT true,
    valid_from DateTime64(3, 'UTC'),
    valid_to Nullable(DateTime64(3, 'UTC')),
    is_current Bool,
    loaded_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(loaded_at)
ORDER BY (pharmacy_key, valid_from);

CREATE TABLE IF NOT EXISTS dwh_core.dim_pharmacy_mapping
(
    source_system LowCardinality(String),
    source_pharmacy_id String,
    pharmacy_key String,
    confidence Enum8('confirmed' = 1, 'inferred' = 2, 'manual' = 3),
    valid_from DateTime64(3, 'UTC'),
    valid_to Nullable(DateTime64(3, 'UTC')),
    is_current Bool,
    loaded_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(loaded_at)
ORDER BY (source_system, source_pharmacy_id, valid_from);

CREATE TABLE IF NOT EXISTS dwh_core.dim_product
(
    product_key String,
    canonical_product_id Nullable(UUID),
    product_name String,
    sku String DEFAULT '',
    material_code String DEFAULT '',
    barcode String DEFAULT '',
    manufacturer String DEFAULT '',
    brand String DEFAULT '',
    category String DEFAULT '',
    dosage_form String DEFAULT '',
    strength String DEFAULT '',
    package_size String DEFAULT '',
    is_active Bool DEFAULT true,
    valid_from DateTime64(3, 'UTC'),
    valid_to Nullable(DateTime64(3, 'UTC')),
    is_current Bool,
    loaded_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(loaded_at)
ORDER BY (product_key, valid_from);

CREATE TABLE IF NOT EXISTS dwh_core.dim_product_mapping
(
    source_system LowCardinality(String),
    identifier_type LowCardinality(String),
    identifier_value String,
    product_key String,
    confidence Enum8('confirmed' = 1, 'inferred' = 2, 'manual' = 3),
    valid_from DateTime64(3, 'UTC'),
    valid_to Nullable(DateTime64(3, 'UTC')),
    is_current Bool,
    loaded_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(loaded_at)
ORDER BY (source_system, identifier_type, identifier_value, valid_from);

CREATE TABLE IF NOT EXISTS dwh_core.dim_employee
(
    employee_key String,
    source_system LowCardinality(String),
    source_employee_id String,
    natural_key_hash FixedString(64),
    employee_name String,
    job_title String DEFAULT '',
    branch_key Nullable(String),
    pharmacy_key Nullable(String),
    employment_status LowCardinality(String) DEFAULT 'active',
    hired_at Nullable(Date),
    dismissed_at Nullable(Date),
    valid_from DateTime64(3, 'UTC'),
    valid_to Nullable(DateTime64(3, 'UTC')),
    is_current Bool,
    loaded_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(loaded_at)
ORDER BY (employee_key, valid_from);

CREATE TABLE IF NOT EXISTS dwh_core.dim_employee_pharmacy_assignment
(
    assignment_key String,
    employee_key String,
    pharmacy_key String,
    branch_key Nullable(String),
    assigned_from DateTime64(3, 'UTC'),
    assigned_to Nullable(DateTime64(3, 'UTC')),
    is_primary Bool DEFAULT true,
    loaded_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(loaded_at)
ORDER BY (employee_key, pharmacy_key, assigned_from);

CREATE TABLE IF NOT EXISTS dwh_core.dim_sales_channel
(
    sales_channel_key LowCardinality(String),
    sales_channel_name String,
    channel_group LowCardinality(String),
    loaded_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(loaded_at)
ORDER BY sales_channel_key;

CREATE TABLE IF NOT EXISTS dwh_core.dim_warehouse
(
    warehouse_key String,
    source_system LowCardinality(String),
    source_warehouse_id String,
    warehouse_name String,
    branch_key Nullable(String),
    pharmacy_key Nullable(String),
    warehouse_type LowCardinality(String) DEFAULT 'pharmacy',
    is_active Bool DEFAULT true,
    loaded_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(loaded_at)
ORDER BY warehouse_key;

CREATE TABLE IF NOT EXISTS dwh_core.dim_cash_register
(
    cash_register_key String,
    source_system LowCardinality(String),
    source_register_id String,
    pharmacy_key Nullable(String),
    register_name String DEFAULT '',
    is_active Bool DEFAULT true,
    loaded_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(loaded_at)
ORDER BY cash_register_key;

CREATE TABLE IF NOT EXISTS dwh_core.dim_bonus_rule
(
    bonus_rule_key String,
    source_system LowCardinality(String),
    source_rule_id String,
    rule_name String,
    calculation_type LowCardinality(String),
    rate Decimal(12, 4),
    valid_from DateTime64(3, 'UTC'),
    valid_to Nullable(DateTime64(3, 'UTC')),
    is_current Bool,
    loaded_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(loaded_at)
ORDER BY (bonus_rule_key, valid_from);

CREATE TABLE IF NOT EXISTS dwh_core.dim_training_program
(
    training_program_key String,
    source_system LowCardinality(String),
    source_program_id String,
    program_name String,
    program_format LowCardinality(String),
    version UInt32 DEFAULT 1,
    is_active Bool DEFAULT true,
    valid_from DateTime64(3, 'UTC'),
    valid_to Nullable(DateTime64(3, 'UTC')),
    is_current Bool,
    loaded_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(loaded_at)
ORDER BY (training_program_key, valid_from);

CREATE TABLE IF NOT EXISTS dwh_core.dim_training_course
(
    training_course_key String,
    source_system LowCardinality(String),
    source_course_id String,
    course_name String,
    course_type LowCardinality(String),
    estimated_minutes UInt32 DEFAULT 0,
    is_active Bool DEFAULT true,
    valid_from DateTime64(3, 'UTC'),
    valid_to Nullable(DateTime64(3, 'UTC')),
    is_current Bool,
    loaded_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(loaded_at)
ORDER BY (training_course_key, valid_from);

CREATE TABLE IF NOT EXISTS dwh_core.dim_date
(
    date_key Date,
    year UInt16,
    quarter UInt8,
    month UInt8,
    month_name LowCardinality(String),
    week UInt8,
    day UInt8,
    day_of_week UInt8,
    is_weekend Bool
)
ENGINE = MergeTree
ORDER BY date_key;
