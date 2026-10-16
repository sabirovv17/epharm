INSERT INTO dwh_core.dim_product
(
    product_key, canonical_product_id, product_name, manufacturer, brand,
    category, valid_from, valid_to, is_current
)
SELECT
    if(notEmpty(ware_id) AND notEmpty(mapping_source), concat('ware:', lower(ware_id)), concat('storefront:', source_product_id)) AS product_key,
    if(notEmpty(mapping_source), toUUIDOrNull(ware_id), NULL) AS canonical_product_id,
    title AS product_name,
    manufacturer,
    brand,
    if(notEmpty(atc), atc, rx_otc) AS category,
    ifNull(source_created_at, toDateTime64('1970-01-01 00:00:00', 3, 'UTC')) AS valid_from,
    NULL AS valid_to,
    active AS is_current
FROM dwh_raw.storefront_catalog_product FINAL
WHERE batch_id = toUUID('{{batch_id}}');

INSERT INTO dwh_core.dim_product_mapping
(
    source_system, identifier_type, identifier_value, product_key,
    confidence, valid_from, valid_to, is_current
)
SELECT
    'storefront',
    'storefront_product_id',
    source_product_id,
    if(notEmpty(ware_id) AND notEmpty(mapping_source), concat('ware:', lower(ware_id)), concat('storefront:', source_product_id)),
    if(notEmpty(ware_id) AND notEmpty(mapping_source), 'confirmed', 'inferred'),
    toDateTime64('1970-01-01 00:00:00', 3, 'UTC'),
    NULL,
    active
FROM dwh_raw.storefront_catalog_product FINAL
WHERE batch_id = toUUID('{{batch_id}}');

INSERT INTO dwh_core.dim_product_mapping
(
    source_system, identifier_type, identifier_value, product_key,
    confidence, valid_from, valid_to, is_current
)
SELECT
    'storefront',
    'storefront_variant_id',
    v.source_variant_id,
    if(notEmpty(p.ware_id) AND notEmpty(p.mapping_source), concat('ware:', lower(p.ware_id)), concat('storefront:', v.source_product_id)),
    if(notEmpty(p.ware_id) AND notEmpty(p.mapping_source), 'confirmed', 'inferred'),
    toDateTime64('1970-01-01 00:00:00', 3, 'UTC'),
    NULL,
    v.active
FROM dwh_raw.storefront_catalog_variant FINAL AS v
LEFT JOIN dwh_raw.storefront_catalog_product FINAL AS p
    ON p.source_product_id = v.source_product_id
WHERE v.batch_id = toUUID('{{batch_id}}');

INSERT INTO dwh_core.dim_product_mapping
(
    source_system, identifier_type, identifier_value, product_key,
    confidence, valid_from, valid_to, is_current
)
SELECT
    'storefront',
    'sku',
    lower(v.sku),
    if(notEmpty(p.ware_id) AND notEmpty(p.mapping_source), concat('ware:', lower(p.ware_id)), concat('storefront:', v.source_product_id)),
    'inferred',
    toDateTime64('1970-01-01 00:00:00', 3, 'UTC'),
    NULL,
    v.active
FROM dwh_raw.storefront_catalog_variant FINAL AS v
LEFT JOIN dwh_raw.storefront_catalog_product FINAL AS p
    ON p.source_product_id = v.source_product_id
WHERE v.batch_id = toUUID('{{batch_id}}')
  AND notEmpty(trim(v.sku));

INSERT INTO dwh_core.dim_product_mapping
(
    source_system, identifier_type, identifier_value, product_key,
    confidence, valid_from, valid_to, is_current
)
SELECT
    'storefront',
    'barcode',
    v.barcode,
    if(notEmpty(p.ware_id) AND notEmpty(p.mapping_source), concat('ware:', lower(p.ware_id)), concat('storefront:', v.source_product_id)),
    'inferred',
    toDateTime64('1970-01-01 00:00:00', 3, 'UTC'),
    NULL,
    v.active
FROM dwh_raw.storefront_catalog_variant FINAL AS v
LEFT JOIN dwh_raw.storefront_catalog_product FINAL AS p
    ON p.source_product_id = v.source_product_id
WHERE v.batch_id = toUUID('{{batch_id}}')
  AND notEmpty(trim(v.barcode));

INSERT INTO dwh_core.dim_category
(
    category_key, source_system, source_category_id, parent_category_key,
    category_name, handle, rank, is_active
)
SELECT
    concat('storefront-category:', source_category_id),
    'storefront',
    source_category_id,
    if(
        notEmpty(parent_source_category_id),
        concat('storefront-category:', parent_source_category_id),
        NULL
    ),
    category_name,
    handle,
    rank,
    active
FROM dwh_raw.storefront_catalog_category FINAL
WHERE batch_id = toUUID('{{batch_id}}');

INSERT INTO dwh_core.bridge_product_category_snapshot
(
    source_system, captured_at, snapshot_date,
    product_key, category_key, is_primary
)
SELECT
    'storefront',
    pc.captured_at,
    toDate(pc.captured_at),
    if(notEmpty(pm.product_key), pm.product_key, concat('storefront:', pc.source_product_id)),
    concat('storefront-category:', pc.source_category_id),
    pc.is_primary
FROM dwh_raw.storefront_product_category AS pc
LEFT JOIN
(
    SELECT identifier_value, argMax(product_key, loaded_at) AS product_key
    FROM dwh_core.dim_product_mapping FINAL
    WHERE source_system = 'storefront' AND identifier_type = 'storefront_product_id' AND is_current
    GROUP BY identifier_value
) AS pm ON pm.identifier_value = pc.source_product_id
WHERE pc.batch_id = toUUID('{{batch_id}}');

INSERT INTO dwh_core.dim_pharmacy
(
    pharmacy_key, source_system, source_pharmacy_id, pharmacy_code,
    pharmacy_name, address, city, latitude, longitude,
    is_active, valid_from, valid_to, is_current
)
SELECT
    if(
        notEmpty(external_pharmacy_id),
        concat('standardn:', external_pharmacy_id),
        concat('storefront-pharmacy:', source_pharmacy_id)
    ) AS pharmacy_key,
    'storefront',
    source_pharmacy_id,
    external_pharmacy_id,
    pharmacy_name,
    address,
    city,
    latitude,
    longitude,
    active,
    toDateTime64('1970-01-01 00:00:00', 3, 'UTC'),
    NULL,
    active
FROM dwh_raw.storefront_pharmacy FINAL
WHERE batch_id = toUUID('{{batch_id}}');

INSERT INTO dwh_core.dim_pharmacy_mapping
(
    source_system, source_pharmacy_id, pharmacy_key,
    confidence, valid_from, valid_to, is_current
)
SELECT
    'storefront',
    source_pharmacy_id,
    if(
        notEmpty(external_pharmacy_id),
        concat('standardn:', external_pharmacy_id),
        concat('storefront-pharmacy:', source_pharmacy_id)
    ),
    if(notEmpty(external_pharmacy_id), 'confirmed', 'inferred'),
    toDateTime64('1970-01-01 00:00:00', 3, 'UTC'),
    NULL,
    active
FROM dwh_raw.storefront_pharmacy FINAL
WHERE batch_id = toUUID('{{batch_id}}');

INSERT INTO dwh_core.dim_warehouse
(
    warehouse_key, source_system, source_warehouse_id,
    warehouse_name, warehouse_type, is_active
)
SELECT
    concat('storefront-warehouse:', lower(warehouse_code), ':', lower(city)) AS warehouse_key,
    'storefront',
    concat(warehouse_code, ':', city),
    if(notEmpty(city), concat(warehouse_code, ' / ', city), warehouse_code),
    'distribution',
    true
FROM dwh_raw.storefront_warehouse_stock
WHERE batch_id = toUUID('{{batch_id}}')
GROUP BY warehouse_code, city;

INSERT INTO dwh_core.fact_storefront_offer_snapshot
(
    offer_snapshot_key, source_system, captured_at, snapshot_date,
    source_updated_at, product_key, pharmacy_key,
    source_product_id, source_variant_id, source_pharmacy_id,
    price_amount, currency, stock_quantity, in_stock
)
SELECT
    concat(o.source_product_id, ':', o.source_pharmacy_id, ':', toString(ifNull(o.source_updated_at, o.captured_at))),
    'storefront',
    o.captured_at,
    toDate(o.captured_at),
    o.source_updated_at,
    if(notEmpty(pm.product_key), pm.product_key, concat('storefront:', o.source_product_id)),
    if(notEmpty(phm.pharmacy_key), phm.pharmacy_key, concat('storefront-pharmacy:', o.source_pharmacy_id)),
    o.source_product_id,
    o.source_variant_id,
    o.source_pharmacy_id,
    o.price_amount,
    upper(o.currency_code),
    o.stock_quantity,
    o.in_stock
FROM dwh_raw.storefront_pharmacy_offer AS o
LEFT JOIN
(
    SELECT identifier_value, argMax(product_key, loaded_at) AS product_key
    FROM dwh_core.dim_product_mapping FINAL
    WHERE source_system = 'storefront' AND identifier_type = 'storefront_product_id' AND is_current
    GROUP BY identifier_value
) AS pm ON pm.identifier_value = o.source_product_id
LEFT JOIN
(
    SELECT source_pharmacy_id, argMax(pharmacy_key, loaded_at) AS pharmacy_key
    FROM dwh_core.dim_pharmacy_mapping FINAL
    WHERE source_system = 'storefront' AND is_current
    GROUP BY source_pharmacy_id
) AS phm ON phm.source_pharmacy_id = o.source_pharmacy_id
WHERE o.batch_id = toUUID('{{batch_id}}');

INSERT INTO dwh_core.fact_storefront_warehouse_snapshot
(
    warehouse_snapshot_key, source_system, captured_at, snapshot_date,
    source_updated_at, product_key, warehouse_key, source_product_id,
    source_variant_id, stock_quantity, in_transit_quantity
)
SELECT
    concat(w.source_product_id, ':', w.warehouse_code, ':', w.city, ':', toString(w.source_updated_at)),
    'storefront',
    w.captured_at,
    toDate(w.captured_at),
    w.source_updated_at,
    if(notEmpty(pm.product_key), pm.product_key, concat('storefront:', w.source_product_id)),
    concat('storefront-warehouse:', lower(w.warehouse_code), ':', lower(w.city)),
    w.source_product_id,
    w.source_variant_id,
    w.stock_quantity,
    w.in_transit_quantity
FROM dwh_raw.storefront_warehouse_stock AS w
LEFT JOIN
(
    SELECT identifier_value, argMax(product_key, loaded_at) AS product_key
    FROM dwh_core.dim_product_mapping FINAL
    WHERE source_system = 'storefront' AND identifier_type = 'storefront_product_id' AND is_current
    GROUP BY identifier_value
) AS pm ON pm.identifier_value = w.source_product_id
WHERE w.batch_id = toUUID('{{batch_id}}');

INSERT INTO dwh_core.fact_order
(
    order_key, source_system, source_order_id, order_number,
    created_at, order_date, updated_at, pharmacy_key,
    sales_channel_key, order_status, gross_amount,
    discount_amount, net_amount, currency
)
SELECT
    concat('storefront:', o.source_order_id),
    'storefront',
    o.source_order_id,
    toString(o.display_number),
    o.placed_at,
    toDate(o.placed_at),
    o.source_updated_at,
    nullIf(
        if(
            notEmpty(phm.pharmacy_key),
            phm.pharmacy_key,
            if(notEmpty(o.pharmacy_id), concat('storefront-pharmacy:', o.pharmacy_id), '')
        ),
        ''
    ),
    if(lower(o.channel) = 'mobile', 'mobile_app', 'storefront_web'),
    if(notEmpty(o.fulfillment_status), o.fulfillment_status, o.order_status),
    o.total_amount,
    toDecimal64(0, 2),
    o.total_amount,
    upper(o.currency_code)
FROM dwh_raw.storefront_site_order FINAL AS o
LEFT JOIN
(
    SELECT source_pharmacy_id, argMax(pharmacy_key, loaded_at) AS pharmacy_key
    FROM dwh_core.dim_pharmacy_mapping FINAL
    WHERE source_system = 'storefront' AND is_current
    GROUP BY source_pharmacy_id
) AS phm ON phm.source_pharmacy_id = o.pharmacy_id
WHERE o.batch_id = toUUID('{{batch_id}}');

INSERT INTO dwh_core.fact_storefront_order
(
    order_key, source_system, order_date, commerce_source,
    customer_key_hash, delivery_method, fulfillment_type,
    fulfillment_status, status_version, fulfillment_version,
    payment_method, payment_status, pricing_state, city,
    promo_code, is_demo, item_count
)
SELECT
    concat('storefront:', source_order_id),
    'storefront',
    toDate(placed_at),
    commerce_source,
    customer_key_hash,
    delivery_method,
    fulfillment_type,
    fulfillment_status,
    status_version,
    fulfillment_version,
    payment_method,
    payment_status,
    pricing_state,
    city,
    promo_code,
    is_demo,
    item_count
FROM dwh_raw.storefront_site_order FINAL
WHERE batch_id = toUUID('{{batch_id}}');

INSERT INTO dwh_core.fact_order_item
(
    order_item_key, source_system, source_order_item_id, order_key,
    order_date, product_key, pharmacy_key, sales_channel_key,
    quantity, unit_price, gross_amount, discount_amount, net_amount,
    is_price_missing
)
SELECT
    concat('storefront:', i.source_order_id, ':', toString(i.line_number)),
    'storefront',
    concat(i.source_order_id, ':', toString(i.line_number)),
    concat('storefront:', i.source_order_id),
    toDate(i.placed_at),
    if(notEmpty(pm.product_key), pm.product_key, concat('storefront:', i.source_product_id)),
    nullIf(
        if(
            notEmpty(phm.pharmacy_key),
            phm.pharmacy_key,
            if(notEmpty(o.pharmacy_id), concat('storefront-pharmacy:', o.pharmacy_id), '')
        ),
        ''
    ),
    if(lower(o.channel) = 'mobile', 'mobile_app', 'storefront_web'),
    i.quantity,
    ifNull(i.unit_price, toDecimal64(0, 2)),
    ifNull(i.line_total, i.quantity * ifNull(i.unit_price, toDecimal64(0, 2))),
    toDecimal64(0, 2),
    ifNull(i.line_total, i.quantity * ifNull(i.unit_price, toDecimal64(0, 2))),
    i.unit_price IS NULL
FROM dwh_raw.storefront_site_order_item FINAL AS i
LEFT JOIN dwh_raw.storefront_site_order FINAL AS o
    ON o.source_order_id = i.source_order_id
LEFT JOIN
(
    SELECT identifier_value, argMax(product_key, loaded_at) AS product_key
    FROM dwh_core.dim_product_mapping FINAL
    WHERE source_system = 'storefront' AND identifier_type = 'storefront_product_id' AND is_current
    GROUP BY identifier_value
) AS pm ON pm.identifier_value = i.source_product_id
LEFT JOIN
(
    SELECT source_pharmacy_id, argMax(pharmacy_key, loaded_at) AS pharmacy_key
    FROM dwh_core.dim_pharmacy_mapping FINAL
    WHERE source_system = 'storefront' AND is_current
    GROUP BY source_pharmacy_id
) AS phm ON phm.source_pharmacy_id = o.pharmacy_id
WHERE i.batch_id = toUUID('{{batch_id}}');

INSERT INTO dwh_core.fact_order_status_event
(
    status_event_key, source_system, order_key, occurred_at,
    previous_status, new_status
)
SELECT
    concat(
        'storefront:', source_order_id, ':',
        toString(status_version), ':', toString(fulfillment_version)
    ),
    'storefront',
    concat('storefront:', source_order_id),
    source_updated_at,
    '',
    if(notEmpty(fulfillment_status), fulfillment_status, order_status)
FROM dwh_raw.storefront_site_order FINAL
WHERE batch_id = toUUID('{{batch_id}}');

INSERT INTO dwh_core.fact_storefront_event
(
    event_key, source_system, event_name, event_version, event_source,
    occurred_at, event_date, profile_key_hash, customer_key_hash,
    anonymous_id_hash, session_id_hash, product_key, order_key,
    category_id, quantity, amount, currency, page_path, campaign,
    analytics_consent
)
SELECT
    toString(e.source_event_id),
    'storefront',
    e.event_name,
    e.event_version,
    e.event_source,
    e.occurred_at,
    toDate(e.occurred_at),
    e.profile_key_hash,
    e.customer_key_hash,
    e.anonymous_id_hash,
    e.session_id_hash,
    if(notEmpty(e.source_product_id), if(notEmpty(pm.product_key), pm.product_key, concat('storefront:', e.source_product_id)), NULL),
    if(notEmpty(e.source_order_id), concat('storefront:', e.source_order_id), NULL),
    nullIf(e.category_id, ''),
    e.quantity,
    e.amount,
    if(notEmpty(e.currency_code), upper(e.currency_code), NULL),
    e.page_path,
    e.campaign,
    e.analytics_consent
FROM dwh_raw.storefront_customer_event FINAL AS e
LEFT JOIN
(
    SELECT identifier_value, argMax(product_key, loaded_at) AS product_key
    FROM dwh_core.dim_product_mapping FINAL
    WHERE source_system = 'storefront' AND identifier_type = 'storefront_product_id' AND is_current
    GROUP BY identifier_value
) AS pm ON pm.identifier_value = e.source_product_id
WHERE e.batch_id = toUUID('{{batch_id}}');

-- Publish the snapshot only after every dimension and fact statement above succeeded.
INSERT INTO dwh_core.storefront_snapshot_manifest
(
    batch_id, source_system, captured_at,
    offer_count, warehouse_stock_count, product_category_count
)
SELECT
    batch_id,
    'storefront',
    captured_at,
    offer_count,
    warehouse_stock_count,
    product_category_count
FROM dwh_raw.storefront_snapshot_manifest FINAL
WHERE batch_id = toUUID('{{batch_id}}');
