import assert from "node:assert/strict";
import { after, test } from "node:test";

import {
  CDP_OPT_OUT_STORAGE_KEY,
  emitCartItemAdded,
  emitCartItemRemoved,
  emitCartItemsCleared,
  emitProductViewed,
  setCdpOptOut,
} from "../src/lib/cdp/client.ts";

class MemoryStorage {
  #values = new Map();
  #writable;

  constructor(writable = true) {
    this.#writable = writable;
  }

  get length() { return this.#values.size; }
  clear() { this.#values.clear(); }
  getItem(key) { return this.#values.get(String(key)) ?? null; }
  key(index) { return [...this.#values.keys()][index] ?? null; }
  removeItem(key) {
    if (!this.#writable) throw new Error("storage_denied");
    this.#values.delete(String(key));
  }
  setItem(key, value) {
    if (!this.#writable) throw new Error("storage_denied");
    this.#values.set(String(key), String(value));
  }
}

function browser({
  dnt = "0",
  globalPrivacyControl = false,
  localStorage = new MemoryStorage(),
  sessionStorage = new MemoryStorage(),
} = {}) {
  const requests = [];
  let sequence = 0;
  const window = {
    navigator: { doNotTrack: dnt, globalPrivacyControl },
    localStorage,
    sessionStorage,
    crypto: {
      randomUUID() {
        sequence += 1;
        return `00000000-0000-4000-8000-${String(sequence).padStart(12, "0")}`;
      },
    },
    fetch(url, init) {
      requests.push({ url, init });
      return Promise.resolve({ ok: true });
    },
  };
  Object.defineProperty(globalThis, "window", { configurable: true, writable: true, value: window });
  return { localStorage, requests, sessionStorage };
}

function requestEvent(request, index = 0) {
  return JSON.parse(request.init.body).events[index];
}

after(() => {
  Reflect.deleteProperty(globalThis, "window");
});

test("emits a bounded first-party product event with stable anonymous/session IDs", () => {
  const state = browser();
  assert.equal(emitProductViewed({ productId: "prod_123", variantId: "variant_456" }), true);
  assert.equal(state.requests.length, 1);

  const firstRequest = state.requests[0];
  const first = requestEvent(firstRequest);
  assert.equal(firstRequest.url, "/api/events");
  assert.equal(firstRequest.init.method, "POST");
  assert.equal(firstRequest.init.keepalive, true);
  assert.equal(firstRequest.init.credentials, "same-origin");
  assert.equal(first.name, "product_viewed");
  assert.match(first.idempotencyKey, /^[A-Za-z0-9:_-]{16,120}$/);
  assert.deepEqual(first.properties, { productId: "prod_123", variantId: "variant_456" });
  assert.deepEqual(first.context, { app: "inkar_storefront", surface: "pdp" });
  assert.equal(first.consent.marketing, false);
  assert.equal(first.consent.scope, "first_party_product_measurement");
  assert.ok(new TextEncoder().encode(firstRequest.init.body).byteLength <= 12 * 1024);

  assert.equal(emitCartItemAdded({ productId: "prod_789", variantId: "variant_789" }, 2), true);
  const second = requestEvent(state.requests[1]);
  assert.equal(second.anonymousId, first.anonymousId);
  assert.equal(second.sessionId, first.sessionId);
  assert.equal(second.name, "cart_item_added");
  assert.deepEqual(second.properties, {
    productId: "prod_789",
    variantId: "variant_789",
    quantity: 2,
    reason: "add",
  });
  assert.doesNotMatch(firstRequest.init.body, /phone|email|address|authorization|cookie|secret|token|referrer/i);
});

test("deduplicates an immediate React-style product view without a second request", () => {
  const state = browser();
  assert.equal(emitProductViewed({ productId: "prod_dedupe_a" }), true);
  assert.equal(emitProductViewed({ productId: "prod_dedupe_a" }), false);
  assert.equal(state.requests.length, 1);
});

test("respects Do-Not-Track and Global Privacy Control before creating IDs", () => {
  const dnt = browser({ dnt: "1" });
  assert.equal(emitProductViewed({ productId: "prod_dnt_a" }), false);
  assert.equal(dnt.requests.length, 0);
  assert.equal(dnt.localStorage.length, 0);
  assert.equal(dnt.sessionStorage.length, 0);

  const gpc = browser({ globalPrivacyControl: true });
  assert.equal(emitCartItemRemoved({ productId: "prod_gpc_a" }, 1), false);
  assert.equal(gpc.requests.length, 0);
  assert.equal(gpc.localStorage.length, 0);
  assert.equal(gpc.sessionStorage.length, 0);
});

test("explicit opt-out removes pseudonymous IDs and disables emission", () => {
  const state = browser();
  assert.equal(emitCartItemAdded({ productId: "prod_opt_a" }, 1), true);
  assert.ok(state.localStorage.length > 0);
  assert.ok(state.sessionStorage.length > 0);

  setCdpOptOut(true);
  assert.equal(state.localStorage.getItem(CDP_OPT_OUT_STORAGE_KEY), "1");
  assert.equal(state.sessionStorage.length, 0);
  const before = state.requests.length;
  assert.equal(emitCartItemRemoved({ productId: "prod_opt_a" }, 1), false);
  assert.equal(state.requests.length, before);

  setCdpOptOut(false);
  assert.equal(state.localStorage.getItem(CDP_OPT_OUT_STORAGE_KEY), null);
  assert.equal(emitCartItemAdded({ productId: "prod_opt_b" }, 1), true);
});

test("requires writable stable-ID storage and rejects invalid event fields", () => {
  const denied = browser({
    localStorage: new MemoryStorage(false),
    sessionStorage: new MemoryStorage(false),
  });
  assert.equal(emitProductViewed({ productId: "prod_storage_a" }), false);
  assert.equal(denied.requests.length, 0);

  const state = browser();
  assert.equal(emitCartItemAdded({ productId: "bad id with spaces" }, 1), false);
  assert.equal(emitCartItemAdded({ productId: "prod_bounds_a" }, 0), false);
  assert.equal(emitCartItemRemoved({ productId: "prod_bounds_a" }, 1000), false);
  assert.equal(state.requests.length, 0);
});

test("clear-all batches at most twenty product-only removals", () => {
  const state = browser();
  const items = Array.from({ length: 35 }, (_, index) => ({
    productId: `prod_clear_${index}`,
    variantId: `variant_clear_${index}`,
    quantity: index + 1,
  }));
  assert.equal(emitCartItemsCleared(items), true);
  assert.equal(state.requests.length, 1);
  const payload = JSON.parse(state.requests[0].init.body);
  assert.equal(payload.events.length, 20);
  assert.ok(payload.events.every((event) => event.name === "cart_item_removed"));
  assert.ok(payload.events.every((event) => event.properties.reason === "clear"));
  assert.ok(new TextEncoder().encode(state.requests[0].init.body).byteLength <= 12 * 1024);
});
