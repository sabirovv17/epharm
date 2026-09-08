import assert from "node:assert/strict";
import test from "node:test";

import { secureMedusaUrl } from "../scripts/lib/secure-medusa-url.mjs";

test("offer synchronization rejects cleartext remote Medusa origins", () => {
  assert.equal(secureMedusaUrl("https://medusa.example.kz/").href, "https://medusa.example.kz/");

  for (const value of [
    "http://78.140.246.238:9000",
    "http://medusa.internal:9000",
    "https://user:password@medusa.example.kz",
    "https://medusa.example.kz?secret=value",
  ]) {
    assert.throws(() => secureMedusaUrl(value), /MEDUSA_URL|credentials/, value);
  }
});

test("offer synchronization retains localhost development support", () => {
  assert.equal(
    secureMedusaUrl("http://localhost:9000").href,
    "http://localhost:9000/",
  );
  assert.equal(
    secureMedusaUrl("http://127.0.0.1:9000").href,
    "http://127.0.0.1:9000/",
  );
});

test("offer synchronization rejects paths on the configured origin", () => {
  assert.throws(
    () => secureMedusaUrl("https://medusa.example.kz/store"),
    /MEDUSA_URL/,
  );
});
