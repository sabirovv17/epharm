"use client";

export const CDP_OPT_OUT_STORAGE_KEY = "inkar-cdp-opt-out-v1";

const INSTALL_ID_STORAGE_KEY = "inkar-cdp-install-id-v1";
const SESSION_ID_STORAGE_KEY = "inkar-cdp-session-id-v1";
const MAX_BATCH_EVENTS = 20;
const MAX_BODY_BYTES = 12 * 1024;
const MAX_RECENT_DEDUPE = 64;
const DEDUPE_WINDOW_MS = 5_000;
const SAFE_ID = /^[A-Za-z0-9_.:-]{1,160}$/;
const STORED_ID = /^[A-Za-z0-9:_-]{16,120}$/;

type ProductSurface = "pdp" | "cart_state";
type CartReason = "add" | "quantity_increase" | "remove" | "quantity_decrease" | "clear";
type ProductEventName = "product_viewed" | "cart_item_added" | "cart_item_removed";

type ProductIdentity = {
  productId: string;
  variantId?: string | null;
};

type ProductEvent = ProductIdentity & {
  name: ProductEventName;
  quantity?: number;
  surface: ProductSurface;
  reason?: CartReason;
  dedupeKey?: string;
};

type StoredDedupe = { expiresAt: number };
const recentDedupe = new Map<string, StoredDedupe>();

type PrivacyNavigator = Navigator & {
  globalPrivacyControl?: boolean;
  msDoNotTrack?: string | null;
};

function storageRead(storage: Storage, key: string): string | null {
  try {
    return storage.getItem(key);
  } catch {
    return null;
  }
}

function storageWrite(storage: Storage, key: string, value: string): boolean {
  try {
    storage.setItem(key, value);
    return true;
  } catch {
    return false;
  }
}

function secureId(prefix: "install" | "session" | "event"): string | null {
  const crypto = window.crypto;
  if (!crypto) return null;
  let value: string;
  if (typeof crypto.randomUUID === "function") {
    value = crypto.randomUUID();
  } else {
    const bytes = new Uint8Array(16);
    crypto.getRandomValues(bytes);
    value = Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
  }
  const id = `${prefix}_${value.replace(/-/g, "_")}`;
  return STORED_ID.test(id) ? id : null;
}

function stableId(storage: Storage, key: string, prefix: "install" | "session"): string | null {
  const stored = storageRead(storage, key);
  if (stored && STORED_ID.test(stored)) return stored;
  const created = secureId(prefix);
  if (!created || !storageWrite(storage, key, created)) return null;
  return created;
}

function privacyBlocksMeasurement(): boolean {
  if (typeof window === "undefined") return true;
  const navigator = window.navigator as PrivacyNavigator;
  const dnt = navigator.doNotTrack ?? navigator.msDoNotTrack;
  if (dnt === "1" || dnt?.toLowerCase() === "yes" || navigator.globalPrivacyControl === true) {
    return true;
  }
  const optOut = storageRead(window.localStorage, CDP_OPT_OUT_STORAGE_KEY)?.toLowerCase();
  return optOut === "1" || optOut === "true" || optOut === "yes";
}

function cleanIdentity(identity: ProductIdentity): { productId: string; variantId?: string } | null {
  const productId = typeof identity.productId === "string" ? identity.productId.trim() : "";
  const variantId = typeof identity.variantId === "string" ? identity.variantId.trim() : "";
  if (!SAFE_ID.test(productId) || (variantId && !SAFE_ID.test(variantId))) return null;
  return { productId, ...(variantId ? { variantId } : {}) };
}

function cleanQuantity(quantity: number | undefined): number | undefined {
  if (quantity === undefined) return undefined;
  return Number.isSafeInteger(quantity) && quantity >= 1 && quantity <= 999 ? quantity : undefined;
}

function claimDedupe(key: string | undefined, now: number): boolean {
  if (!key) return true;
  const stored = recentDedupe.get(key);
  if (stored && stored.expiresAt > now) return false;
  recentDedupe.delete(key);
  recentDedupe.set(key, { expiresAt: now + DEDUPE_WINDOW_MS });
  while (recentDedupe.size > MAX_RECENT_DEDUPE) {
    const oldest = recentDedupe.keys().next().value as string | undefined;
    if (!oldest) break;
    recentDedupe.delete(oldest);
  }
  return true;
}

function buildEvent(event: ProductEvent, anonymousId: string, sessionId: string, now: number) {
  const identity = cleanIdentity(event);
  const quantity = cleanQuantity(event.quantity);
  if (!identity || (event.quantity !== undefined && quantity === undefined)) return null;
  const idempotencyKey = secureId("event");
  if (!idempotencyKey) return null;
  return {
    idempotencyKey,
    name: event.name,
    version: 1,
    source: "web",
    occurredAt: new Date(now).toISOString(),
    anonymousId,
    sessionId,
    properties: {
      ...identity,
      ...(quantity === undefined ? {} : { quantity }),
      ...(event.reason ? { reason: event.reason } : {}),
    },
    context: {
      app: "inkar_storefront",
      surface: event.surface,
    },
    consent: {
      scope: "first_party_product_measurement",
      marketing: false,
      optOut: false,
      doNotTrack: false,
    },
  };
}

function emitProductEvents(events: ProductEvent[]): boolean {
  if (privacyBlocksMeasurement() || events.length < 1 || events.length > MAX_BATCH_EVENTS) return false;
  const anonymousId = stableId(window.localStorage, INSTALL_ID_STORAGE_KEY, "install");
  const sessionId = stableId(window.sessionStorage, SESSION_ID_STORAGE_KEY, "session");
  if (!anonymousId || !sessionId) return false;

  const now = Date.now();
  const claimed = events.filter((event) => claimDedupe(event.dedupeKey, now));
  if (claimed.length < 1) return false;
  const payloadEvents = claimed
    .map((event) => buildEvent(event, anonymousId, sessionId, now))
    .filter((event): event is NonNullable<typeof event> => event !== null);
  if (payloadEvents.length < 1) return false;

  const body = JSON.stringify({ events: payloadEvents });
  if (new TextEncoder().encode(body).byteLength > MAX_BODY_BYTES) return false;
  try {
    void window.fetch("/api/events", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body,
      credentials: "same-origin",
      cache: "no-store",
      keepalive: true,
      redirect: "error",
      referrerPolicy: "same-origin",
    }).catch(() => undefined);
    return true;
  } catch {
    return false;
  }
}

export function setCdpOptOut(optedOut: boolean): void {
  if (typeof window === "undefined") return;
  try {
    if (optedOut) {
      window.localStorage.setItem(CDP_OPT_OUT_STORAGE_KEY, "1");
      window.localStorage.removeItem(INSTALL_ID_STORAGE_KEY);
      window.sessionStorage.removeItem(SESSION_ID_STORAGE_KEY);
      recentDedupe.clear();
    } else {
      window.localStorage.removeItem(CDP_OPT_OUT_STORAGE_KEY);
    }
  } catch {
    // Storage denial is itself treated as no stable identity, so events remain disabled.
  }
}

export function emitProductViewed(identity: ProductIdentity): boolean {
  const cleaned = cleanIdentity(identity);
  if (!cleaned) return false;
  return emitProductEvents([{
    ...cleaned,
    name: "product_viewed",
    surface: "pdp",
    dedupeKey: `product_viewed:${cleaned.productId}:${cleaned.variantId ?? "none"}`,
  }]);
}

export function emitCartItemAdded(
  identity: ProductIdentity,
  quantity: number,
  reason: Extract<CartReason, "add" | "quantity_increase"> = "add",
): boolean {
  return emitProductEvents([{
    ...identity,
    name: "cart_item_added",
    quantity,
    reason,
    surface: "cart_state",
  }]);
}

export function emitCartItemRemoved(
  identity: ProductIdentity,
  quantity: number,
  reason: Extract<CartReason, "remove" | "quantity_decrease" | "clear"> = "remove",
): boolean {
  return emitProductEvents([{
    ...identity,
    name: "cart_item_removed",
    quantity,
    reason,
    surface: "cart_state",
  }]);
}

export function emitCartItemsCleared(items: Array<ProductIdentity & { quantity: number }>): boolean {
  const bounded = items.slice(0, MAX_BATCH_EVENTS);
  return emitProductEvents(bounded.map((item) => ({
    ...item,
    name: "cart_item_removed",
    reason: "clear",
    surface: "cart_state",
  })));
}
