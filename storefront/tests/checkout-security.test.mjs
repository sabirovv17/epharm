import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import { canonicalizeCheckoutItems } from "../src/lib/checkoutItems.ts";

test("checkout canonicalizes duplicate variant lines before pricing and order creation", () => {
  assert.deepEqual(
    canonicalizeCheckoutItems([
      { productId: "prod_A1", variantId: "variant_V1", quantity: 2 },
      { product_id: "prod_A1", variant_id: "variant_V1", quantity: 3 },
      { productId: "prod_B2", variantId: "variant_V2", quantity: 1 },
    ]),
    [
      { productId: "prod_A1", variantId: "variant_V1", quantity: 5 },
      { productId: "prod_B2", variantId: "variant_V2", quantity: 1 },
    ],
  );
});

test("checkout rejects duplicate quantities that exceed the per-variant limit", () => {
  assert.equal(
    canonicalizeCheckoutItems([
      { productId: "prod_A1", variantId: "variant_V1", quantity: 60 },
      { productId: "prod_A1", variantId: "variant_V1", quantity: 40 },
    ]),
    null,
  );
});

test("one quantity-99 line is accepted while the former 4,950-unit collision is rejected", () => {
  assert.deepEqual(
    canonicalizeCheckoutItems([
      { productId: "prod_A1", variantId: "variant_V1", quantity: 99 },
    ]),
    [{ productId: "prod_A1", variantId: "variant_V1", quantity: 99 }],
  );
  assert.equal(
    canonicalizeCheckoutItems(Array.from({ length: 50 }, () => ({
      productId: "prod_A1",
      variantId: "variant_V1",
      quantity: 99,
    }))),
    null,
  );
});

test("checkout rejects rounded, defaulted, or otherwise ambiguous quantities", () => {
  for (const quantity of [0, -1, 1.5, Number.NaN, "not-a-number"]) {
    assert.equal(
      canonicalizeCheckoutItems([
        { productId: "prod_A1", variantId: "variant_V1", quantity },
      ]),
      null,
    );
  }
});

test("checkout route sends server-verified canonical lines to every order sink", async () => {
  const source = await readFile(
    new URL("../src/app/api/checkout/route.ts", import.meta.url),
    "utf8",
  );

  assert.match(source, /const canonical = canonicalizeCheckoutItems\(raw\)/);
  assert.match(source, /const items = itemsOf\(body\?\.cartItems \?\? body\?\.items\)/);
  assert.match(source, /for \(const item of items\)/);
  assert.match(source, /const fulfillmentItems = verifiedQuote\.lines\.map/);
  assert.match(source, /recordCompletedStorefrontOrder\(\{[\s\S]*?\n\s+items: fulfillmentItems,/);
  assert.match(source, /recordCompletedMedusaOrder\(\{[\s\S]*?fallbackItems: fulfillmentItems,/);
  assert.doesNotMatch(source, /Math\.round\(Number\(value\.quantity\)/);
});

test("checkout bounds and validates JSON before processing order fields", async () => {
  const source = await readFile(
    new URL("../src/app/api/checkout/route.ts", import.meta.url),
    "utf8",
  );

  const boundedRead = source.indexOf("readBoundedJson<unknown>");
  const firstOrderField = source.indexOf("body?.cartItems");
  assert.ok(boundedRead >= 0);
  assert.ok(firstOrderField > boundedRead);
  assert.match(source, /MAX_CHECKOUT_BODY_BYTES = 64 \* 1024/);
  assert.match(source, /status: error\.status/);
});

test("legacy order endpoint cannot persist client-supplied price or status", async () => {
  const source = await readFile(
    new URL("../src/app/api/orders/route.ts", import.meta.url),
    "utf8",
  );

  assert.doesNotMatch(source, /createOrder|body\.sum|body\.items/);
  assert.match(source, /legacy_order_endpoint_disabled/);
  assert.match(source, /status:\s*410/);
});
