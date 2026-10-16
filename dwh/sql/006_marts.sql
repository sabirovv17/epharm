CREATE OR REPLACE VIEW dwh_marts.current_stock AS
SELECT
    source_system,
    product_key,
    pharmacy_key,
    branch_key,
    warehouse_key,
    argMax(snapshot_at, tuple(snapshot_at, loaded_at)) AS snapshot_at,
    argMax(quantity_on_hand, tuple(snapshot_at, loaded_at)) AS quantity_on_hand,
    argMax(quantity_reserved, tuple(snapshot_at, loaded_at)) AS quantity_reserved,
    argMax(quantity_available, tuple(snapshot_at, loaded_at)) AS quantity_available,
    argMax(retail_price, tuple(snapshot_at, loaded_at)) AS retail_price,
    argMax(cost_price, tuple(snapshot_at, loaded_at)) AS cost_price,
    max(loaded_at) AS dwh_updated_at
FROM dwh_core.fact_stock_snapshot FINAL
GROUP BY source_system, product_key, pharmacy_key, branch_key, warehouse_key;

CREATE OR REPLACE VIEW dwh_marts.sales_by_day AS
SELECT
    sale_date,
    source_system,
    pharmacy_key,
    branch_key,
    product_key,
    employee_key,
    sales_channel_key,
    sum(if(is_return, -quantity, quantity)) AS net_quantity,
    sum(gross_amount) AS gross_amount,
    sum(discount_amount) AS discount_amount,
    sum(net_amount) AS net_amount,
    sum(cost_amount) AS cost_amount,
    sum(net_amount - ifNull(cost_amount, 0)) AS gross_margin,
    uniqExact(receipt_key) AS receipt_count
FROM dwh_core.fact_sales_item FINAL
GROUP BY
    sale_date, source_system, pharmacy_key, branch_key,
    product_key, employee_key, sales_channel_key;

CREATE OR REPLACE VIEW dwh_marts.standardn_sales_by_day AS
SELECT
    sale_date,
    source_system,
    pharmacy_key,
    product_key,
    sum(sales_quantity) AS sales_quantity,
    sum(return_quantity) AS return_quantity,
    sum(revenue) AS revenue,
    max(loaded_at) AS dwh_updated_at
FROM dwh_core.fact_sales_daily FINAL
GROUP BY sale_date, source_system, pharmacy_key, product_key;

CREATE OR REPLACE VIEW dwh_marts.orders_by_day AS
SELECT
    order_date,
    source_system,
    sales_channel_key,
    pharmacy_key,
    branch_key,
    order_status,
    count() AS order_count,
    sum(gross_amount) AS gross_amount,
    sum(discount_amount) AS discount_amount,
    sum(net_amount) AS net_amount
FROM dwh_core.fact_order FINAL
GROUP BY
    order_date, source_system, sales_channel_key,
    pharmacy_key, branch_key, order_status;

CREATE OR REPLACE VIEW dwh_marts.pharmacist_bonus AS
SELECT
    accrual_date,
    source_system,
    employee_key,
    pharmacy_key,
    branch_key,
    accrual_status,
    count() AS accrual_count,
    sum(base_amount) AS base_amount,
    sum(bonus_amount) AS bonus_amount
FROM dwh_core.fact_bonus_accrual FINAL
GROUP BY
    accrual_date, source_system, employee_key,
    pharmacy_key, branch_key, accrual_status;

CREATE OR REPLACE VIEW dwh_marts.data_freshness AS
SELECT
    source_system,
    job_name,
    argMax(status, updated_at) AS last_status,
    max(started_at) AS last_started_at,
    max(finished_at) AS last_finished_at,
    argMax(rows_written, updated_at) AS last_rows_written,
    argMax(error_message, updated_at) AS last_error
FROM dwh_meta.etl_job_run FINAL
GROUP BY source_system, job_name;

CREATE OR REPLACE VIEW dwh_marts.training_completion AS
SELECT
    toDate(assigned_at) AS assignment_date,
    source_system,
    training_program_key,
    employee_key,
    pharmacy_key,
    branch_key,
    assignment_status,
    count() AS assignment_count,
    countIf(completed_at IS NOT NULL) AS completed_count,
    avg(progress_percent) AS average_progress_percent,
    avgIf(dateDiff('hour', assigned_at, completed_at), completed_at IS NOT NULL) AS average_hours_to_complete
FROM dwh_core.fact_training_assignment FINAL
GROUP BY
    assignment_date, source_system, training_program_key,
    employee_key, pharmacy_key, branch_key, assignment_status;
