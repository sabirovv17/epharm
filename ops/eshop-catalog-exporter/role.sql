-- Run as the PostgreSQL administrator in the actual storefront database.
-- A new role has no password. Set it interactively with:
--   \password eshop_catalog_exporter
-- Then store the generated DATABASE_URL only in the server-side .env (mode 0600).
DO $role$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'eshop_catalog_exporter') THEN
    CREATE ROLE eshop_catalog_exporter LOGIN NOINHERIT;
  END IF;
END
$role$;

DO $database$
BEGIN
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO eshop_catalog_exporter', current_database());
END
$database$;
GRANT USAGE ON SCHEMA public TO eshop_catalog_exporter;
GRANT SELECT ON TABLE
  daribar_catalog_state,
  daribar_catalog_runs,
  daribar_catalog_products,
  daribar_availability_state,
  daribar_availability_runs,
  daribar_product_availability,
  daribar_delivery_product_mappings
TO eshop_catalog_exporter;

ALTER ROLE eshop_catalog_exporter SET default_transaction_read_only = on;
ALTER ROLE eshop_catalog_exporter SET statement_timeout = '10s';
ALTER ROLE eshop_catalog_exporter SET idle_in_transaction_session_timeout = '30s';
