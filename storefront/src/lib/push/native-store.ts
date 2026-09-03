import { createHash, randomUUID } from "node:crypto";
import type { Pool } from "pg";
import type { DeliveryResult, PushAudience, PushPreferences } from "./types";

export type NativePushDevice = {
  id: string;
  customerId: string;
  installationId: string;
  platform: "ios";
  token: string;
  preferences: PushPreferences;
  locale?: string;
  city?: string;
  appVersion?: string;
};

type NativePushRow = {
  id: string;
  customer_id: string;
  installation_id: string;
  platform: "ios";
  token: string;
  orders_enabled: boolean;
  promos_enabled: boolean;
  locale: string | null;
  city: string | null;
  app_version: string | null;
};

type Runtime = { connectionString: string; poolPromise: Promise<Pool> | null };
const root = globalThis as typeof globalThis & { __inkarNativePushDatabase?: Runtime };

function connectionString(): string {
  const value = String(process.env.DATABASE_URL || process.env.POSTGRES_URL || "").trim();
  if (!value) throw new Error("native_push_database_not_configured");
  return value;
}

async function pool(): Promise<Pool> {
  const databaseUrl = connectionString();
  let runtime = root.__inkarNativePushDatabase;
  if (!runtime || runtime.connectionString !== databaseUrl) {
    runtime = { connectionString: databaseUrl, poolPromise: null };
    root.__inkarNativePushDatabase = runtime;
  }
  if (!runtime.poolPromise) {
    runtime.poolPromise = (async () => {
      const { Pool: PgPool } = await import("pg");
      const ssl = /sslmode=require|neon\.tech|supabase|render\.com|amazonaws\.com/i.test(databaseUrl)
        ? { rejectUnauthorized: true }
        : undefined;
      return new PgPool({
        connectionString: databaseUrl,
        ssl,
        max: 4,
        idleTimeoutMillis: 30_000,
        connectionTimeoutMillis: 5_000,
        statement_timeout: 10_000,
        application_name: "inkar-native-push",
      });
    })().catch((error) => {
      runtime!.poolPromise = null;
      throw error;
    });
  }
  return runtime.poolPromise;
}

function hash(token: string): string {
  return createHash("sha256").update(token, "utf8").digest("hex");
}

function rowToDevice(row: NativePushRow): NativePushDevice {
  return {
    id: row.id,
    customerId: row.customer_id,
    installationId: row.installation_id,
    platform: row.platform,
    token: row.token,
    preferences: { orders: row.orders_enabled, promos: row.promos_enabled },
    locale: row.locale || undefined,
    city: row.city || undefined,
    appVersion: row.app_version || undefined,
  };
}

export async function upsertNativeDevice(input: {
  customerId: string;
  installationId: string;
  platform: "ios";
  token: string;
  preferences: PushPreferences;
  locale?: string;
  city?: string;
  appVersion?: string;
}): Promise<NativePushDevice> {
  const db = await pool();
  await db.query(`
    DELETE FROM native_push_devices
    WHERE customer_id = $1
      AND installation_id = $2::uuid
      AND platform = $3
      AND token_hash <> $4
  `, [input.customerId, input.installationId, input.platform, hash(input.token)]);
  const result = await db.query<NativePushRow>(`
    INSERT INTO native_push_devices (
      id, customer_id, installation_id, platform, token, token_hash,
      orders_enabled, promos_enabled, locale, city, app_version
    ) VALUES ($1, $2, $3::uuid, $4, $5, $6, $7, $8, $9, $10, $11)
    ON CONFLICT (token_hash) DO UPDATE SET
      customer_id = excluded.customer_id,
      installation_id = excluded.installation_id,
      platform = excluded.platform,
      token = excluded.token,
      orders_enabled = excluded.orders_enabled,
      promos_enabled = excluded.promos_enabled,
      locale = excluded.locale,
      city = excluded.city,
      app_version = excluded.app_version,
      failure_count = 0,
      last_error = NULL,
      revoked_at = NULL,
      revoke_reason = NULL,
      updated_at = now()
    RETURNING id, customer_id, installation_id, platform, token,
      orders_enabled, promos_enabled, locale, city, app_version
  `, [
    randomUUID(), input.customerId, input.installationId, input.platform,
    input.token, hash(input.token), input.preferences.orders, input.preferences.promos,
    input.locale || null, input.city || null, input.appVersion || null,
  ]);
  return rowToDevice(result.rows[0]);
}

export async function revokeNativeDevice(customerId: string, token: string): Promise<boolean> {
  const db = await pool();
  const result = await db.query(`
    UPDATE native_push_devices
    SET revoked_at = now(), revoke_reason = 'client_unregistered', updated_at = now()
    WHERE customer_id = $1 AND token_hash = $2 AND revoked_at IS NULL
  `, [customerId, hash(token)]);
  return (result.rowCount || 0) > 0;
}

export async function nativeDevicesForAudience(audience: PushAudience): Promise<NativePushDevice[]> {
  const db = await pool();
  const condition = audience === "promos"
    ? "AND promos_enabled"
    : audience === "orders" ? "AND orders_enabled" : "";
  const result = await db.query<NativePushRow>(`
    SELECT id, customer_id, installation_id, platform, token,
      orders_enabled, promos_enabled, locale, city, app_version
    FROM native_push_devices
    WHERE revoked_at IS NULL ${condition}
    ORDER BY updated_at DESC
  `);
  return result.rows.map(rowToDevice);
}

export async function nativeDevicesForOrder(orderNumber: string): Promise<NativePushDevice[]> {
  const db = await pool();
  const result = await db.query<NativePushRow>(`
    SELECT DISTINCT device.id, device.customer_id, device.installation_id,
      device.platform, device.token, device.orders_enabled,
      device.promos_enabled, device.locale, device.city, device.app_version
    FROM native_push_devices device
    JOIN site_orders owned_order ON owned_order.customer_id = device.customer_id
    WHERE device.revoked_at IS NULL
      AND device.orders_enabled
      AND (
        owned_order.id = $1
        OR owned_order.source_order_id = $1
        OR owned_order.display_number::text = $1
      )
  `, [orderNumber.slice(0, 160)]);
  return result.rows.map(rowToDevice);
}

export async function applyNativeDeliveryResults(results: DeliveryResult[]): Promise<void> {
  if (!results.length) return;
  const db = await pool();
  for (const result of results) {
    await db.query(`
      UPDATE native_push_devices SET
        last_sent_at = now(),
        last_success_at = CASE WHEN $2 THEN now() ELSE last_success_at END,
        failure_count = CASE WHEN $2 THEN 0 ELSE failure_count + 1 END,
        last_error = CASE WHEN $2 THEN NULL ELSE $3 END,
        revoked_at = CASE WHEN $4 THEN now() ELSE revoked_at END,
        revoke_reason = CASE WHEN $4 THEN 'apns_token_rejected' ELSE revoke_reason END,
        updated_at = now()
      WHERE id = $1::uuid
    `, [result.endpoint, result.ok, result.error?.slice(0, 300) || null, result.revoked]);
  }
}

export async function nativePushStats(): Promise<{
  total: number;
  active: number;
  orders: number;
  promos: number;
}> {
  const db = await pool();
  const result = await db.query<{
    total: string;
    active: string;
    orders: string;
    promos: string;
  }>(`
    SELECT
      count(*) AS total,
      count(*) FILTER (WHERE revoked_at IS NULL) AS active,
      count(*) FILTER (WHERE revoked_at IS NULL AND orders_enabled) AS orders,
      count(*) FILTER (WHERE revoked_at IS NULL AND promos_enabled) AS promos
    FROM native_push_devices
  `);
  const row = result.rows[0];
  return {
    total: Number(row.total || 0),
    active: Number(row.active || 0),
    orders: Number(row.orders || 0),
    promos: Number(row.promos || 0),
  };
}
