import assert from "node:assert/strict";
import test from "node:test";

import {
  activateCode,
  canSend,
  discardCode,
  reserveCode,
  smscResponseResult,
  validateCode,
} from "./otp.ts";

test("exhausting verify attempts does not erase resend cooldown", () => {
  const phone = "77000000001";
  const startedAt = 1_000_000;

  assert.equal(reserveCode(phone, "123456", startedAt), true);
  assert.equal(activateCode(phone, "123456"), true);
  for (let attempt = 0; attempt < 5; attempt += 1) {
    assert.deepEqual(validateCode(phone, "000000", startedAt + attempt), {
      ok: false,
      reason: "mismatch",
    });
  }
  assert.deepEqual(validateCode(phone, "000000", startedAt + 5), {
    ok: false,
    reason: "too_many",
  });

  assert.equal(canSend(phone, startedAt + 29_999), false);
  assert.equal(canSend(phone, startedAt + 30_000), true);
});

test("provider failure releases both reservation and resend cooldown", () => {
  const phone = "77000000002";
  const startedAt = 2_000_000;

  assert.equal(reserveCode(phone, "654321", startedAt), true);
  discardCode(phone, "654321");
  assert.equal(canSend(phone, startedAt), true);
});

test("SMSC response accepts a queued message id", () => {
  assert.deepEqual(smscResponseResult(true, 200, { id: 123, cnt: 1 }), { ok: true });
});

test("SMSC response rejects provider and malformed responses", () => {
  assert.deepEqual(
    smscResponseResult(true, 200, { error: "authorise error", error_code: 2 }),
    { ok: false, error: "authorise error" },
  );
  assert.deepEqual(
    smscResponseResult(true, 200, { balance: "100" }),
    { ok: false, error: "smsc_invalid_response" },
  );
  assert.deepEqual(
    smscResponseResult(false, 502, {}),
    { ok: false, error: "http_502" },
  );
});
