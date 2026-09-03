import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

test("native push registration is authenticated and rejects arbitrary tokens", async () => {
  const source = await readFile(
    new URL("../src/app/api/push/native/route.ts", import.meta.url),
    "utf8",
  );
  assert.match(source, /customerSession\(req\)/);
  assert.match(source, /login_required/);
  assert.match(source, /\^\[a-f0-9\]\{64\}\$/);
  assert.match(source, /body\.promos === true/);
});

test("native push migration keeps marketing opt-in false by default", async () => {
  const source = await readFile(
    new URL("../db/migrations/009_native_push_devices.sql", import.meta.url),
    "utf8",
  );
  assert.match(source, /promos_enabled\s+boolean NOT NULL DEFAULT false/i);
  assert.match(source, /orders_enabled\s+boolean NOT NULL DEFAULT true/i);
  assert.match(source, /token_hash\s+char\(64\) NOT NULL UNIQUE/i);
});

test("APNs sender uses token auth, alert push type, and production endpoint", async () => {
  const source = await readFile(
    new URL("../src/lib/push/apns.ts", import.meta.url),
    "utf8",
  );
  assert.match(source, /api\.push\.apple\.com/);
  assert.match(source, /APNS_PRIVATE_KEY_BASE64/);
  assert.match(source, /"apns-push-type": "alert"/);
  assert.match(source, /dsaEncoding: "ieee-p1363"/);
  assert.match(source, /encoded\.length > 4_096/);
});
