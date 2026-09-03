import { randomInt, randomUUID } from "node:crypto";
import { promises as fs } from "node:fs";
import path from "node:path";
import type { Pool, PoolClient } from "pg";
import { ORDER_STATUSES, type Order } from "@/lib/content/defaults";
import { orderCreatedOutboxKey, ordersStorageMode } from "./model";
import { buildEpharmOrderCreated, frozenOrderBody, requiresEpharmFulfillment } from "./epharmContract";

export { orderCreatedOutboxKey, ordersStorageMode, orderStatusOutboxKey } from "./model";

export type StoredOrder = Order & {
  createdAt: string;
  idempotencyKey: string;
  sourceSystem: "medusa" | "storefront";
  sourceOrderId: string;
  customerId?: string;
  demo?: boolean;
  metadata?: Record<string, unknown>;
  statusVersion: number;
  fulfillmentStatus?: "submitted" | "assembling" | "ready" | "completed" | "cancelled";
  fulfillmentVersion?: number;
  fulfillmentCursor?: number;
};

type CreateOrderInput = {
  id: string;
  sourceSystem: StoredOrder["sourceSystem"];
  sourceOrderId: string;
  idempotencyKey: string;
  n: number;
  createdAt: string;
  sum: number;
  status: string;
  items: number;
  code?: string;
  delivery: string;
  currencyCode?: string;
  customerId?: string;
  demo?: boolean;
  metadata?: Record<string, unknown>;
};

type SiteOrderRow = {
  id: string;
  source_system: StoredOrder["sourceSystem"];
  source_order_id: string;
  idempotency_key: string;
  display_number: number;
  placed_at: Date | string;
  total_amount: number | string;
  currency_code: string;
  status: string;
  status_version: number;
  item_count: number;
  pickup_code: string | null;
  delivery_method: string;
  customer_id: string | null;
  is_demo: boolean;
  metadata: Record<string, unknown> | null;
  fulfillment_status?: StoredOrder["fulfillmentStatus"] | null;
  fulfillment_version?: number | string;
  fulfillment_cursor?: number | string;
};

export type CompletedMedusaOrderLike = {
  id?: unknown;
  display_id?: unknown;
  created_at?: unknown;
  total?: unknown;
  currency_code?: unknown;
  customer_id?: unknown;
  cart_id?: unknown;
  items?: unknown;
};

export type RecordCompletedMedusaOrderInput = {
  order: CompletedMedusaOrderLike;
  delivery: string;
  fallbackTotal?: number;
  fallbackItems: Array<{
    product_id?: string;
    variant_id: string;
    sku?: string;
    quantity: number;
    unit_price?: number;
  }>;
  customerId?: string;
  demo?: boolean;
  metadata?: Record<string, unknown>;
};

export type RecordCompletedStorefrontOrderInput = {
  idempotencyKey: string;
  total: number;
  delivery: string;
  items: Array<{
    product_id?: string;
    variant_id: string;
    quantity: number;
    unit_price?: number;
  }>;
  customerId?: string;
  demo?: boolean;
  metadata?: Record<string, unknown>;
};

const DATA_DIR = path.join(process.cwd(), ".data");
const FILE = path.join(DATA_DIR, "orders.json");
const MAX_ORDERS = 500;
const MAX_METADATA_BYTES = 256 * 1024;
let fileCache: StoredOrder[] | null = null;
let fileWriteChain: Promise<void> = Promise.resolve();

type Runtime = { poolPromise: Promise<Pool> | null };
const runtimeRoot = globalThis as typeof globalThis & { __inkarOrdersDatabase?: Runtime };
const runtime = runtimeRoot.__inkarOrdersDatabase ??= { poolPromise: null };

function databaseUrl(): string {
  return String(process.env.DATABASE_URL || process.env.POSTGRES_URL || "").trim();
}

function text(value: unknown, maxLength: number): string {
  return String(value ?? "").trim().slice(0, maxLength);
}

function safeInteger(value: unknown, fallback = 0): number {
  const parsed = Math.round(Number(value));
  return Number.isSafeInteger(parsed) && parsed >= 0 ? parsed : fallback;
}

function safeMoney(value: unknown): number | null {
  const number = Number(value);
  const cents = Math.round(number * 100);
  if (!Number.isFinite(number) || number < 0 || !Number.isSafeInteger(cents)) return null;
  if (Math.abs(number - cents / 100) > 1e-9) return null;
  return cents / 100;
}

function requiredMoney(value: unknown): number {
  const normalized = safeMoney(value);
  if (normalized === null) throw new Error("invalid_stored_order_amount");
  return normalized;
}

function snapshotMoney(value: unknown): number | null | "invalid" {
  if (value === null || value === undefined || value === "") return null;
  return safeMoney(value) ?? "invalid";
}

function safeDate(value: unknown): string {
  const date = value ? new Date(String(value)) : new Date();
  return Number.isFinite(date.valueOf()) ? date.toISOString() : new Date().toISOString();
}

function safeMetadata(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) return {};
  const serialized = JSON.stringify(value);
  if (Buffer.byteLength(serialized, "utf8") > MAX_METADATA_BYTES) {
    throw new Error("order_metadata_too_large");
  }
  return JSON.parse(serialized) as Record<string, unknown>;
}

function rowToOrder(row: SiteOrderRow): StoredOrder {
  const createdAt = safeDate(row.placed_at);
  return {
    id: row.id,
    n: Number(row.display_number),
    date: new Intl.DateTimeFormat("ru-RU", {
      day: "2-digit",
      month: "2-digit",
      year: "numeric",
    }).format(new Date(createdAt)),
    sum: Number(row.total_amount),
    status: row.status,
    items: Number(row.item_count),
    ...(row.pickup_code ? { code: row.pickup_code } : {}),
    delivery: row.delivery_method,
    createdAt,
    idempotencyKey: row.idempotency_key,
    sourceSystem: row.source_system,
    sourceOrderId: row.source_order_id,
    ...(row.customer_id ? { customerId: row.customer_id } : {}),
    ...(row.is_demo ? { demo: true } : {}),
    ...(row.metadata && Object.keys(row.metadata).length ? { metadata: row.metadata } : {}),
    statusVersion: Number(row.status_version),
    ...(row.fulfillment_status ? { fulfillmentStatus: row.fulfillment_status } : {}),
    ...(row.fulfillment_version !== undefined ? { fulfillmentVersion: Number(row.fulfillment_version) } : {}),
    ...(row.fulfillment_cursor !== undefined ? { fulfillmentCursor: Number(row.fulfillment_cursor) } : {}),
  };
}

async function databasePool(): Promise<Pool> {
  const connectionString = databaseUrl();
  if (!connectionString) throw new Error("orders_database_not_configured");
  if (!runtime.poolPromise) {
    runtime.poolPromise = (async () => {
      const parsed = new URL(connectionString);
      if (!["postgres:", "postgresql:"].includes(parsed.protocol)) {
        throw new Error("orders_database_url_invalid");
      }
      const { Pool: PgPool } = await import("pg");
      const ssl = /sslmode=require|neon\.tech|supabase|render\.com|amazonaws\.com/i.test(connectionString)
        ? { rejectUnauthorized: true }
        : undefined;
      const pool = new PgPool({
        connectionString,
        ssl,
        max: 4,
        idleTimeoutMillis: 30_000,
        connectionTimeoutMillis: 5_000,
        statement_timeout: 10_000,
        application_name: "inkar-orders",
      });
      pool.on("error", (error) => console.error("[orders/database] idle client error", error));
      return pool;
    })().catch((error) => {
      runtime.poolPromise = null;
      throw error;
    });
  }
  return runtime.poolPromise;
}

async function fileLoad(): Promise<StoredOrder[]> {
  if (fileCache) return fileCache;
  try {
    const parsed = JSON.parse(await fs.readFile(FILE, "utf8"));
    fileCache = Array.isArray(parsed) ? parsed : [];
  } catch {
    fileCache = [];
  }
  return fileCache;
}

async function filePersist(orders: StoredOrder[]): Promise<void> {
  fileCache = orders;
  fileWriteChain = fileWriteChain.then(async () => {
    await fs.mkdir(DATA_DIR, { recursive: true });
    await fs.writeFile(FILE, JSON.stringify(orders, null, 2), "utf8");
  });
  await fileWriteChain;
}

async function fileCreateOrder(input: CreateOrderInput): Promise<StoredOrder> {
  const orders = await fileLoad();
  const existing = orders.find((order) => order.idempotencyKey === input.idempotencyKey
    || (order.sourceSystem === input.sourceSystem && order.sourceOrderId === input.sourceOrderId));
  if (existing) return existing;
  const row: SiteOrderRow = {
    id: input.id,
    source_system: input.sourceSystem,
    source_order_id: input.sourceOrderId,
    idempotency_key: input.idempotencyKey,
    display_number: input.n,
    placed_at: input.createdAt,
    total_amount: input.sum,
    currency_code: input.currencyCode || "kzt",
    status: input.status,
    status_version: 1,
    item_count: input.items,
    pickup_code: input.code || null,
    delivery_method: input.delivery,
    customer_id: input.customerId || null,
    is_demo: input.demo === true,
    metadata: input.metadata || {},
  };
  const order = rowToOrder(row);
  await filePersist([order, ...orders].slice(0, MAX_ORDERS));
  return order;
}

async function insertOutbox(
  client: PoolClient,
  topic: "epharm.order.created" | "epharm.order.status_changed",
  row: SiteOrderRow,
  idempotencyKey: string,
  payload: Record<string, unknown>,
): Promise<void> {
  const id = randomUUID();
  const created = topic === "epharm.order.created" ? buildEpharmOrderCreated(row, id) : null;
  // Warehouse/courier orders without an explicitly selected pharmacy are not cashier work.
  // Invalid product snapshots stay local and diagnosable instead of leaking a partial order.
  if (topic === "epharm.order.created" && !created) {
    if (requiresEpharmFulfillment(row)) {
      throw new Error("epharm_order_event_invalid");
    }
    console.warn("[orders/outbox] order is not eligible for pharmacy fulfillment", { orderId: row.id });
    return;
  }
  const frozen = created ? frozenOrderBody(created) : null;
  await client.query(`
    INSERT INTO integration_outbox (
      id, topic, aggregate_type, aggregate_id, payload, idempotency_key,
      frozen_body, frozen_sha256
    ) VALUES ($1, $2, 'site_order', $3, $4::jsonb, $5, $6, $7)
    ON CONFLICT (idempotency_key) DO NOTHING
  `, [
    id,
    topic,
    row.id,
    JSON.stringify(created || payload),
    idempotencyKey,
    frozen?.body || null,
    frozen?.sha256 || null,
  ]);
}

async function postgresCreateOrder(input: CreateOrderInput): Promise<StoredOrder> {
  const db = await databasePool();
  const client = await db.connect();
  try {
    await client.query("BEGIN");
    const inserted = await client.query<SiteOrderRow>(`
      INSERT INTO site_orders (
        id, source_system, source_order_id, idempotency_key, display_number,
        placed_at, total_amount, currency_code, status, item_count, pickup_code,
        delivery_method, customer_id, is_demo, metadata
      ) VALUES (
        $1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15::jsonb
      )
      ON CONFLICT DO NOTHING
      RETURNING *
    `, [
      input.id, input.sourceSystem, input.sourceOrderId, input.idempotencyKey,
      input.n, input.createdAt, input.sum, input.currencyCode || "kzt", input.status,
      input.items, input.code || null, input.delivery, input.customerId || null,
      input.demo === true, JSON.stringify(input.metadata || {}),
    ]);

    let row = inserted.rows[0];
    if (!row) {
      const existing = await client.query<SiteOrderRow>(`
        SELECT *
        FROM site_orders
        WHERE id = $1
           OR idempotency_key = $2
           OR (source_system = $3 AND source_order_id = $4)
        FOR UPDATE
      `, [input.id, input.idempotencyKey, input.sourceSystem, input.sourceOrderId]);
      if (existing.rowCount !== 1) throw new Error("order_idempotency_conflict");
      row = existing.rows[0];
      const sameIdempotencyKey = row.idempotency_key === input.idempotencyKey;
      const sameSourceOrder = row.source_system === input.sourceSystem
        && row.source_order_id === input.sourceOrderId;
      if (!sameIdempotencyKey && !sameSourceOrder) throw new Error("order_idempotency_conflict");
    }

    await insertOutbox(
      client,
      "epharm.order.created",
      row,
      orderCreatedOutboxKey(row.id),
      {},
    );
    await client.query("COMMIT");
    return rowToOrder(row);
  } catch (error) {
    try {
      await client.query("ROLLBACK");
    } catch (rollbackError) {
      console.error("[orders/database] create rollback failed", rollbackError);
    }
    throw error;
  } finally {
    client.release();
  }
}

async function persistOrder(input: CreateOrderInput): Promise<StoredOrder> {
  const normalized: CreateOrderInput = {
    ...input,
    id: text(input.id, 256),
    sourceOrderId: text(input.sourceOrderId, 256),
    idempotencyKey: text(input.idempotencyKey, 512),
    n: safeInteger(input.n),
    createdAt: safeDate(input.createdAt),
    sum: requiredMoney(input.sum),
    status: text(input.status, 100),
    items: safeInteger(input.items),
    code: text(input.code, 100) || undefined,
    delivery: text(input.delivery, 100),
    currencyCode: text(input.currencyCode || "kzt", 3).toLowerCase(),
    customerId: text(input.customerId, 256) || undefined,
    metadata: safeMetadata(input.metadata),
  };
  if (!normalized.id || !normalized.sourceOrderId || !normalized.idempotencyKey
      || !normalized.status || !normalized.delivery || normalized.items < 1) {
    throw new Error("invalid_stored_order");
  }
  return ordersStorageMode(process.env.DATABASE_URL, process.env.POSTGRES_URL) === "postgres"
    ? postgresCreateOrder(normalized)
    : fileCreateOrder(normalized);
}

function medusaLineItems(value: unknown): Array<Record<string, unknown>> {
  if (!Array.isArray(value)) return [];
  return value.slice(0, 200).map((raw) => {
    const item = raw && typeof raw === "object" ? raw as Record<string, unknown> : {};
    return {
      id: text(item.id, 256),
      product_id: text(item.product_id, 256),
      variant_id: text(item.variant_id, 256),
      title: text(item.product_title || item.title, 500),
      quantity: Math.max(1, safeInteger(item.quantity, 1)),
      unit_price: snapshotMoney(item.unit_price),
      total: snapshotMoney(item.total),
    };
  });
}

/**
 * Durable hook for a successfully completed Medusa order. Call this only after
 * `/store/carts/:id/complete` returns `type=order`; it never calls Medusa.
 */
export async function recordCompletedMedusaOrder(input: RecordCompletedMedusaOrderInput): Promise<StoredOrder> {
  const orderId = text(input.order.id, 256);
  if (!orderId) throw new Error("medusa_order_id_missing");
  const lines = medusaLineItems(input.order.items);
  const fallbackLines = input.fallbackItems.slice(0, 200).map((item) => ({
    product_id: text(item.product_id, 256),
    variant_id: text(item.variant_id, 256),
    sku: text(item.sku, 256),
    quantity: Math.max(1, safeInteger(item.quantity, 1)),
    unit_price: snapshotMoney(item.unit_price),
  }));
  const itemCount = lines.length
    ? lines.reduce((sum, item) => sum + safeInteger(item.quantity), 0)
    : fallbackLines.reduce((sum, item) => sum + safeInteger(item.quantity), 0);
  const metadata = safeMetadata({
    ...(input.metadata || {}),
    currency_code: text(input.order.currency_code || "kzt", 3).toLowerCase(),
    medusa_cart_id: text(input.order.cart_id, 256) || undefined,
    line_items: lines.length ? lines : fallbackLines,
  });
  return persistOrder({
    id: orderId,
    sourceSystem: "medusa",
    sourceOrderId: orderId,
    idempotencyKey: `medusa:${orderId}`,
    n: safeInteger(input.order.display_id),
    createdAt: safeDate(input.order.created_at),
    sum: safeMoney(input.order.total) ?? safeMoney(input.fallbackTotal) ?? Number.NaN,
    status: ORDER_STATUSES[0],
    items: itemCount,
    code: String(randomInt(100000, 1000000)),
    delivery: input.delivery,
    currencyCode: text(input.order.currency_code || "kzt", 3).toLowerCase(),
    customerId: text(input.order.customer_id || input.customerId, 256) || undefined,
    demo: input.demo === true,
    metadata,
  });
}

/**
 * Persists a site-native checkout which deliberately did not create a Medusa
 * cart/order (currently the signed demo flow). The caller must supply a total
 * obtained from the server-side quote service; this function never accepts a
 * client subtotal.
 */
export async function recordCompletedStorefrontOrder(
  input: RecordCompletedStorefrontOrderInput,
): Promise<StoredOrder> {
  const id = randomUUID();
  const lines = input.items.slice(0, 200).map((item) => ({
    product_id: text(item.product_id, 256),
    variant_id: text(item.variant_id, 256),
    quantity: Math.max(1, safeInteger(item.quantity, 1)),
    unit_price: snapshotMoney(item.unit_price),
  }));
  const itemCount = lines.reduce((sum, item) => sum + item.quantity, 0);
  const metadata = safeMetadata({
    ...(input.metadata || {}),
    currency_code: "kzt",
    line_items: lines,
  });
  return persistOrder({
    id,
    sourceSystem: "storefront",
    sourceOrderId: id,
    idempotencyKey: input.idempotencyKey,
    n: randomInt(100000, 1000000),
    createdAt: new Date().toISOString(),
    sum: input.total,
    status: ORDER_STATUSES[0],
    items: itemCount,
    code: String(randomInt(100000, 1000000)),
    delivery: input.delivery,
    currencyCode: "kzt",
    customerId: text(input.customerId, 256) || undefined,
    demo: input.demo === true,
    metadata,
  });
}

export async function listOrders(): Promise<StoredOrder[]> {
  if (ordersStorageMode(process.env.DATABASE_URL, process.env.POSTGRES_URL) === "file") {
    return [...(await fileLoad())];
  }
  const db = await databasePool();
  const result = await db.query<SiteOrderRow>(`
    SELECT *
    FROM site_orders
    ORDER BY placed_at DESC, id DESC
    LIMIT $1
  `, [MAX_ORDERS]);
  return result.rows.map(rowToOrder);
}
export async function listCustomerOrders(
  customerId: string,
  limit = 100,
): Promise<StoredOrder[]> {
  const normalizedCustomerId = text(customerId, 256);
  if (!normalizedCustomerId) return [];
  const safeLimit = Math.max(1, Math.min(100, safeInteger(limit, 100)));
  if (ordersStorageMode(process.env.DATABASE_URL, process.env.POSTGRES_URL) === "file") {
    return (await fileLoad())
      .filter((order) => order.customerId === normalizedCustomerId)
      .slice(0, safeLimit);
  }
  const db = await databasePool();
  const result = await db.query<SiteOrderRow>(`
    SELECT *
    FROM site_orders
    WHERE customer_id = $1
    ORDER BY placed_at DESC, id DESC
    LIMIT $2
  `, [normalizedCustomerId, safeLimit]);
  return result.rows.map(rowToOrder);
}

/** Legacy isolated order endpoint; normal checkout persists a completed Medusa order instead. */
export async function createOrder(input: {
  sum: number;
  items: number;
  delivery: string;
  idempotencyKey: string;
}): Promise<StoredOrder> {
  const id = randomUUID();
  return persistOrder({
    id,
    sourceSystem: "storefront",
    sourceOrderId: id,
    idempotencyKey: input.idempotencyKey,
    n: randomInt(100000, 1000000),
    createdAt: new Date().toISOString(),
    sum: input.sum,
    status: input.delivery === "pickup" ? ORDER_STATUSES[3] : ORDER_STATUSES[1],
    items: input.items,
    code: String(randomInt(100000, 1000000)),
    delivery: input.delivery,
  });
}

async function postgresUpdateOrderStatus(id: string, status: string): Promise<StoredOrder | null> {
  const db = await databasePool();
  const client = await db.connect();
  try {
    await client.query("BEGIN");
    const current = await client.query<SiteOrderRow>(
      "SELECT * FROM site_orders WHERE id = $1 FOR UPDATE",
      [id],
    );
    if (!current.rows[0]) {
      await client.query("COMMIT");
      return null;
    }
    if (current.rows[0].status === status) {
      await client.query("COMMIT");
      return rowToOrder(current.rows[0]);
    }
    const updated = await client.query<SiteOrderRow>(`
      UPDATE site_orders
      SET status = $2,
          status_version = status_version + 1,
          updated_at = clock_timestamp()
      WHERE id = $1
      RETURNING *
    `, [id, status]);
    const row = updated.rows[0];
    // ePharm owns fulfilment transitions. Store status changes are read-model updates only
    // and must never create a reverse command that could bypass its state machine.
    await client.query("COMMIT");
    return rowToOrder(row);
  } catch (error) {
    try {
      await client.query("ROLLBACK");
    } catch (rollbackError) {
      console.error("[orders/database] status rollback failed", rollbackError);
    }
    throw error;
  } finally {
    client.release();
  }
}

async function fileUpdateOrderStatus(id: string, status: string): Promise<StoredOrder | null> {
  const orders = await fileLoad();
  const index = orders.findIndex((order) => order.id === id);
  if (index < 0) return null;
  if (orders[index].status === status) return orders[index];
  const next = [...orders];
  next[index] = {
    ...next[index],
    status,
    statusVersion: safeInteger(next[index].statusVersion, 1) + 1,
  };
  await filePersist(next);
  return next[index];
}

export async function updateOrderStatus(id: string, status: string): Promise<StoredOrder | null> {
  const orderId = text(id, 256);
  const nextStatus = text(status, 100);
  if (!orderId || !nextStatus) return null;
  return ordersStorageMode(process.env.DATABASE_URL, process.env.POSTGRES_URL) === "postgres"
    ? postgresUpdateOrderStatus(orderId, nextStatus)
    : fileUpdateOrderStatus(orderId, nextStatus);
}
