import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

test("customer route uses the shared fail-closed Medusa origin", () => {
  const source = readFileSync("src/app/api/customer/route.ts", "utf8");

  assert.match(source, /secureMedusaBaseUrl\(process\.env\.MEDUSA_URL\)/);
  assert.match(source, /const MEDUSA_ON = Boolean\(BASE && PK\)/);
  assert.match(source, /if \(!BASE\) return \{ status: 0, data: null \}/);
  assert.doesNotMatch(source, /http:\/\/78\.140\.246\.238:9000/);
});
