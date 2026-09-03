import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

// These source-contract tests guard every web removal entry point without a DOM runner.
function confirmationBlocks(source) {
  return source.match(/<ConfirmCartDelete[\s\S]*?\/>/g) ?? [];
}

test("cart removal confirmation is a portalled accessible modal", async () => {
  const confirmation = await readFile(
    new URL("../src/components/cart/ConfirmCartDelete.tsx", import.meta.url),
    "utf8",
  );

  assert.match(confirmation, /createPortal\(/);
  assert.match(confirmation, /document\.body/);
  assert.match(confirmation, /role="dialog"/);
  assert.match(confirmation, /aria-modal="true"/);
  assert.match(confirmation, /aria-labelledby=\{titleId\}/);
  assert.match(confirmation, /aria-describedby=\{[^\n]*descriptionId[^\n]*\}/);
  assert.match(confirmation, /aria-haspopup="dialog"/);
  assert.match(confirmation, /event\.key === "Escape"/);
  assert.match(confirmation, /event\.key !== "Tab"/);
  assert.match(confirmation, /cancelRef\.current\?\.focus\(\)/);
  assert.match(confirmation, /triggerRef\.current\?\.focus\(\)/);
  assert.match(confirmation, /document\.body\.style\.overflow = "hidden"/);
  assert.match(confirmation, /document\.body\.style\.overflow = previousOverflow/);
  assert.match(confirmation, /onClick=\{confirm\}/);
});

test("every web cart removal caller supplies explicit copy and never deletes on the first click", async () => {
  const [page, drawer, stepper] = await Promise.all([
    readFile(new URL("../src/app/cart/page.tsx", import.meta.url), "utf8"),
    readFile(new URL("../src/components/cart/CartDrawer.tsx", import.meta.url), "utf8"),
    readFile(new URL("../src/components/ui/QuantityStepper.tsx", import.meta.url), "utf8"),
  ]);

  const pageConfirmations = confirmationBlocks(page);
  const drawerConfirmations = confirmationBlocks(drawer);

  assert.equal(pageConfirmations.length, 2, "cart page must confirm item removal and clear-all");
  assert.equal(drawerConfirmations.length, 1, "cart drawer must confirm item removal");
  for (const block of [...pageConfirmations, ...drawerConfirmations]) {
    assert.match(block, /\bonConfirm=/);
    assert.match(block, /\btitle=\{/);
    assert.match(block, /\bdescription=\{/);
    assert.match(block, /\bconfirmLabel=\{/);
    assert.match(block, /\bcancelLabel=\{/);
  }

  assert.doesNotMatch(page, /onClick=\{\(\) => remove\(/);
  assert.doesNotMatch(drawer, /onClick=\{\(\) => remove\(/);
  assert.doesNotMatch(page, /onClick=\{clear\}/);
  assert.match(stepper, /disabled=\{atMinimum\}/);
});

test("cart page allows its grid and cards to shrink on narrow mobile screens", async () => {
  const page = await readFile(new URL("../src/app/cart/page.tsx", import.meta.url), "utf8");
  assert.match(page, /grid-cols-\[minmax\(0,1fr\)\]/);
  assert.match(page, /lg:grid-cols-\[minmax\(0,1fr\)_360px\]/);
  assert.match(page, /className="min-w-0 space-y-3"/);
  assert.match(page, /flex min-w-0 gap-2 rounded-2xl/);
  assert.match(page, /aside className="min-w-0"/);
});

test("confirmed removals expose undo through actionable dismissible toasts and cart restore", async () => {
  const [page, drawer, toastContext, toastViewport, cartContext] = await Promise.all([
    readFile(new URL("../src/app/cart/page.tsx", import.meta.url), "utf8"),
    readFile(new URL("../src/components/cart/CartDrawer.tsx", import.meta.url), "utf8"),
    readFile(new URL("../src/lib/ui/ToastContext.tsx", import.meta.url), "utf8"),
    readFile(new URL("../src/components/ui/ToastViewport.tsx", import.meta.url), "utf8"),
    readFile(new URL("../src/lib/cart/CartContext.tsx", import.meta.url), "utf8"),
  ]);

  assert.match(toastContext, /actionLabel\?: string/);
  assert.match(toastContext, /onAction\?: \(\) => void/);
  assert.match(toastContext, /dismiss: \(id: number\) => void/);
  assert.match(toastContext, /const dismiss = useCallback/);
  assert.match(toastContext, /window\.setTimeout\(\(\) => dismiss\(id\)/);
  assert.match(toastViewport, /role="status"/);
  assert.match(toastViewport, /t\.actionLabel && t\.onAction/);
  assert.match(toastViewport, /t\.onAction\?\.\(\)/);
  assert.match(toastViewport, /dismiss\(t\.id\)/);

  assert.match(cartContext, /restore: \(product: Product, qty\?: number\) => void/);
  const restoreBlock = cartContext.match(
    /const restore = useCallback\([\s\S]*?\n  \}, \[\]\);/,
  )?.[0];
  assert.ok(restoreBlock, "CartContext must implement restore as a stable callback");
  assert.match(restoreBlock, /setItems\(/);
  assert.doesNotMatch(
    restoreBlock,
    /setIsOpen\(true\)/,
    "undo must not unexpectedly open the cart drawer",
  );
  assert.match(cartContext, /add, restore, remove, setQty, clear/);

  for (const source of [page, drawer]) {
    assert.match(source, /actionLabel: t\("cart\.undo"\)/);
    assert.match(source, /onAction: \(\) =>/);
    assert.match(source, /restore\(/);
  }
});
