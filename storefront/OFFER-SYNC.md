# Incremental per-pharmacy prices

`calculated_price` is empty for the current Medusa sales channel. Run this
resumable worker after the product catalogue has been imported and migrations
have been applied:

```bash
npm run catalog:offers:sync -- --limit 20          # read-only canary
npm run catalog:offers:sync -- --apply --limit 300 # persist one batch
```

The worker selects products with no checkpoint or the oldest offers, holds the
same Postgres advisory lock as the full catalogue sync, and uses bounded
concurrency, response size, retries, and request timeouts. The checkpoint in
`catalog_offer_sync_state` makes each invocation resume the oldest work.

A valid non-empty response atomically replaces one product's offers. Failed,
malformed, oversized, and empty responses update only the checkpoint; they
never erase last-known-good offers. Apply mode is explicit, so a command
without `--apply` is a network/database canary and does not write.

## Availability contract

Medusa's public `GET /store/products/:id/pharmacies` response currently has no
reliable stock quantity. Under the current business contract, **presence in
`pharmacies[]` means available**. The synchronizer sets `in_stock=true` and
keeps `stock_quantity=NULL`. Replace this assumption once ACC/Epharm exposes an
explicit balance or availability field.

## Scheduling

Install `deploy/systemd/inkar-shop-offer-sync.{service,timer}`. The timer runs a
bounded batch every ten minutes. If a full catalogue run or another offer run
already owns the advisory lock, the new invocation exits successfully without
starting upstream requests.

Useful checks:

```bash
sudo systemctl start inkar-shop-offer-sync.service
journalctl -u inkar-shop-offer-sync.service -n 100 --no-pager
psql "$DATABASE_URL" -c "select status,count(*) from catalog_offer_sync_state group by status"
```

## ClickHouse PIM/Epharm source

The same worker can read authoritative pharmacy prices and stock from
ClickHouse instead of calling one Medusa endpoint per product. It still writes
the existing `catalog_pharmacies` / `catalog_pharmacy_offers` boundary, so the
storefront and checkout do not gain a second runtime dependency.

```env
CATALOG_OFFER_SOURCE=clickhouse
CLICKHOUSE_URL=http://127.0.0.1:18123
CLICKHOUSE_USER=inkar_shop_readonly
CLICKHOUSE_PASSWORD=load-from-server-secret
CLICKHOUSE_DATABASE=pharmacy_analytics
CLICKHOUSE_FACT_TABLE=pharmacy_analytics.fact_pharmacy_daily
CLICKHOUSE_MAP_TABLE=pharmacy_analytics.product_material_map
CLICKHOUSE_WAREHOUSE_TABLE=pharmacy_analytics.head_warehouse_stock
```

Do not put credentials in `CLICKHOUSE_URL` or commit a populated env file.
Use an HTTPS endpoint where available. A loopback SSH tunnel is accepted
without an opt-in. The deployment container can use
`http://host.docker.internal:18123` only with the narrow compatibility switch:

```env
CLICKHOUSE_ALLOW_INSECURE_PRIVATE_HTTP=true
```

That switch permits only RFC1918 IPs and `host.docker.internal`; it does not
permit arbitrary cleartext hostnames. Bind the tunnel to a host interface that
is reachable from the operation container but not from the public network, and
use a ClickHouse account with `SELECT` access only to the three source tables.

Run the migration and a read-only canary first:

```bash
npm run db:migrate
npm run catalog:offers:sync -- --source clickhouse --limit 20
npm run catalog:offers:sync -- --source clickhouse --apply --limit 300
```

The connector:

- reads `max(snapshot_date)` independently from the pharmacy fact and
  head-warehouse tables;
- prunes each large query to that exact `Date` snapshot before filtering SKU;
- resolves products only by exact `metadata.ware_id`, variant SKU
  (`material_id`) and barcode through `product_material_map`;
- rejects conflicting, ambiguous and cross-product mappings instead of
  attaching a price to the wrong medicine;
- writes `retail_price`, `stock_end` and the source snapshot atomically;
- stores head-warehouse `quantity` / `in_transit` separately in
  `catalog_warehouse_stocks` because warehouse rows do not have a retail
  pharmacy price;
- treats zero pharmacy rows in a versioned ClickHouse snapshot as an
  authoritative out-of-stock state; failures and malformed rows preserve the
  last successful snapshot.

`product_material_map` is incomplete today. Products without one safe exact
mapping are logged as retryable failures and remain on their last-known-good
offers. Backfilling the mapping table increases coverage without any storefront
code change.
