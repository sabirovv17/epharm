import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import {
  canonicalFulfillmentRequest,
  fulfillmentSignature,
  parseStartAt,
  retryDelaySeconds,
  secureEpharmBaseUrl,
  storefrontStatus,
  validateUpdateFeed,
} from "../scripts/lib/epharm-contract.mjs";
import {
  buildEpharmOrderCreated,
  frozenOrderBody,
  requiresEpharmFulfillment,
} from "../src/lib/orders/epharmContract.ts";

const SECRET = "0123456789abcdef0123456789abcdef";

test("HMAC contract has a stable known vector", () => {
  const path = "/api/integrations/storefront/order-updates?after=0&limit=200";
  assert.equal(
    canonicalFulfillmentRequest(1_720_000_000, "GET", path, ""),
    "1720000000\nGET\n/api/integrations/storefront/order-updates?after=0&limit=200\ne3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  );
  assert.equal(
    fulfillmentSignature(SECRET, 1_720_000_000, "GET", path, ""),
    "21fac86cb7ba071ee9c7c00fcfaa51d6627770ce406f2fd86a32354f30e7365c",
  );
});

test("outbound order is strict, immutable and contains no customer PII", () => {
  const event = buildEpharmOrderCreated({
    id: "order_01",
    display_number: 1001,
    placed_at: "2026-09-03T10:00:00.000Z",
    total_amount: 1590,
    currency_code: "kzt",
    pickup_code: "123456",
    delivery_method: "pickup",
    is_demo: false,
    metadata: {
      fulfillment: "pickup",
      pharmacy_id: "ch:84",
      payment: "cash",
      payment_status: "pending",
      phone: "+77000000000",
      email: "private@example.kz",
      delivery_address: "private address",
      comment: "private comment",
      line_items: [{
        product_id: "prod_1",
        sku: "SKU-1",
        title: "Товар",
        quantity: 2,
        unit_price: 795,
      }],
    },
  }, "11111111-1111-4111-8111-111111111111");

  assert.ok(event);
  assert.deepEqual(Object.keys(event), [
    "eventId", "orderId", "number", "pharmacyExternalId", "createdAt", "total",
    "currency", "delivery", "paymentMethod", "paymentStatus", "demo", "pickupCode", "lines",
  ]);
  const frozen = frozenOrderBody(event);
  assert.equal(frozen.body, JSON.stringify(event));
  assert.match(frozen.sha256, /^[0-9a-f]{64}$/);
  assert.doesNotMatch(frozen.body, /phone|email|address|comment|private/i);
});

test("invalid or warehouse orders never produce a fulfillment event", () => {
  const base = {
    id: "order_02",
    display_number: 1002,
    placed_at: "2026-09-03T10:00:00.000Z",
    total_amount: 100,
    currency_code: "kzt",
    pickup_code: "123456",
    delivery_method: "courier",
    is_demo: false,
    metadata: {
      fulfillment: "warehouse",
      pharmacy_id: "",
      payment: "cash",
      payment_status: "pending",
      line_items: [{ product_id: "prod_1", quantity: 1, unit_price: 100 }],
    },
  };
  assert.equal(buildEpharmOrderCreated(base, "22222222-2222-4222-8222-222222222222"), null);
  assert.equal(buildEpharmOrderCreated({ ...base, placed_at: "invalid" }, "22222222-2222-4222-8222-222222222222"), null);
});

test("unit price is nullable when absent while malformed money fails closed", () => {
  const base = {
    id: "order_nullable_price",
    display_number: 1003,
    placed_at: "2026-09-03T10:00:00.000Z",
    total_amount: 100.25,
    currency_code: "kzt",
    pickup_code: "123456",
    delivery_method: "pickup",
    is_demo: false,
    metadata: {
      fulfillment: "pickup",
      pharmacy_id: "ch:84",
      payment: "cash",
      payment_status: "pending",
      line_items: [{ product_id: "prod_1", title: "Товар", quantity: 1 }],
    },
  };
  const event = buildEpharmOrderCreated(base, "33333333-3333-4333-8333-333333333333");
  assert.ok(event);
  assert.equal(event.total, 100.25);
  assert.equal(event.lines[0].unitPrice, null);
  assert.equal(requiresEpharmFulfillment(base), true);

  const malformed = {
    ...base,
    id: "order_negative_price",
    metadata: {
      ...base.metadata,
      line_items: [{ product_id: "prod_1", title: "Товар", quantity: 1, unit_price: -1 }],
    },
  };
  assert.equal(buildEpharmOrderCreated(malformed, "44444444-4444-4444-8444-444444444444"), null);
});

test("status feed is strictly monotonic and maps to storefront labels", () => {
  const feed = validateUpdateFeed({
    nextCursor: 12,
    hasMore: false,
    updates: [
      { cursor: 11, orderId: "o1", status: "assembling", version: 2 },
      { cursor: 12, orderId: "o1", status: "ready", version: 3 },
    ],
  }, 10);
  assert.equal(feed.nextCursor, 12);
  assert.equal(storefrontStatus("assembling"), "Собирается");
  assert.equal(storefrontStatus("completed"), "Получен");
  assert.throws(() => validateUpdateFeed({
    nextCursor: 11,
    hasMore: false,
    updates: [{ cursor: 11, orderId: "o1", status: "unknown", version: 2 }],
  }, 10), /fulfillment_status_invalid/);
});

test("production URL, start boundary and bounded retry policy fail closed", () => {
  assert.equal(secureEpharmBaseUrl("https://epharm.inkar.kz"), "https://epharm.inkar.kz");
  assert.throws(() => secureEpharmBaseUrl("http://epharm.inkar.kz"), /requires_https/);
  assert.throws(() => secureEpharmBaseUrl("https://epharm.inkar.kz/api"), /must_be_origin/);
  assert.equal(parseStartAt("2026-09-03T10:00:00Z"), "2026-09-03T10:00:00.000Z");
  assert.throws(() => parseStartAt(""), /start_at_invalid/);
  assert.equal(retryDelaySeconds(1, 0), 5);
  assert.equal(retryDelaySeconds(99, 0.99), 1_284);
});

test("migration and worker enforce the activation boundary and frozen payload", async () => {
  const migration = await readFile(new URL("../db/migrations/010_epharm_fulfillment.sql", import.meta.url), "utf8");
  const worker = await readFile(new URL("../scripts/sync-epharm-orders.mjs", import.meta.url), "utf8");
  const store = await readFile(new URL("../src/lib/orders/store.ts", import.meta.url), "utf8");
  assert.match(migration, /frozen_body\s+text/i);
  assert.match(migration, /fulfillment_cursor\s+bigint/i);
  assert.match(worker, /created_at\s+>=\s+\$1::timestamptz/i);
  assert.match(worker, /frozen_body IS NOT NULL/i);
  assert.match(worker, /FOR UPDATE SKIP LOCKED/i);
  assert.match(worker, /pg_try_advisory_lock/i);
  assert.match(migration, /total_amount TYPE numeric\(14,2\)/i);
  assert.match(store, /requiresEpharmFulfillment\(row\)[\s\S]*throw new Error\("epharm_order_event_invalid"\)/);
  assert.doesNotMatch(worker, /customer_id|delivery_address|phone|email/i);
});
