#!/usr/bin/env node

import { promises as fs } from "node:fs";
import process from "node:process";
import pg from "pg";
import {
  constantTimeHexEqual,
  fulfillmentHeaders,
  parseStartAt,
  retryDelaySeconds,
  secureEpharmBaseUrl,
  sha256Hex,
  storefrontStatus,
  validateUpdateFeed,
} from "./lib/epharm-contract.mjs";

const { Pool } = pg;
const ADVISORY_LOCK_ID = 4_930_511_109;
const HEARTBEAT = "/tmp/epharm-order-worker.heartbeat";
const SUCCESS_HEARTBEAT = "/tmp/epharm-order-worker.success";
const OUTBOUND_PATH = "/api/integrations/storefront/orders";
const MAX_RESPONSE_BYTES = 512 * 1024;

let stopping = false;
process.on("SIGTERM", () => { stopping = true; });
process.on("SIGINT", () => { stopping = true; });

function envValue(name, alias) {
  return process.env[name] ?? (alias ? process.env[alias] : undefined);
}

function booleanEnv(name, fallback = false, alias) {
  const value = String(envValue(name, alias) ?? fallback).trim().toLowerCase();
  if (value === "true" || value === "1") return true;
  if (value === "false" || value === "0") return false;
  throw new Error(`${name}_invalid`);
}

function integerEnv(name, fallback, minimum, maximum) {
  const value = Number(process.env[name] || fallback);
  if (!Number.isSafeInteger(value) || value < minimum || value > maximum) throw new Error(`${name}_invalid`);
  return value;
}

function required(name, alias) {
  const value = String(envValue(name, alias) || "").trim();
  if (!value) throw new Error(`${name}_required`);
  return value;
}

function postgresOptions(connectionString) {
  const parsed = new URL(connectionString);
  if (!new Set(["postgres:", "postgresql:"]).has(parsed.protocol)) throw new Error("DATABASE_URL_invalid");
  return {
    connectionString,
    max: 1,
    connectionTimeoutMillis: 5_000,
    idleTimeoutMillis: 30_000,
    statement_timeout: 20_000,
    application_name: "inkar-epharm-order-sync",
    ssl: parsed.searchParams.get("sslmode") === "require" ? { rejectUnauthorized: true } : undefined,
  };
}

function safeError(error) {
  return String(error instanceof Error ? error.message : error || "unknown_error")
    .replace(/[\r\n\t]+/g, " ")
    .replace(/\s+/g, " ")
    .slice(0, 500);
}

function wait(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

async function heartbeat() {
  await fs.writeFile(HEARTBEAT, new Date().toISOString(), { mode: 0o600 });
}

async function successfulCycleHeartbeat() {
  await fs.writeFile(SUCCESS_HEARTBEAT, new Date().toISOString(), { mode: 0o600 });
}

async function responseText(response) {
  const declared = Number(response.headers.get("content-length"));
  if (Number.isFinite(declared) && declared > MAX_RESPONSE_BYTES) throw new Error("response_too_large");
  const bytes = Buffer.from(await response.arrayBuffer());
  if (bytes.length > MAX_RESPONSE_BYTES) throw new Error("response_too_large");
  return bytes.toString("utf8");
}

async function signedFetch(config, pathAndQuery, method, body = "") {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), config.timeoutMs);
  try {
    return await fetch(`${config.baseUrl}${pathAndQuery}`, {
      method,
      body: method === "GET" ? undefined : body,
      headers: {
        Accept: "application/json",
        ...(method === "GET" ? {} : { "Content-Type": "application/json; charset=utf-8" }),
        ...fulfillmentHeaders(config.secret, method, pathAndQuery, body),
      },
      redirect: "error",
      cache: "no-store",
      signal: controller.signal,
    });
  } finally {
    clearTimeout(timer);
  }
}

async function diagnostic(client, direction, aggregateId, code, message) {
  await client.query(
    `INSERT INTO epharm_sync_diagnostics (direction, aggregate_id, error_code, error_message)
     VALUES ($1, $2, $3, $4)`,
    [direction, aggregateId || null, String(code).slice(0, 100), String(message).slice(0, 500)],
  );
}

async function claimOutbound(client, config) {
  await client.query("BEGIN");
  try {
    await client.query(`
      UPDATE integration_outbox
      SET status = 'failed', locked_at = NULL, available_at = now(),
          last_error = 'processing_lease_expired', updated_at = clock_timestamp()
      WHERE topic = 'epharm.order.created' AND status = 'processing'
        AND locked_at < now() - interval '5 minutes'
    `);
    const result = await client.query(`
      WITH candidates AS (
        SELECT id
        FROM integration_outbox
        WHERE topic = 'epharm.order.created'
          AND frozen_body IS NOT NULL
          AND frozen_sha256 IS NOT NULL
          AND created_at >= $1::timestamptz
          AND status IN ('pending', 'failed')
          AND available_at <= now()
        ORDER BY created_at, id
        LIMIT $2
        FOR UPDATE SKIP LOCKED
      )
      UPDATE integration_outbox o
      SET status = 'processing', attempts = o.attempts + 1,
          locked_at = clock_timestamp(), updated_at = clock_timestamp()
      FROM candidates c
      WHERE o.id = c.id
      RETURNING o.id::text, o.aggregate_id, o.frozen_body, o.frozen_sha256, o.attempts
    `, [config.startAt, config.batchSize]);
    await client.query("COMMIT");
    return result.rows;
  } catch (error) {
    await client.query("ROLLBACK").catch(() => undefined);
    throw error;
  }
}

async function markSent(client, row) {
  await client.query(`
    UPDATE integration_outbox
    SET status = 'sent', sent_at = clock_timestamp(), locked_at = NULL,
        last_error = NULL, updated_at = clock_timestamp()
    WHERE id = $1::uuid AND status = 'processing'
  `, [row.id]);
  await client.query(`
    UPDATE epharm_worker_state
    SET last_outbound_at = clock_timestamp(), last_error = NULL, updated_at = clock_timestamp()
    WHERE worker_key = 'order-updates'
  `);
}

async function markRetry(client, row, error) {
  const message = safeError(error);
  const delay = retryDelaySeconds(row.attempts);
  await client.query(`
    UPDATE integration_outbox
    SET status = 'failed', available_at = now() + make_interval(secs => $2),
        locked_at = NULL, last_error = $3, updated_at = clock_timestamp()
    WHERE id = $1::uuid AND status = 'processing'
  `, [row.id, delay, message]);
  await client.query(`
    UPDATE epharm_worker_state
    SET last_error = $1, last_error_at = clock_timestamp(), updated_at = clock_timestamp()
    WHERE worker_key = 'order-updates'
  `, [message]);
}

async function markTerminal(client, row, code, error) {
  const message = safeError(error);
  await client.query("BEGIN");
  try {
    await client.query(`
      UPDATE integration_outbox
      SET status = 'failed', available_at = 'infinity'::timestamptz,
          locked_at = NULL, last_error = $2, updated_at = clock_timestamp()
      WHERE id = $1::uuid AND status = 'processing'
    `, [row.id, message]);
    await diagnostic(client, "outbound", row.aggregate_id, code, message);
    await client.query(`
      UPDATE epharm_worker_state
      SET last_error = $1, last_error_at = clock_timestamp(), updated_at = clock_timestamp()
      WHERE worker_key = 'order-updates'
    `, [message]);
    await client.query("COMMIT");
  } catch (transactionError) {
    await client.query("ROLLBACK").catch(() => undefined);
    throw transactionError;
  }
}

async function dispatchOutbound(client, config, row) {
  if (!constantTimeHexEqual(sha256Hex(row.frozen_body), row.frozen_sha256)) {
    await markTerminal(client, row, "frozen_body_hash_mismatch", "frozen_body_hash_mismatch");
    return;
  }
  try {
    const response = await signedFetch(config, OUTBOUND_PATH, "POST", row.frozen_body);
    const body = await responseText(response);
    if (response.ok) {
      const ack = JSON.parse(body);
      if (!ack || String(ack.orderId || "") !== row.aggregate_id) throw new Error("order_ack_invalid");
      await markSent(client, row);
      return;
    }
    const message = `epharm_http_${response.status}`;
    if ([400, 404, 405, 409, 422].includes(response.status)) {
      await markTerminal(client, row, message, message);
    } else {
      await markRetry(client, row, message);
    }
  } catch (error) {
    await markRetry(client, row, error);
  }
}

async function outboundCycle(client, config) {
  const rows = await claimOutbound(client, config);
  for (const row of rows) {
    if (stopping) break;
    await dispatchOutbound(client, config, row);
    await heartbeat();
  }
  return rows.length;
}

async function currentCursor(client) {
  const result = await client.query(
    "SELECT cursor FROM epharm_worker_state WHERE worker_key = 'order-updates'",
  );
  if (result.rowCount !== 1) throw new Error("worker_state_missing");
  return Number(result.rows[0].cursor);
}

async function fetchUpdatePage(config, after) {
  const path = `/api/integrations/storefront/order-updates?after=${after}&limit=200`;
  const response = await signedFetch(config, path, "GET");
  const body = await responseText(response);
  if (!response.ok) throw new Error(`epharm_updates_http_${response.status}`);
  return validateUpdateFeed(JSON.parse(body), after);
}

async function applyUpdatePage(client, after, feed) {
  await client.query("BEGIN");
  try {
    const state = await client.query(
      "SELECT cursor FROM epharm_worker_state WHERE worker_key = 'order-updates' FOR UPDATE",
    );
    if (state.rowCount !== 1 || Number(state.rows[0].cursor) !== after) {
      throw new Error("worker_cursor_changed");
    }
    for (const update of feed.updates) {
      const result = await client.query(`
        UPDATE site_orders
        SET fulfillment_status = $2,
            fulfillment_version = $3,
            fulfillment_cursor = $4,
            status_version = CASE WHEN status <> $5 THEN status_version + 1 ELSE status_version END,
            status = $5,
            updated_at = clock_timestamp()
        WHERE id = $1 AND fulfillment_version < $3
        RETURNING id
      `, [
        update.orderId,
        update.status,
        update.version,
        update.cursor,
        storefrontStatus(update.status),
      ]);
      if (result.rowCount === 0) {
        const exists = await client.query("SELECT 1 FROM site_orders WHERE id = $1", [update.orderId]);
        if (exists.rowCount === 0) {
          await diagnostic(client, "inbound", update.orderId, "site_order_missing", "ePharm update has no local order");
        }
      }
    }
    await client.query(`
      UPDATE epharm_worker_state
      SET cursor = $1, last_inbound_at = clock_timestamp(), last_error = NULL,
          updated_at = clock_timestamp()
      WHERE worker_key = 'order-updates'
    `, [feed.nextCursor]);
    await client.query("COMMIT");
  } catch (error) {
    await client.query("ROLLBACK").catch(() => undefined);
    throw error;
  }
}

async function inboundCycle(client, config) {
  let pages = 0;
  let hasMore = true;
  while (hasMore && pages < 20 && !stopping) {
    const after = await currentCursor(client);
    const feed = await fetchUpdatePage(config, after);
    await applyUpdatePage(client, after, feed);
    await heartbeat();
    hasMore = feed.hasMore;
    pages += 1;
  }
  return pages;
}

function configFromEnvironment() {
  const enabled = booleanEnv("EPHARM_ORDER_SYNC_ENABLED", false, "EPHARM_FULFILLMENT_ENABLED");
  if (!enabled) return { enabled, pollMs: 30_000 };
  const secret = required("EPHARM_FULFILLMENT_SHARED_SECRET", "EPHARM_SHARED_SECRET");
  if (Buffer.byteLength(secret, "utf8") < 32) throw new Error("EPHARM_FULFILLMENT_SHARED_SECRET_too_short");
  return {
    enabled,
    secret,
    baseUrl: secureEpharmBaseUrl(
      required("EPHARM_BASE_URL"),
      booleanEnv("EPHARM_ALLOW_PRIVATE_HTTP", false),
    ),
    startAt: parseStartAt(required("EPHARM_ORDER_START_AT")),
    timeoutMs: integerEnv("EPHARM_SYNC_TIMEOUT_MS", 15_000, 1_000, 60_000),
    pollMs: integerEnv("EPHARM_SYNC_POLL_SEC", 10, 5, 300) * 1_000,
    batchSize: integerEnv("EPHARM_SYNC_BATCH_SIZE", 20, 1, 20),
  };
}

async function main() {
  const config = configFromEnvironment();
  if (!config.enabled) {
    console.log("[epharm-orders] disabled; waiting for an explicit production enablement");
    while (!stopping) {
      await heartbeat();
      await wait(config.pollMs);
    }
    return;
  }

  const pool = new Pool(postgresOptions(required("DATABASE_URL")));
  let client;
  try {
    client = await pool.connect();
    const lock = await client.query("SELECT pg_try_advisory_lock($1) AS acquired", [ADVISORY_LOCK_ID]);
    if (lock.rows[0]?.acquired !== true) throw new Error("epharm_order_worker_already_running");
    console.log(`[epharm-orders] started; sending only orders created at or after ${config.startAt}`);
    await heartbeat();
    while (!stopping) {
      try {
        await outboundCycle(client, config);
        await inboundCycle(client, config);
        await successfulCycleHeartbeat();
      } catch (error) {
        const message = safeError(error);
        console.error(`[epharm-orders] cycle failed: ${message}`);
        await client.query(`
          UPDATE epharm_worker_state
          SET last_error = $1, last_error_at = clock_timestamp(), updated_at = clock_timestamp()
          WHERE worker_key = 'order-updates'
        `, [message]).catch(() => undefined);
      }
      await heartbeat();
      if (!stopping) await wait(config.pollMs);
    }
  } finally {
    client?.release();
    await pool.end().catch(() => undefined);
  }
}

main().catch((error) => {
  console.error(`[epharm-orders] fatal: ${safeError(error)}`);
  process.exitCode = 1;
});
