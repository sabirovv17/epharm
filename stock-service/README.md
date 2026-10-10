# Pharmacy stock service

This directory contains the JSON read API, a PostgreSQL snapshot store, a
one-time SQLite history importer and a **pilot** HTTP contract for future
cashier collectors. There is no web page. The old central Standard-N polling is
retired in this branch. No cashier collector is enabled until the local database
schema, permissions and load have been verified at a pilot pharmacy.

## Give this to an integrating developer

- [`openapi.yaml`](openapi.yaml): stable read API, including freshness,
  provenance and pagination.
- [`collector-openapi.yaml`](collector-openapi.yaml): scoped full-snapshot upload
  protocol for a verified cashier collector; the production gateway does not
  publish this route yet.
- [`docs/23-cashier-stock-transition.md`](../docs/23-cashier-stock-transition.md):
  source evidence, business meaning of quantities and the pilot gates.
- [`docs/22-stock-service.md`](../docs/22-stock-service.md): live endpoint,
  migration, backup and rollback runbook.

Use `GET /api/v1/status` and each pharmacy's `status`, `sourceKind` and
`sourceObservedAt`. `central_legacy` is historical even if its timestamp looks
recent. `quantity` describes physical warehouse stock. Sale or reservation
availability has **not** been established. Only a verified future collector can
make a pharmacy `cashier_local`; even then, consumers must not promise stock for
sale until the sellable rules are separately validated. An absent first snapshot
returns HTTP 503. A complete, confirmed empty snapshot returns `total: 0`.

## Local checks

```bash
dotnet test stock-service-tests/stock-service-tests.csproj
dotnet publish stock-service/StockService.csproj -c Release
docker compose --env-file stock-service/.env.example -f stock-service/compose.yml config --quiet
```

The PostgreSQL tests require
`STOCK_TEST_PG_OWNER_CONNECTION_STRING` and
`STOCK_TEST_PG_CONNECTION_STRING` against a disposable instance. Without them
those tests are skipped; a release run must report **zero skipped tests**.
The stock CI job provisions both roles and runs all integration tests.
The application account has DML rights and cannot create schema or
collector credentials. Schema bootstrap runs as `stock_schema_owner`; a
separate administrator provisions each collector token against one mapped HQ
and Standard-N pharmacy ID. Do not use the read API key for uploads.

## Production layout

The read API remains at
`https://inkeshopapteka.inkar.kz/stocks/api/v1/` inside the corporate network.
The `.80` gateway proxies to Nginx on `.81`; Nginx proxies to the service on
`127.0.0.1:18080`. Compose keeps PostgreSQL private and its data on a dedicated
host mount. Keep `.env` on the server with mode `0600`. `STOCK_COLLECTION_ENABLED`
must remain `false`: the old central collector must never resume.

Before cutover, make a consistent backup of the existing SQLite cache and
PostgreSQL, run the importer, compare counts and dates, and verify API responses
and rollback. See the runbook above. The SQLite package exists only in the
one-time importer image; the serving process uses PostgreSQL.

The backup scripts and systemd units are in [`deploy/`](deploy/). The stock
host writes a PostgreSQL custom-format dump every six hours, verifies its
catalogue and SHA-256 after delivery to the separate backup host. The receiver
is bound to one restricted SSH key. Local dumps are kept for seven days and
off-host dumps for thirty days. A restore into a separate database is required
before the first production cutover.
