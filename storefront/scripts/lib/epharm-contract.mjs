import { createHash, createHmac, timingSafeEqual } from "node:crypto";

const STATUS_LABELS = Object.freeze({
  submitted: "Новый",
  assembling: "Собирается",
  ready: "Готов к выдаче",
  completed: "Получен",
  cancelled: "Отменён",
});

export function sha256Hex(value) {
  return createHash("sha256").update(value).digest("hex");
}
export function canonicalFulfillmentRequest(timestamp, method, pathAndQuery, body = "") {
  if (!Number.isSafeInteger(timestamp) || timestamp <= 0) throw new Error("timestamp_invalid");
  if (!/^[A-Z]+$/.test(String(method || "").toUpperCase())) throw new Error("method_invalid");
  if (!String(pathAndQuery || "").startsWith("/")) throw new Error("path_invalid");
  return `${timestamp}\n${String(method).toUpperCase()}\n${pathAndQuery}\n${sha256Hex(body)}`;
}

export function fulfillmentSignature(secret, timestamp, method, pathAndQuery, body = "") {
  if (Buffer.byteLength(String(secret || ""), "utf8") < 32) throw new Error("secret_too_short");
  return createHmac("sha256", secret)
    .update(canonicalFulfillmentRequest(timestamp, method, pathAndQuery, body), "utf8")
    .digest("hex");
}

export function fulfillmentHeaders(secret, method, pathAndQuery, body = "", now = Date.now()) {
  const timestamp = Math.floor(now / 1000);
  return {
    "X-Fulfillment-Timestamp": String(timestamp),
    "X-Fulfillment-Signature": fulfillmentSignature(secret, timestamp, method, pathAndQuery, body),
  };
}

export function constantTimeHexEqual(left, right) {
  if (!/^[0-9a-f]{64}$/i.test(String(left)) || !/^[0-9a-f]{64}$/i.test(String(right))) return false;
  return timingSafeEqual(Buffer.from(left, "hex"), Buffer.from(right, "hex"));
}

export function secureEpharmBaseUrl(value, allowPrivateHttp = false) {
  const url = new URL(String(value || "").trim());
  if (url.username || url.password || url.search || url.hash || url.pathname !== "/") {
    throw new Error("epharm_base_url_must_be_origin");
  }
  const privateHttp = url.protocol === "http:"
    && allowPrivateHttp
    && /^(localhost|127(?:\.\d{1,3}){3}|10(?:\.\d{1,3}){3}|192\.168(?:\.\d{1,3}){2}|172\.(?:1[6-9]|2\d|3[01])(?:\.\d{1,3}){2})$/i.test(url.hostname);
  if (url.protocol !== "https:" && !privateHttp) throw new Error("epharm_base_url_requires_https");
  return url.origin;
}

export function parseStartAt(value) {
  const normalized = String(value || "").trim();
  const milliseconds = Date.parse(normalized);
  if (!normalized || !Number.isFinite(milliseconds)) throw new Error("epharm_order_start_at_invalid");
  return new Date(milliseconds).toISOString();
}

export function retryDelaySeconds(attempts, random = Math.random()) {
  const exponent = Math.max(0, Math.min(8, Number(attempts || 1) - 1));
  return Math.min(1_800, 5 * (2 ** exponent) + Math.floor(Math.max(0, Math.min(0.999, random)) * 5));
}

export function storefrontStatus(status) {
  const normalized = String(status || "").trim().toLowerCase();
  const label = STATUS_LABELS[normalized];
  if (!label) throw new Error("fulfillment_status_invalid");
  return label;
}

export function validateUpdateFeed(value, after) {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("update_feed_invalid");
  const nextCursor = Number(value.nextCursor);
  if (!Number.isSafeInteger(nextCursor) || nextCursor < after) throw new Error("update_cursor_invalid");
  if (typeof value.hasMore !== "boolean" || !Array.isArray(value.updates) || value.updates.length > 200) {
    throw new Error("update_feed_invalid");
  }
  let previous = after;
  const updates = value.updates.map((raw) => {
    if (!raw || typeof raw !== "object" || Array.isArray(raw)) throw new Error("update_invalid");
    const cursor = Number(raw.cursor);
    const version = Number(raw.version);
    const orderId = String(raw.orderId || "").trim();
    const status = String(raw.status || "").trim().toLowerCase();
    if (!Number.isSafeInteger(cursor) || cursor <= previous || cursor > nextCursor) throw new Error("update_cursor_invalid");
    if (!Number.isSafeInteger(version) || version <= 0 || !orderId || orderId.length > 256) throw new Error("update_invalid");
    storefrontStatus(status);
    previous = cursor;
    return { cursor, orderId, status, version };
  });
  if (updates.length && updates.at(-1).cursor !== nextCursor) throw new Error("update_cursor_invalid");
  if (!updates.length && nextCursor !== after) throw new Error("update_cursor_invalid");
  return { nextCursor, hasMore: value.hasMore, updates };
}
