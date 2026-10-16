INSERT INTO dwh_meta.source_system
    (source_system, description, owner, is_active)
VALUES
    ('standardn', 'Daily pharmacy inventory and sales export from Standard-N', 'data-platform', true),
    ('storefront', 'eFarm storefront orders and catalogue', 'ecommerce', true),
    ('pos', 'Pharmacy point-of-sale receipts', 'retail', true),
    ('sap', 'SAP master and movement data', 'enterprise-integration', true),
    ('hr', 'Employee and organizational structure', 'hr', true),
    ('merch', 'Merchandising tasks and reports', 'operations', true);

INSERT INTO dwh_core.dim_sales_channel
    (sales_channel_key, sales_channel_name, channel_group)
VALUES
    ('pharmacy_pos', 'Аптека / касса', 'offline'),
    ('storefront_web', 'Интернет-магазин', 'online'),
    ('mobile_app', 'Мобильное приложение', 'online'),
    ('call_center', 'Контакт-центр', 'assisted'),
    ('unknown', 'Не определён', 'unknown');

INSERT INTO dwh_core.dim_date
SELECT
    toDate('2020-01-01') + number AS date_key,
    toYear(date_key) AS year,
    toQuarter(date_key) AS quarter,
    toMonth(date_key) AS month,
    formatDateTime(date_key, '%B') AS month_name,
    toWeek(date_key, 3) AS week,
    toDayOfMonth(date_key) AS day,
    toDayOfWeek(date_key) AS day_of_week,
    day_of_week IN (6, 7) AS is_weekend
FROM numbers(dateDiff('day', toDate('2020-01-01'), toDate('2036-01-01')))
WHERE date_key NOT IN (SELECT date_key FROM dwh_core.dim_date);
