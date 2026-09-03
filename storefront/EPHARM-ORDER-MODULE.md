# ePharm order bridge

The storefront writes the order and its outbound event in one PostgreSQL
transaction. A dedicated worker sends the exact frozen JSON body to ePharm and
then consumes the monotonic ePharm status feed. Checkout never waits for ePharm.

## Safety properties

- Only pharmacy pickup/fulfillment orders are exported. Warehouse and courier
  orders stay in the storefront.
- The payload contains product/order/pharmacy/payment facts only. Customer name,
  phone, email, address, comments and arbitrary metadata are never exported.
- `eventId`, the serialized body and SHA-256 are frozen at order creation. A
  retry sends the same bytes, making backend idempotency deterministic.
- `EPHARM_ORDER_START_AT` is mandatory when synchronization is enabled. Legacy
  outbox rows have no frozen body and are excluded as a second guard.
- One PostgreSQL advisory lock allows one active worker. Claims use
  `FOR UPDATE SKIP LOCKED`; abandoned five-minute leases are recovered.
- Reverse status updates and the feed cursor are committed atomically. Applying
  them never writes a new outbound event.
- Contract errors are quarantined with diagnostics; transport and 5xx errors use
  bounded exponential backoff.

## Production configuration

```dotenv
EPHARM_ORDER_SYNC_ENABLED=false
EPHARM_BASE_URL=https://epharm.inkar.kz
EPHARM_FULFILLMENT_SHARED_SECRET=<same random 32+ character secret as ePharm>
EPHARM_ORDER_START_AT=2026-09-03T12:00:00Z
EPHARM_SYNC_POLL_SEC=10
EPHARM_SYNC_TIMEOUT_MS=15000
EPHARM_SYNC_BATCH_SIZE=20
```

Keep synchronization disabled until the backend migration is deployed and at
least one exact `external pharmacy ID -> ePharm pharmacy ID` link is verified.
Enable one pharmacy as a pilot before broadening the mapping set.

The worker also accepts the deployment aliases `EPHARM_FULFILLMENT_ENABLED` and
`EPHARM_SHARED_SECRET`. The canonical names above take precedence when both are
present.

`unitPrice` is nullable only when the source order has no price snapshot.
Malformed or negative money makes an eligible pharmacy order fail atomically:
neither the order nor a partial outbox event is committed. Card/Kaspi/Halyk
orders remain blocked from issue until a trusted payment source marks them paid;
the first pilot must therefore use cash or an already-paid test order.

## Operations

```bash
npm run test:fulfillment
npm run orders:epharm:sync
```

The production Compose stack runs `epharm-order-worker` from the same immutable
image as the active storefront release. Its heartbeat is checked by Docker.
Inspect only metadata-safe logs with:

```bash
docker compose ... logs --tail=100 epharm-order-worker
```

Failed records and missing reverse-feed orders are recorded in
`epharm_sync_diagnostics`. Never print frozen order bodies in logs.
