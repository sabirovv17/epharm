import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

test("product and cart instrumentation stays outside checkout and the events API", async () => {
  const [productPage, tracker, cart] = await Promise.all([
    readFile(new URL("../src/app/product/[slug]/page.tsx", import.meta.url), "utf8"),
    readFile(new URL("../src/components/cdp/ProductViewTracker.tsx", import.meta.url), "utf8"),
    readFile(new URL("../src/lib/cart/CartContext.tsx", import.meta.url), "utf8"),
  ]);

  assert.match(productPage, /<ProductViewTracker productId=\{product\.id\}/);
  assert.match(tracker, /emitProductViewed\(\{ productId, variantId \}\)/);
  assert.match(cart, /emitCartItemAdded\(\{ productId: product\.id, variantId: product\.variantId \}, qty\)/);
  assert.match(cart, /emitCartItemRemoved\(/);
  assert.match(cart, /emitCartItemsCleared\(/);

  const checkoutCleanup = cart.match(/const removeSelected = useCallback\([\s\S]*?\n  \}, \[unselected\]\);/)?.[0];
  assert.ok(checkoutCleanup, "removeSelected block must remain present");
  assert.doesNotMatch(checkoutCleanup, /emitCartItem/);
});
