import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

test("checkout requires login before sending an order and opens the existing auth modal", async () => {
  const source = await readFile(
    new URL("../src/app/checkout/page.tsx", import.meta.url),
    "utf8",
  );

  assert.match(source, /const \{ user, openLogin \} = useAuth\(\)/);
  assert.match(source, /if \(!user\) \{[\s\S]*?openLogin\(\);[\s\S]*?return;[\s\S]*?\}/);
  assert.match(source, /Войти или зарегистрироваться/);

  const authGuard = source.indexOf("if (!user)");
  const checkoutCall = source.indexOf("const order = await addOrder");
  assert.ok(authGuard >= 0, "checkout must contain an auth guard");
  assert.ok(checkoutCall > authGuard, "the auth guard must run before addOrder");
});

test("expired demo session is mapped to an explicit login recovery", async () => {
  const source = await readFile(
    new URL("../src/app/checkout/page.tsx", import.meta.url),
    "utf8",
  );

  assert.match(
    source,
    /error\.message === "demo_session_required"[\s\S]*?setFormError\(AUTH_REQUIRED_MESSAGE\)[\s\S]*?openLogin\(\)/,
  );
  assert.match(source, /AUTH_REQUIRED_MESSAGE = "Войдите или зарегистрируйтесь, чтобы оформить заказ\."/);
});
