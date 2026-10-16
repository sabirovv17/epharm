# eFarm DWH

This module is the ClickHouse-first analytical warehouse for eFarm. It extends the
existing `pim-etl` Standard N pipeline instead of querying the transactional eFarm
PostgreSQL database from Power BI.

## Layers

- `dwh_raw` - immutable source-shaped records and event envelopes.
- `dwh_core` - conformed dimensions and idempotent facts.
- `dwh_marts` - Power BI-ready views.
- `dwh_meta` - migrations, job runs, checkpoints and data-quality results.

The operational website stock remains in the storefront PostgreSQL read model.
ClickHouse keeps history and analytical marts; the storefront worker continues to
publish the latest accepted stock to the website.

## Current MVP scope

Implemented:

- canonical product, pharmacy, branch, employee, warehouse and channel dimensions;
- product/pharmacy source mapping with effective dates;
- sales receipt/item, stock snapshot, daily sales, order, bonus, merchandising and training fact contracts;
- a bridge from the existing `pharmacy_analytics.fact_pharmacy_daily` table;
- current-stock, daily-sales, bonus, order and training marts;
- job-run, checkpoint, rejected-row and data-quality metadata;
- repeatable HTTP migration and Standard N synchronization scripts.
- incremental PostgreSQL ingestion for the «Аптека со склада» storefront.

Not yet implemented because source contracts are missing:

- SAP PI ingestion;
- receipt-level export from all Standard N cash desks;
- near-real-time stock events;
- merchandising and training source ingestion;
- Power BI dataset and access policy.

## Local start

Copy `.env.example` to `.env`, replace the password and run:

```bash
docker compose --env-file .env up -d
python scripts/apply_migrations.py
```

The Docker bootstrap mounts the same SQL files, while `apply_migrations.py` is used
for an already running ClickHouse instance.

To import one already-published Standard N daily partition:

```bash
python scripts/sync_standardn_daily.py --date 2026-10-03
python scripts/run_data_quality.py --date 2026-10-03
```

Omit `--date` to use the latest complete source partition.

## «Аптека со склада»

The storefront loader reads the existing PostgreSQL read model and writes:

- catalogue products and variants into conformed product dimensions/mappings;
- pharmacy offers and warehouse availability into dedicated website snapshots;
- `site_orders` into `fact_order`;
- `metadata.line_items` into normalized `fact_order_item` rows;
- versioned order/fulfilment state into `fact_order_status_event`;
- consented CDP events into the website funnel fact.

Install the one PostgreSQL driver, configure a read-only source user in `.env`,
and set a private random hashing salt:

```bash
python -m pip install -r requirements.txt
python scripts/apply_migrations.py
python scripts/sync_storefront.py --full
python scripts/run_storefront_quality.py
```

Subsequent runs use a five-minute overlap around the stored watermark for
catalogue, orders and CDP events. Offers and warehouse availability are always
read as complete snapshots so deletions cannot leave false stock in the current
website mart:

```bash
python scripts/sync_storefront.py
```

The loader never copies customer phone, e-mail, delivery address or comment.
Customer/profile identifiers are HMAC-SHA256 pseudonyms. The salt must be kept
outside Git and must remain stable between runs.

Website offer snapshots deliberately stay separate from
`fact_stock_snapshot`: the offers are a projection already derived from
Standard N/ClickHouse and must not double the physical stock in reports.

## Source identity rules

The current production source uses `WARE_ID`, not `WayID`. The warehouse therefore
preserves every source identifier in mapping tables and does not assume that
`WARE_ID`, SAP material, Medusa product, SKU, barcode and Standard N `iPartID` are
interchangeable. A mapping can be activated only after deterministic matching or
manual verification.

## Development checks

```bash
python -m unittest discover -s tests -p "test_*.py" -v
```

The tests are hermetic and do not need a running ClickHouse server.
