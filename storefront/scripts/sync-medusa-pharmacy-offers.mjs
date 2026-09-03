#!/usr/bin/env node

/**
 * Incrementally cache the public Medusa per-pharmacy price snapshots.
 *
 * Product listing calculated_price is empty for this sales channel. This
 * worker keeps Postgres warm without issuing ~28k requests on a user path.
 * Missing/oldest checkpoints are processed first, so runs resume naturally.
 */

import process from "node:process";
import pg from "pg";
import {
  normalizePharmacyPayload,
  offerSyncExitCode,
  parsePharmacyBatchPayload,
  parseOfferSyncArgs,
  retryableStatus,
  retryableTransactionError,
  transactionRetryDelayMs,
} from "./lib/medusa-offer-sync.mjs";
import {
  attachClickHouseWarehouseResults,
  clickHouseArrayLiteral,
  normalizeClickHouseOfferRows,
  normalizeClickHouseWarehouseRows,
  normalizeExactIdentifier,
  resolveProductMappings,
  secureClickHouseUrl,
  validateClickHouseTable,
} from "./lib/clickhouse-offer-sync.mjs";
import { secureMedusaUrl } from "./lib/secure-medusa-url.mjs";

const { Client } = pg;
// Shared with sync-medusa-catalog.mjs so both scans cannot load Medusa at once.
const ADVISORY_LOCK_ID = 4_930_511_108;
const TRANSACTION_ATTEMPTS = 5;

function usage() {
  return `
Usage: node scripts/sync-medusa-pharmacy-offers.mjs [options]

  --apply                       Atomically replace valid product snapshots
  --full                        Backfill every active catalog product
  --source <medusa|clickhouse>  Price/stock source (default: env or medusa)
  --limit <1..100000>           Products per resumable run (default: 300)
  --concurrency <1..8>          Concurrent Medusa requests (default: 3)
  --batch-size <1..100>         Products per Medusa batch request (default: 100)
  --timeout-ms <1000..60000>    Per-attempt timeout (default: 10000)
  --attempts <1..5>             Attempts per product (default: 2)
  --max-pharmacies <1..10000>   Reject unexpectedly large snapshots
  --max-response-bytes <n>      Response body safety limit
  --batch-delay-ms <0..10000>   Pause between request batches

DATABASE_URL is required. Medusa mode also requires MEDUSA_URL and
MEDUSA_PUBLISHABLE_KEY. ClickHouse mode requires CLICKHOUSE_URL,
CLICKHOUSE_USER and CLICKHOUSE_PASSWORD. Options can also be set with
CATALOG_OFFER_SYNC_* environment variables.
`;
}

function required(name, aliases = []) {
  for (const key of [name, ...aliases]) {
    const value = String(process.env[key] || "").trim();
    if (value) return value;
  }
  throw new Error(`${name} is required`);
}

function postgresOptions(connectionString) {
  const parsed = new URL(connectionString);
  if (!new Set(["postgres:", "postgresql:"]).has(parsed.protocol)) {
    throw new Error("DATABASE_URL must use postgres:// or postgresql://");
  }
  return {
    connectionString,
    statement_timeout: 120_000,
    application_name: "inkar-offer-sync",
    ssl: parsed.searchParams.get("sslmode") === "require" ? { rejectUnauthorized: true } : undefined,
  };
}

function medusaBase() {
  return secureMedusaUrl(required("MEDUSA_URL"));
}

function clickHouseConfig() {
  const allowPrivateHttp = String(process.env.CLICKHOUSE_ALLOW_INSECURE_PRIVATE_HTTP || "")
    .trim().toLowerCase() === "true";
  return {
    url: secureClickHouseUrl(required("CLICKHOUSE_URL"), allowPrivateHttp),
    user: required("CLICKHOUSE_USER"),
    password: required("CLICKHOUSE_PASSWORD"),
    database: String(process.env.CLICKHOUSE_DATABASE || "pharmacy_analytics").trim(),
    factTable: validateClickHouseTable(
      process.env.CLICKHOUSE_FACT_TABLE,
      "pharmacy_analytics.fact_pharmacy_daily",
    ),
    mapTable: validateClickHouseTable(
      process.env.CLICKHOUSE_MAP_TABLE,
      "pharmacy_analytics.product_material_map",
    ),
    warehouseTable: validateClickHouseTable(
      process.env.CLICKHOUSE_WAREHOUSE_TABLE,
      "pharmacy_analytics.head_warehouse_stock",
    ),
  };
}

function wait(milliseconds) {
  return milliseconds > 0 ? new Promise((resolve) => setTimeout(resolve, milliseconds)) : Promise.resolve();
}

async function limitedText(response, maximumBytes) {
  const declared = Number(response.headers.get("content-length"));
  if (Number.isFinite(declared) && declared > maximumBytes) throw new Error("response_too_large");
  if (!response.body) throw new Error("response_missing_body");
  const reader = response.body.getReader();
  const chunks = [];
  let total = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > maximumBytes) {
      await reader.cancel().catch(() => undefined);
      throw new Error("response_too_large");
    }
    chunks.push(Buffer.from(value));
  }
  return Buffer.concat(chunks, total).toString("utf8");
}

function parseJsonEachRow(text) {
  const rows = [];
  for (const line of text.split(/\r?\n/)) {
    if (!line.trim()) continue;
    try {
      const row = JSON.parse(line);
      if (!row || typeof row !== "object" || Array.isArray(row)) throw new Error();
      rows.push(row);
    } catch {
      throw new Error("clickhouse_response_invalid_json_each_row");
    }
  }
  return rows;
}

async function clickHouseQuery(config, query, parameters, options) {
  const endpoint = new URL(config.url);
  endpoint.searchParams.set("database", config.database);
  endpoint.searchParams.set("wait_end_of_query", "1");
  // The production api_reader uses ClickHouse readonly=1, which rejects
  // per-request setting changes (HTTP 500 READONLY). AbortController still
  // enforces the bounded client-side timeout without changing server settings.
  for (const [name, value] of Object.entries(parameters || {})) {
    endpoint.searchParams.set(`param_${name}`, value);
  }
  let finalError;
  for (let attempt = 1; attempt <= options.attempts; attempt += 1) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), options.timeoutMs);
    try {
      const response = await fetch(endpoint, {
        method: "POST",
        headers: {
          Accept: "application/x-ndjson",
          Authorization: `Basic ${Buffer.from(`${config.user}:${config.password}`).toString("base64")}`,
          "Content-Type": "text/plain; charset=utf-8",
        },
        body: query,
        cache: "no-store",
        redirect: "error",
        signal: controller.signal,
      });
      if (!response.ok) {
        const error = new Error(`clickhouse_http_${response.status}`);
        error.status = response.status;
        throw error;
      }
      return parseJsonEachRow(await limitedText(response, options.maxResponseBytes));
    } catch (error) {
      finalError = error;
      const retryable = error?.name === "AbortError"
        || typeof error?.status !== "number"
        || retryableStatus(error.status);
      if (!retryable || attempt >= options.attempts) break;
      await wait(Math.min(2_000, 250 * (2 ** (attempt - 1))) + Math.floor(Math.random() * 100));
    } finally {
      clearTimeout(timer);
    }
  }
  throw finalError;
}

async function clickHouseLatestSnapshot(config, table, label, options) {
  const rows = await clickHouseQuery(config, `
    SELECT toString(max(snapshot_date)) AS snapshot_date
    FROM ${table}
    HAVING count() > 0
    FORMAT JSONEachRow
  `, {}, options);
  const snapshotDate = String(rows[0]?.snapshot_date || "");
  if (!/^\d{4}-\d{2}-\d{2}$/.test(snapshotDate)) {
    throw new Error(`clickhouse_${label}_snapshot_missing`);
  }
  return snapshotDate;
}

function safeError(error) {
  return (error instanceof Error ? error.message : String(error)).replace(/[\r\n]+/g, " ").slice(0, 1_000);
}

async function limitedJson(response, maximumBytes) {
  const declared = Number(response.headers.get("content-length"));
  if (Number.isFinite(declared) && declared > maximumBytes) throw new Error("response_too_large");
  if (!response.body) throw new Error("response_missing_body");
  const reader = response.body.getReader();
  const chunks = [];
  let total = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > maximumBytes) {
      await reader.cancel().catch(() => undefined);
      throw new Error("response_too_large");
    }
    chunks.push(Buffer.from(value));
  }
  const buffer = Buffer.concat(chunks, total);
  try {
    return JSON.parse(buffer.toString("utf8"));
  } catch {
    throw new Error("response_invalid_json");
  }
}

async function fetchSnapshot(base, publishableKey, productId, options) {
  const endpoint = new URL(`/store/products/${encodeURIComponent(productId)}/pharmacies`, base);
  let finalError;
  for (let attempt = 1; attempt <= options.attempts; attempt += 1) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), options.timeoutMs);
    try {
      const response = await fetch(endpoint, {
        headers: { Accept: "application/json", "x-publishable-api-key": publishableKey },
        cache: "no-store",
        redirect: "error",
        signal: controller.signal,
      });
      if (!response.ok) {
        const error = new Error(`medusa_http_${response.status}`);
        error.status = response.status;
        throw error;
      }
      return await limitedJson(response, options.maxResponseBytes);
    } catch (error) {
      finalError = error;
      const retryable = error?.name === "AbortError"
        || typeof error?.status !== "number"
        || retryableStatus(error.status);
      if (!retryable || attempt >= options.attempts) break;
      await wait(Math.min(2_000, 250 * (2 ** (attempt - 1))) + Math.floor(Math.random() * 100));
    } finally {
      clearTimeout(timer);
    }
  }
  throw finalError;
}

class BatchEndpointUnavailable extends Error {
  constructor(reason) {
    super(reason);
    this.name = "BatchEndpointUnavailable";
  }
}

async function fetchBatchSnapshots(base, publishableKey, productIds, options) {
  const endpoint = new URL("/store/products/pharmacies/batch", base);
  let finalError;
  for (let attempt = 1; attempt <= options.attempts; attempt += 1) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), options.timeoutMs);
    try {
      const response = await fetch(endpoint, {
        method: "POST",
        headers: {
          Accept: "application/json",
          "Content-Type": "application/json",
          "x-publishable-api-key": publishableKey,
        },
        body: JSON.stringify({ product_ids: productIds }),
        cache: "no-store",
        redirect: "error",
        signal: controller.signal,
      });
      if (!response.ok) {
        if ([404, 405, 501].includes(response.status)) {
          throw new BatchEndpointUnavailable(`batch_endpoint_http_${response.status}`);
        }
        const error = new Error(`medusa_http_${response.status}`);
        error.status = response.status;
        throw error;
      }
      const payload = await limitedJson(response, options.maxResponseBytes);
      const parsed = parsePharmacyBatchPayload(payload, productIds);
      if (parsed.status !== "valid") {
        throw new BatchEndpointUnavailable(parsed.reason);
      }
      return parsed;
    } catch (error) {
      if (error instanceof BatchEndpointUnavailable) throw error;
      finalError = error;
      const retryable = error?.name === "AbortError"
        || typeof error?.status !== "number"
        || retryableStatus(error.status);
      if (!retryable || attempt >= options.attempts) break;
      await wait(Math.min(2_000, 250 * (2 ** (attempt - 1))) + Math.floor(Math.random() * 100));
    } finally {
      clearTimeout(timer);
    }
  }
  throw finalError;
}

async function fetchSingles(base, publishableKey, productIds, options) {
  const fetched = [];
  for (let offset = 0; offset < productIds.length; offset += options.concurrency) {
    const requestGroup = productIds.slice(offset, offset + options.concurrency);
    fetched.push(...await Promise.all(requestGroup.map(async (productId) => {
      try {
        const raw = await fetchSnapshot(base, publishableKey, productId, options);
        return { productId, normalized: normalizePharmacyPayload(raw, options) };
      } catch (error) {
        return { productId, error: safeError(error) };
      }
    })));
  }
  return fetched;
}

async function selectedProducts(client, limit, source) {
  const result = await client.query(`
    SELECT product.id, offers.offer_updated_at, state.last_attempt_at
    FROM catalog_products product
    LEFT JOIN catalog_offer_sync_state state ON state.product_id = product.id
    LEFT JOIN LATERAL (
      SELECT max(offer.updated_at) AS offer_updated_at
      FROM catalog_pharmacy_offers offer
      WHERE offer.product_id = product.id
    ) offers ON true
    WHERE product.active
    ORDER BY (state.source IS DISTINCT FROM $2) DESC,
             greatest(
               coalesce(offers.offer_updated_at, '-infinity'::timestamptz),
               coalesce(state.last_attempt_at, '-infinity'::timestamptz)
             ) ASC,
             product.id ASC
    LIMIT $1
  `, [limit, source]);
  return result.rows.map((row) => String(row.id));
}

async function localProductIdentifiers(client, productIds) {
  if (productIds.length === 0) return [];
  const result = await client.query(`
    SELECT product.id,
           nullif(trim(product.metadata->>'ware_id'), '') AS ware_id,
           coalesce(
             jsonb_agg(
               jsonb_build_object(
                 'id', variant.id,
                 'sku', variant.sku,
                 'barcode', variant.barcode
               )
               ORDER BY variant.id
             ) FILTER (WHERE variant.id IS NOT NULL),
             '[]'::jsonb
           ) AS variants
    FROM catalog_products product
    LEFT JOIN catalog_variants variant
      ON variant.product_id = product.id AND variant.active
    WHERE product.id = ANY($1::text[]) AND product.active
    GROUP BY product.id, product.metadata
    ORDER BY product.id
  `, [productIds]);
  return result.rows.map((row) => ({
    productId: String(row.id),
    wareId: row.ware_id ? String(row.ware_id) : "",
    variants: Array.isArray(row.variants) ? row.variants : [],
  }));
}

function uniqueIdentifiers(products, selector) {
  return [...new Set(products.flatMap(selector).map(normalizeExactIdentifier).filter(Boolean))].sort();
}

async function clickHouseMappings(config, products, options) {
  const materialIds = uniqueIdentifiers(
    products,
    (product) => (product.variants || []).map((variant) => variant?.sku),
  );
  const wareIds = uniqueIdentifiers(products, (product) => [product.wareId]);
  const barcodes = uniqueIdentifiers(
    products,
    (product) => (product.variants || []).map((variant) => variant?.barcode),
  );
  if (!materialIds.length && !wareIds.length && !barcodes.length) return [];
  return clickHouseQuery(config, `
    SELECT
      lowerUTF8(toString(material_id)) AS material_id,
      lowerUTF8(toString(ware_id)) AS ware_id,
      lowerUTF8(toString(barcode)) AS barcode
    FROM ${config.mapTable}
    WHERE lowerUTF8(toString(material_id)) IN {material_ids:Array(String)}
       OR lowerUTF8(toString(ware_id)) IN {ware_ids:Array(String)}
       OR lowerUTF8(toString(barcode)) IN {barcodes:Array(String)}
    FORMAT JSONEachRow
  `, {
    material_ids: clickHouseArrayLiteral(materialIds),
    ware_ids: clickHouseArrayLiteral(wareIds),
    barcodes: clickHouseArrayLiteral(barcodes),
  }, options);
}

async function clickHouseOfferRows(config, mappings, snapshotDate, options) {
  const wareIds = [...new Set(mappings.map((mapping) => mapping.wareId))].sort();
  if (!wareIds.length) return [];
  return clickHouseQuery(config, `
    SELECT
      lowerUTF8(toString(ware_id)) AS ware_id,
      toString(profile_id) AS profile_id,
      any(toString(sname)) AS sname,
      any(toString(caption)) AS caption,
      minIf(toFloat64(retail_price), toFloat64(retail_price) > 0) AS retail_price,
      max(toFloat64(stock_end)) AS stock_end
    FROM ${config.factTable}
    PREWHERE snapshot_date = {snapshot:Date}
    WHERE lowerUTF8(toString(ware_id)) IN {ware_ids:Array(String)}
    GROUP BY ware_id, profile_id
    HAVING retail_price > 0 AND stock_end > 0
    ORDER BY ware_id, profile_id
    FORMAT JSONEachRow
  `, {
    snapshot: snapshotDate,
    ware_ids: clickHouseArrayLiteral(wareIds),
  }, options);
}

async function clickHouseWarehouseRows(config, mappings, snapshotDate, options) {
  const materialIds = [...new Set(mappings.flatMap((mapping) => mapping.materialIds || []))].sort();
  const barcodes = [...new Set(mappings.flatMap((mapping) => mapping.barcodes || []))].sort();
  if (!materialIds.length && !barcodes.length) return [];
  return clickHouseQuery(config, `
    SELECT
      lowerUTF8(toString(material_id)) AS material_id,
      lowerUTF8(toString(barcode)) AS barcode,
      toString(city) AS city,
      toString(warehouse_code) AS warehouse_code,
      sum(toFloat64(quantity)) AS quantity,
      sum(toFloat64(in_transit)) AS in_transit
    FROM ${config.warehouseTable}
    PREWHERE snapshot_date = {snapshot:Date}
    WHERE lowerUTF8(toString(material_id)) IN {material_ids:Array(String)}
       OR lowerUTF8(toString(barcode)) IN {barcodes:Array(String)}
    GROUP BY material_id, barcode, city, warehouse_code
    HAVING quantity > 0 OR in_transit > 0
    ORDER BY material_id, barcode, city, warehouse_code
    FORMAT JSONEachRow
  `, {
    snapshot: snapshotDate,
    material_ids: clickHouseArrayLiteral(materialIds),
    barcodes: clickHouseArrayLiteral(barcodes),
  }, options);
}

async function fetchClickHouseBatch(
  client,
  config,
  productIds,
  factSnapshotDate,
  warehouseSnapshotDate,
  options,
) {
  const localProducts = await localProductIdentifiers(client, productIds);
  const localById = new Map(localProducts.map((product) => [product.productId, product]));
  const missingLocal = productIds.filter((productId) => !localById.has(productId));
  const mappingRows = await clickHouseMappings(config, localProducts, options);
  const mappingResult = resolveProductMappings(localProducts, mappingRows);
  const [offerRows, warehouseRows] = await Promise.all([
    clickHouseOfferRows(config, mappingResult.resolved, factSnapshotDate, options),
    clickHouseWarehouseRows(config, mappingResult.resolved, warehouseSnapshotDate, options),
  ]);
  const fetched = normalizeClickHouseOfferRows(
    offerRows,
    mappingResult.resolved,
    factSnapshotDate,
    options.maxPharmacies,
  );
  const warehouseNormalization = normalizeClickHouseWarehouseRows(
    warehouseRows,
    mappingResult.resolved,
    warehouseSnapshotDate,
  );
  const merged = attachClickHouseWarehouseResults(fetched, warehouseNormalization);
  const unresolved = [
    ...mappingResult.unresolved,
    ...missingLocal.map((productId) => ({ productId, reason: "local_product_missing_or_inactive" })),
  ];
  merged.push(...unresolved.map(({ productId, reason }) => ({ productId, error: reason })));
  const covered = new Set(merged.map((item) => item.productId));
  for (const productId of productIds) {
    if (!covered.has(productId)) merged.push({ productId, error: "clickhouse_product_not_covered" });
  }
  return merged.sort(
    (left, right) => productIds.indexOf(left.productId) - productIds.indexOf(right.productId),
  );
}

async function recordNonSuccess(client, productId, status, error, source) {
  await client.query(`
    INSERT INTO catalog_offer_sync_state (
      product_id, last_attempt_at, status, consecutive_failures, last_error,
      source, updated_at
    ) VALUES ($1, clock_timestamp(), $2, 1, $3, $4, clock_timestamp())
    ON CONFLICT (product_id) DO UPDATE SET
      last_attempt_at = EXCLUDED.last_attempt_at,
      status = EXCLUDED.status,
      consecutive_failures = catalog_offer_sync_state.consecutive_failures + 1,
      last_error = EXCLUDED.last_error,
      source = EXCLUDED.source,
      updated_at = EXCLUDED.updated_at
  `, [productId, status, error.slice(0, 1_000), source]);
}

async function replaceOffers(client, productId, offers, context = {}) {
  // Every writer acquires shared pharmacy rows in the same order. Combined
  // with the per-product row lock this prevents the read-through cache and
  // backfill from forming opposite product/offer/pharmacy lock chains.
  const orderedOffers = [...offers].sort((left, right) => left.id.localeCompare(right.id));
  const payload = JSON.stringify(orderedOffers.map((offer) => ({
    id: offer.id,
    external_id: offer.externalId || null,
    name: offer.name,
    city: offer.city || "",
    address: offer.address || "",
    price: offer.price,
    stock_quantity: offer.stockQuantity ?? null,
    variant_id: offer.variantId || null,
    source_updated_at: offer.sourceUpdatedAt || null,
    source: offer.source || context.source || "medusa",
  })));
  for (let attempt = 1; attempt <= TRANSACTION_ATTEMPTS; attempt += 1) {
    await client.query("BEGIN");
    try {
      const product = await client.query(
        "SELECT id FROM catalog_products WHERE id = $1 AND active FOR NO KEY UPDATE",
        [productId],
      );
      if (product.rowCount !== 1) throw new Error("local_product_missing_or_inactive");
      await client.query(`
        WITH incoming AS (
          SELECT id, nullif(external_id, '') AS external_id, name,
                 nullif(city, '') AS city, nullif(address, '') AS address,
                 nullif(source, '') AS source
          FROM jsonb_to_recordset($1::jsonb)
            AS item(
              id text, external_id text, name text, city text, address text,
              price bigint, stock_quantity numeric, variant_id text,
              source_updated_at timestamptz, source text
            )
        )
        INSERT INTO catalog_pharmacies (
          id, external_id, name, city, address, metadata, active, updated_at
        )
        SELECT id, external_id, name, city, address,
               jsonb_build_object('source', coalesce(source, $2)),
               true, clock_timestamp()
        FROM incoming
        ORDER BY id
        ON CONFLICT (id) DO UPDATE SET
          external_id = coalesce(EXCLUDED.external_id, catalog_pharmacies.external_id),
          name = EXCLUDED.name,
          city = coalesce(EXCLUDED.city, catalog_pharmacies.city),
          address = coalesce(EXCLUDED.address, catalog_pharmacies.address),
          metadata = catalog_pharmacies.metadata || EXCLUDED.metadata,
          active = true,
          updated_at = EXCLUDED.updated_at
      `, [payload, context.source || "medusa"]);
      await client.query("DELETE FROM catalog_pharmacy_offers WHERE product_id = $1", [productId]);
      await client.query(`
        WITH incoming AS (
          SELECT id, price, stock_quantity, nullif(variant_id, '') AS variant_id,
                 source_updated_at
          FROM jsonb_to_recordset($2::jsonb)
            AS item(
              id text, external_id text, name text, city text, address text,
              price bigint, stock_quantity numeric, variant_id text,
              source_updated_at timestamptz, source text
            )
        )
        INSERT INTO catalog_pharmacy_offers (
          product_id, variant_id, pharmacy_id, price_amount, currency_code,
          stock_quantity, in_stock, source_updated_at, updated_at
        )
        SELECT $1, variant_id, id, price, 'kzt', stock_quantity, true,
               coalesce(source_updated_at, clock_timestamp()), clock_timestamp()
        FROM incoming
        ORDER BY id
      `, [productId, payload]);
      if (Array.isArray(context.warehouseStocks)) {
        const warehousePayload = JSON.stringify(context.warehouseStocks.map((stock) => ({
          warehouse_code: stock.warehouseCode,
          city: stock.city || "",
          stock_quantity: stock.stockQuantity,
          in_transit_quantity: stock.inTransitQuantity,
          variant_id: stock.variantId || null,
          source_updated_at: stock.sourceUpdatedAt,
        })));
        await client.query(
          "DELETE FROM catalog_warehouse_stocks WHERE product_id = $1",
          [productId],
        );
        await client.query(`
          WITH incoming AS (
            SELECT warehouse_code, city, stock_quantity, in_transit_quantity,
                   nullif(variant_id, '') AS variant_id, source_updated_at
            FROM jsonb_to_recordset($2::jsonb)
              AS item(
                warehouse_code text, city text, stock_quantity numeric,
                in_transit_quantity numeric, variant_id text,
                source_updated_at timestamptz
              )
          )
          INSERT INTO catalog_warehouse_stocks (
            product_id, variant_id, warehouse_code, city, stock_quantity,
            in_transit_quantity, source_updated_at, updated_at
          )
          SELECT $1, variant_id, warehouse_code, city, stock_quantity,
                 in_transit_quantity, source_updated_at, clock_timestamp()
          FROM incoming
          ORDER BY city, warehouse_code
        `, [productId, warehousePayload]);
      }
      await client.query(`
        INSERT INTO catalog_offer_sync_state (
          product_id, last_attempt_at, last_success_at, status,
          consecutive_failures, last_offer_count, last_error, source,
          source_snapshot_at, mapping_source, updated_at
        ) VALUES (
          $1, clock_timestamp(), clock_timestamp(), 'success', 0, $2, NULL,
          $3, $4, $5, clock_timestamp()
        )
        ON CONFLICT (product_id) DO UPDATE SET
          last_attempt_at = EXCLUDED.last_attempt_at,
          last_success_at = EXCLUDED.last_success_at,
          status = 'success', consecutive_failures = 0,
          last_offer_count = EXCLUDED.last_offer_count,
          last_error = NULL, updated_at = EXCLUDED.updated_at
          , source = EXCLUDED.source
          , source_snapshot_at = EXCLUDED.source_snapshot_at
          , mapping_source = EXCLUDED.mapping_source
      `, [
        productId,
        orderedOffers.length,
        context.source || "medusa",
        context.sourceSnapshotAt || null,
        context.mappingSource || null,
      ]);
      await client.query("COMMIT");
      return;
    } catch (error) {
      await client.query("ROLLBACK").catch(() => undefined);
      if (!retryableTransactionError(error) || attempt >= TRANSACTION_ATTEMPTS) throw error;
      console.warn(JSON.stringify({
        event: "offer_sync_db_transaction_retry",
        product_id: productId,
        attempt,
        error_code: error.code,
      }));
      await wait(transactionRetryDelayMs(attempt));
    }
  }
}

async function main() {
  const options = parseOfferSyncArgs(process.argv.slice(2));
  if (options.help) {
    console.log(usage());
    return;
  }
  const databaseUrl = required("DATABASE_URL", ["POSTGRES_URL"]);
  const base = options.source === "medusa" ? medusaBase() : null;
  const publishableKey = options.source === "medusa" ? required("MEDUSA_PUBLISHABLE_KEY") : null;
  const clickHouse = options.source === "clickhouse" ? clickHouseConfig() : null;
  const client = new Client(postgresOptions(databaseUrl));
  let locked = false;
  const summary = {
    source: options.source,
    selected: 0,
    attempted: 0,
    updated: 0,
    empty: 0,
    failed: 0,
  };
  try {
    await client.connect();
    const schema = await client.query(`
      SELECT to_regclass('catalog_products') AS products,
             to_regclass('catalog_offer_sync_state') AS sync_state
    `);
    if (!schema.rows[0]?.products || !schema.rows[0]?.sync_state) {
      throw new Error("Catalog offer schema is missing; run npm run db:migrate first");
    }
    const lock = await client.query("SELECT pg_try_advisory_lock($1) AS locked", [ADVISORY_LOCK_ID]);
    if (!lock.rows[0]?.locked) {
      console.log(JSON.stringify({
        event: "offer_sync_skipped",
        scope: options.full ? "full" : "incremental",
        reason: "catalog_sync_lock_busy",
      }));
      // A bounded timer run may safely skip and try again at its next tick.
      // A manually started full backfill has no timer of its own: report a
      // temporary failure so its systemd unit can retry instead of silently
      // declaring the one-time job complete.
      if (options.full) process.exitCode = 75;
      return;
    }
    locked = true;
    const sourceSnapshotDate = clickHouse
      ? await clickHouseLatestSnapshot(clickHouse, clickHouse.factTable, "fact", options)
      : null;
    const warehouseSnapshotDate = clickHouse
      ? await clickHouseLatestSnapshot(
        clickHouse,
        clickHouse.warehouseTable,
        "warehouse",
        options,
      )
      : null;
    const productIds = await selectedProducts(client, options.limit, options.source);
    summary.selected = productIds.length;

    for (let offset = 0; offset < productIds.length; offset += options.batchSize) {
      const batch = productIds.slice(offset, offset + options.batchSize);
      let fetched;
      if (clickHouse) {
        try {
          fetched = await fetchClickHouseBatch(
            client,
            clickHouse,
            batch,
            sourceSnapshotDate,
            warehouseSnapshotDate,
            options,
          );
        } catch (error) {
          const message = safeError(error);
          fetched = batch.map((productId) => ({ productId, error: message }));
        }
      } else {
        try {
          const parsed = await fetchBatchSnapshots(base, publishableKey, batch, options);
          const payloadByProduct = new Map(
            parsed.snapshots.map(({ productId, payload }) => [productId, payload]),
          );
          const notFound = new Set(parsed.notFoundProductIds);
          fetched = batch.map((productId) => {
            if (notFound.has(productId)) {
              return { productId, error: "medusa_http_404" };
            }
            return {
              productId,
              normalized: normalizePharmacyPayload(payloadByProduct.get(productId), options),
            };
          });
        } catch (error) {
          if (error instanceof BatchEndpointUnavailable) {
            console.warn(JSON.stringify({
              event: "offer_sync_batch_fallback",
              reason: safeError(error),
              products: batch.length,
            }));
            fetched = await fetchSingles(base, publishableKey, batch, options);
          } else {
            const message = safeError(error);
            fetched = batch.map((productId) => ({ productId, error: message }));
          }
        }
      }

      for (const result of fetched) {
        summary.attempted += 1;
        if (result.error) {
          summary.failed += 1;
          if (options.apply) {
            await recordNonSuccess(client, result.productId, "failed", result.error, options.source);
          }
          console.error(JSON.stringify({ event: "offer_sync_product_failed", product_id: result.productId, error: result.error }));
          continue;
        }
        if (result.normalized.status !== "valid") {
          const status = result.normalized.status === "empty" ? "empty" : "failed";
          if (status === "empty") summary.empty += 1;
          else summary.failed += 1;
          if (options.apply) {
            await recordNonSuccess(
              client,
              result.productId,
              status,
              result.normalized.reason,
              options.source,
            );
          }
          continue;
        }
        if (options.apply) {
          await replaceOffers(client, result.productId, result.normalized.offers, {
            source: options.source,
            sourceSnapshotAt: result.sourceSnapshotAt || null,
            mappingSource: result.mappingSource || null,
            warehouseStocks: result.warehouseStocks,
          });
        }
        summary.updated += 1;
      }
      if (summary.attempted > 0 && (summary.attempted % 500 === 0 || summary.attempted === summary.selected)) {
        console.log(JSON.stringify({ event: "offer_sync_progress", ...summary }));
      }
      if (offset + options.batchSize < productIds.length) await wait(options.batchDelayMs);
    }
    console.log(JSON.stringify({
      event: "offer_sync_completed",
      mode: options.apply ? "apply" : "dry_run",
      scope: options.full ? "full" : "incremental",
      ...summary,
    }));
    const exitCode = offerSyncExitCode(summary);
    if (exitCode !== 0) {
      console.error(JSON.stringify({
        event: "offer_sync_incomplete",
        scope: options.full ? "full" : "incremental",
        retryable_products: summary.failed + summary.empty,
        exit_code: exitCode,
      }));
      process.exitCode = exitCode;
    }
  } finally {
    if (locked) await client.query("SELECT pg_advisory_unlock($1)", [ADVISORY_LOCK_ID]).catch(() => undefined);
    await client.end().catch(() => undefined);
  }
}

main().catch((error) => {
  console.error(safeError(error));
  process.exitCode = 1;
});
