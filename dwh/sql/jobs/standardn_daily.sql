INSERT INTO dwh_raw.standardn_pharmacy_daily
(
    snapshot_date, source_file, source_row, part_id, ware_id, sname,
    profile_id, caption, return_qty, goods_received, goods_transferred,
    transferred_out, adjustments, sales, retail_price, cost_price,
    stock_begin, stock_end, supplier, series, expiration_date, quant_opt,
    revenue, loaded_at, source_retail_price, source_cost_price, source_revenue
)
SELECT
    snapshot_date, source_file, source_row, part_id, ware_id, sname,
    profile_id, caption, return_qty, goods_received, goods_transferred,
    transferred_out, adjustments, sales, retail_price, cost_price,
    stock_begin, stock_end, supplier, series, toDate(expiration_date), quant_opt,
    revenue, loaded_at, source_retail_price, source_cost_price, source_revenue
FROM {{source_database}}.{{source_table}} FINAL
WHERE snapshot_date = toDate('{{snapshot_date}}');

INSERT INTO dwh_core.dim_product
(
    product_key, canonical_product_id, product_name,
    valid_from, valid_to, is_current
)
SELECT
    concat('ware:', lower(toString(ware_id))) AS product_key,
    toUUIDOrNull(ware_id) AS canonical_product_id,
    if(empty(argMax(sname, ingested_at)), argMax(caption, ingested_at), argMax(sname, ingested_at)) AS product_name,
    toDateTime64('1970-01-01 00:00:00', 3, 'UTC') AS valid_from,
    NULL AS valid_to,
    true AS is_current
FROM dwh_raw.standardn_pharmacy_daily FINAL
WHERE snapshot_date = toDate('{{snapshot_date}}')
  AND notEmpty(trim(ware_id))
GROUP BY ware_id;

INSERT INTO dwh_core.dim_product_mapping
(
    source_system, identifier_type, identifier_value, product_key,
    confidence, valid_from, valid_to, is_current
)
SELECT
    'standardn' AS source_system,
    'ware_id' AS identifier_type,
    lower(toString(ware_id)) AS identifier_value,
    concat('ware:', lower(toString(ware_id))) AS product_key,
    'confirmed' AS confidence,
    toDateTime64('1970-01-01 00:00:00', 3, 'UTC') AS valid_from,
    NULL AS valid_to,
    true AS is_current
FROM dwh_raw.standardn_pharmacy_daily FINAL
WHERE snapshot_date = toDate('{{snapshot_date}}')
  AND notEmpty(trim(ware_id))
GROUP BY ware_id;

INSERT INTO dwh_core.dim_pharmacy
(
    pharmacy_key, source_system, source_pharmacy_id, pharmacy_code,
    pharmacy_name, valid_from, valid_to, is_current
)
SELECT
    concat('standardn:', toString(profile_id)) AS pharmacy_key,
    'standardn' AS source_system,
    toString(profile_id) AS source_pharmacy_id,
    toString(profile_id) AS pharmacy_code,
    concat('Аптека Standard-N #', toString(profile_id)) AS pharmacy_name,
    toDateTime64('1970-01-01 00:00:00', 3, 'UTC') AS valid_from,
    NULL AS valid_to,
    true AS is_current
FROM dwh_raw.standardn_pharmacy_daily FINAL
WHERE snapshot_date = toDate('{{snapshot_date}}')
  AND profile_id > 0
GROUP BY profile_id;

INSERT INTO dwh_core.dim_pharmacy_mapping
(
    source_system, source_pharmacy_id, pharmacy_key,
    confidence, valid_from, valid_to, is_current
)
SELECT
    'standardn' AS source_system,
    toString(profile_id) AS source_pharmacy_id,
    concat('standardn:', toString(profile_id)) AS pharmacy_key,
    'inferred' AS confidence,
    toDateTime64('1970-01-01 00:00:00', 3, 'UTC') AS valid_from,
    NULL AS valid_to,
    true AS is_current
FROM dwh_raw.standardn_pharmacy_daily FINAL
WHERE snapshot_date = toDate('{{snapshot_date}}')
  AND profile_id > 0
GROUP BY profile_id;

INSERT INTO dwh_core.fact_stock_snapshot
(
    source_snapshot_id, source_system, snapshot_at, snapshot_date,
    snapshot_granularity, product_key, pharmacy_key,
    quantity_on_hand, quantity_reserved, quantity_available,
    retail_price, cost_price, row_hash
)
SELECT
    concat(toString(snapshot_date), ':', toString(profile_id), ':', lower(toString(ware_id))) AS source_snapshot_id,
    'standardn' AS source_system,
    toTimeZone(toDateTime64(concat(toString(snapshot_date), ' 23:59:59'), 3, 'Asia/Almaty'), 'UTC') AS snapshot_at,
    snapshot_date,
    'daily' AS snapshot_granularity,
    concat('ware:', lower(toString(ware_id))) AS product_key,
    concat('standardn:', toString(profile_id)) AS pharmacy_key,
    toDecimal64(sum(stock_end), 4) AS quantity_on_hand,
    toDecimal64(0, 4) AS quantity_reserved,
    toDecimal64(greatest(sum(stock_end), 0), 4) AS quantity_available,
    max(retail_price) AS retail_price,
    max(cost_price) AS cost_price,
    lower(hex(SHA256(concat(
        toString(snapshot_date), '|', toString(profile_id), '|',
        lower(toString(ware_id)), '|', toString(sum(stock_end)), '|',
        ifNull(toString(max(retail_price)), ''), '|', ifNull(toString(max(cost_price)), '')
    )))) AS row_hash
FROM dwh_raw.standardn_pharmacy_daily FINAL
WHERE snapshot_date = toDate('{{snapshot_date}}')
  AND profile_id > 0
  AND notEmpty(trim(ware_id))
GROUP BY snapshot_date, profile_id, ware_id;

INSERT INTO dwh_core.fact_sales_daily
(
    sales_daily_key, source_system, sale_date, product_key, pharmacy_key,
    sales_quantity, return_quantity, goods_received_quantity,
    goods_transferred_quantity, transferred_out_quantity,
    adjustment_quantity, revenue
)
SELECT
    concat(toString(snapshot_date), ':', toString(profile_id), ':', lower(toString(ware_id))) AS sales_daily_key,
    'standardn' AS source_system,
    snapshot_date AS sale_date,
    concat('ware:', lower(toString(ware_id))) AS product_key,
    concat('standardn:', toString(profile_id)) AS pharmacy_key,
    toDecimal64(sum(sales), 4) AS sales_quantity,
    toDecimal64(sum(return_qty), 4) AS return_quantity,
    toDecimal64(sum(goods_received), 4) AS goods_received_quantity,
    toDecimal64(sum(goods_transferred), 4) AS goods_transferred_quantity,
    toDecimal64(sum(transferred_out), 4) AS transferred_out_quantity,
    toDecimal64(sum(adjustments), 4) AS adjustment_quantity,
    toDecimal64(sum(revenue), 2) AS revenue
FROM dwh_raw.standardn_pharmacy_daily FINAL
WHERE snapshot_date = toDate('{{snapshot_date}}')
  AND profile_id > 0
  AND notEmpty(trim(ware_id))
GROUP BY snapshot_date, profile_id, ware_id;
