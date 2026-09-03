import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

test("customer orders expose stored receipt details and demo line previews", async () => {
  const route = await readFile(
    new URL("../src/app/api/customer/orders/route.ts", import.meta.url),
    "utf8",
  );

  assert.match(route, /order\.metadata\?\.line_items/);
  assert.match(route, /preview:\s*storedOrderPreview\(order\)/);
  assert.match(route, /code:\s*receiptCode\(order\.code\)/);
  assert.match(route, /delivery:\s*deliveryMethod\(order\.delivery\)/);
  assert.match(route, /storedBySourceId/);
  assert.match(route, /code:\s*receiptCode\(stored\?\.code\)/);
  assert.match(route, /stored\?\.fulfillmentStatus/);
});

test("visible customer order list polls every 15 seconds and preserves the last snapshot", async () => {
  const hook = await readFile(
    new URL("../src/lib/orders/useOrders.ts", import.meta.url),
    "utf8",
  );

  assert.match(hook, /setInterval\(refreshVisible,\s*15_000\)/);
  assert.match(hook, /visibilitychange/);
  assert.match(hook, /if \(!response\.ok\) throw/);
  assert.doesNotMatch(hook, /catch[\s\S]{0,120}setOrders\(\[\]\)/);
});

test("order details button reveals products, delivery and a numeric receipt code", async () => {
  const page = await readFile(
    new URL("../src/app/account/orders/page.tsx", import.meta.url),
    "utf8",
  );

  assert.match(page, /setExpandedOrderId\(expanded \? null : o\.id\)/);
  assert.match(page, /aria-expanded=\{expanded\}/);
  assert.match(page, /t\("acc\.ord\.contents"\)/);
  assert.match(page, /t\("acc\.ord\.delivery"\)/);
  assert.match(page, /t\("acc\.ord\.code"\)/);
  assert.match(page, /aria-label=\{o\.code\.split\(""\)\.join\(" "\)\}/);
  assert.doesNotMatch(page, /QRCode|<canvas|api\.qrserver\.com/i);
});
