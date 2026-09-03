import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

test("checkout receipt uses only the server-issued numeric code", async () => {
  const checkout = await readFile(
    new URL("../src/app/checkout/page.tsx", import.meta.url),
    "utf8",
  );

  assert.doesNotMatch(checkout, /api\.qrserver\.com|create-qr-code/);
  assert.doesNotMatch(checkout, /LocalQrCode|QRCode|<canvas/i);
  assert.match(checkout, /aria-label=\{code\.split\(""\)\.join\(" "\)\}/);
  assert.match(checkout, /\{code\}/);
});
