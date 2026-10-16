CREATE OR REPLACE VIEW dwh_marts.storefront_availability AS
SELECT
    product_key,
    pharmacy_key,
    price_amount AS current_price,
    currency,
    stock_quantity,
    in_stock,
    captured_at AS snapshot_at
FROM dwh_core.fact_storefront_offer_snapshot FINAL
WHERE captured_at =
(
    SELECT max(captured_at)
    FROM dwh_core.storefront_snapshot_manifest FINAL
);

CREATE OR REPLACE VIEW dwh_marts.storefront_warehouse_availability AS
SELECT
    product_key,
    warehouse_key,
    stock_quantity,
    in_transit_quantity,
    captured_at AS snapshot_at
FROM dwh_core.fact_storefront_warehouse_snapshot FINAL
WHERE captured_at =
(
    SELECT max(captured_at)
    FROM dwh_core.storefront_snapshot_manifest FINAL
);

CREATE OR REPLACE VIEW dwh_marts.storefront_product_category AS
SELECT
    product_key,
    category_key,
    is_primary,
    captured_at AS snapshot_at
FROM dwh_core.bridge_product_category_snapshot FINAL
WHERE captured_at =
(
    SELECT max(captured_at)
    FROM dwh_core.storefront_snapshot_manifest FINAL
);

CREATE OR REPLACE VIEW dwh_marts.storefront_orders_by_day AS
SELECT
    o.order_date,
    o.sales_channel_key,
    o.order_status,
    o.pharmacy_key,
    s.delivery_method,
    s.fulfillment_type,
    s.fulfillment_status,
    s.payment_method,
    s.payment_status,
    s.is_demo,
    count() AS order_count,
    uniqExactIf(o.order_key, o.order_status NOT IN ('cancelled', 'canceled', 'Отменён')) AS non_cancelled_orders,
    sum(o.net_amount) AS revenue,
    avg(o.net_amount) AS average_order_value
FROM dwh_core.fact_order FINAL AS o
INNER JOIN dwh_core.fact_storefront_order FINAL AS s ON s.order_key = o.order_key
WHERE o.source_system = 'storefront'
GROUP BY
    o.order_date, o.sales_channel_key, o.order_status, o.pharmacy_key,
    s.delivery_method, s.fulfillment_type, s.fulfillment_status,
    s.payment_method, s.payment_status, s.is_demo;

CREATE OR REPLACE VIEW dwh_marts.storefront_product_sales AS
SELECT
    order_date,
    product_key,
    pharmacy_key,
    sales_channel_key,
    sum(quantity) AS ordered_quantity,
    sum(net_amount) AS ordered_revenue,
    uniqExact(order_key) AS order_count,
    countIf(is_price_missing) AS lines_without_price
FROM dwh_core.fact_order_item FINAL
WHERE source_system = 'storefront'
GROUP BY order_date, product_key, pharmacy_key, sales_channel_key;

CREATE OR REPLACE VIEW dwh_marts.storefront_funnel_by_day AS
SELECT
    event_date,
    event_source,
    event_name,
    campaign,
    count() AS event_count,
    uniqExactIf(session_id_hash, notEmpty(session_id_hash)) AS sessions,
    uniqExactIf(customer_key_hash, customer_key_hash != repeat('0', 64)) AS known_customers,
    sum(ifNull(amount, 0)) AS event_amount
FROM dwh_core.fact_storefront_event FINAL
WHERE analytics_consent
GROUP BY event_date, event_source, event_name, campaign;
