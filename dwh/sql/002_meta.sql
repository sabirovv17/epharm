CREATE TABLE IF NOT EXISTS dwh_meta.schema_migration
(
    filename LowCardinality(String),
    sha256 FixedString(64),
    applied_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(applied_at)
ORDER BY filename;

CREATE TABLE IF NOT EXISTS dwh_meta.source_system
(
    source_system LowCardinality(String),
    description String,
    owner String DEFAULT '',
    is_active Bool DEFAULT true,
    updated_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(updated_at)
ORDER BY source_system;

CREATE TABLE IF NOT EXISTS dwh_meta.etl_job_run
(
    job_run_id UUID,
    job_name LowCardinality(String),
    source_system LowCardinality(String),
    window_start Nullable(DateTime64(3, 'UTC')),
    window_end Nullable(DateTime64(3, 'UTC')),
    started_at DateTime64(3, 'UTC'),
    finished_at Nullable(DateTime64(3, 'UTC')),
    status Enum8('running' = 1, 'succeeded' = 2, 'failed' = 3),
    rows_read UInt64 DEFAULT 0,
    rows_written UInt64 DEFAULT 0,
    rows_rejected UInt64 DEFAULT 0,
    error_message String DEFAULT '',
    updated_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(updated_at)
PARTITION BY toYYYYMM(started_at)
ORDER BY (job_name, job_run_id);

CREATE TABLE IF NOT EXISTS dwh_meta.ingestion_checkpoint
(
    source_system LowCardinality(String),
    job_name LowCardinality(String),
    cursor_value String DEFAULT '',
    watermark Nullable(DateTime64(3, 'UTC')),
    updated_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(updated_at)
ORDER BY (source_system, job_name);

CREATE TABLE IF NOT EXISTS dwh_meta.data_quality_check
(
    check_id UUID DEFAULT generateUUIDv4(),
    job_run_id Nullable(UUID),
    check_name LowCardinality(String),
    entity LowCardinality(String),
    checked_at DateTime64(3, 'UTC') DEFAULT now64(3),
    status Enum8('passed' = 1, 'warning' = 2, 'failed' = 3),
    failed_count UInt64,
    threshold UInt64 DEFAULT 0,
    details String DEFAULT ''
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(checked_at)
ORDER BY (checked_at, check_name, check_id);

CREATE TABLE IF NOT EXISTS dwh_meta.rejected_record
(
    rejected_at DateTime64(3, 'UTC') DEFAULT now64(3),
    job_run_id Nullable(UUID),
    source_system LowCardinality(String),
    source_entity LowCardinality(String),
    source_record_id String DEFAULT '',
    reason_code LowCardinality(String),
    reason String,
    payload String
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(rejected_at)
ORDER BY (rejected_at, source_system, source_entity);
