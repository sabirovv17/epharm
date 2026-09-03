#!/usr/bin/env node

/**
 * Replicate the public Medusa catalogue into the local Inkar Postgres read model.
 * Safe defaults: read-only dry-run; local writes require --apply; source rows are
 * never deleted. A complete run can explicitly mark missing products inactive.
 */

import { createHash, randomUUID } from "node:crypto";
import process from "node:process";
import pg from "pg";
import {
  buildCatalogProductsUrl,
  incrementalUpdatedSince,
  parseCatalogSyncArgs,
} from "./lib/medusa-catalog-sync.mjs";
import { secureMedusaUrl } from "./lib/secure-medusa-url.mjs";

const { Client } = pg;
const retryableStatuses = new Set([408, 425, 429, 500, 502, 503, 504]);
const advisoryLockId = 4_930_511_108;
const maximumResponseBytes = 64 * 1024 * 1024;
const requestTimeoutMs = 45_000;

function usage() {
  return `
Usage:
  node scripts/sync-medusa-catalog.mjs [options]

Options:
  --apply                    Write to DATABASE_URL (default: dry-run)
  --full                     Reconcile the complete catalogue instead of changed products
  --page-size <1..1000>      Medusa page size (default: 500)
  --limit <n>                Stop after n products (canary/backfill)
  --offset <n>               Start a manual --full run at this Medusa offset
  --overlap-seconds <n>      Re-read this interval before the last checkpoint (default: 300)
  --mark-missing-inactive    With --full --apply, mark unseen local products inactive
  --help                     Show this help

Environment:
  MEDUSA_URL, MEDUSA_PUBLISHABLE_KEY
  MEDUSA_SALES_CHANNEL and MEDUSA_REGION are recommended
  CATALOG_SYNC_OVERLAP_SECONDS optionally changes the 300-second overlap
  DATABASE_URL is required for --apply and for default incremental dry-runs

The default run is incremental and requires a completed checkpoint. Bootstrap
once with --apply --full; periodically reconcile deletions with
--apply --full --mark-missing-inactive.
`;
}

function requiredEnvironment(name) {
  const value = String(process.env[name] || "").trim();
  if (!value) throw new Error(`${name} is required`);
  return value;
}

function optionalText(value) {
  if (value === null || value === undefined) return null;
  const text = String(value).trim();
  return text || null;
}

function finiteInteger(value) {
  const number = Number(value);
  return Number.isFinite(number) ? Math.round(number) : null;
}

function safeTimestamp(value) {
  if (!value) return null;
  const timestamp = new Date(value);
  return Number.isNaN(timestamp.valueOf()) ? null : timestamp.toISOString();
}

function safeObject(value) {
  return value && typeof value === "object" && !Array.isArray(value) ? value : {};
}

function metadataValue(metadata, ...keys) {
  for (const key of keys) {
    const value = optionalText(metadata?.[key]);
    if (value) return value;
  }
  return null;
}

function normalizePage(rawProducts) {
  const products = [];
  const variants = [];
  const categoriesById = new Map();
  const productCategories = [];
  const images = [];

  for (const raw of rawProducts) {
    const id = optionalText(raw?.id);
    const handle = optionalText(raw?.handle);
    const title = optionalText(raw?.title);
    if (!id || !handle || !title) throw new Error("Medusa product is missing id, handle or title");
    const metadata = safeObject(raw?.metadata);
    products.push({
      id,
      handle,
      title,
      subtitle: optionalText(raw?.subtitle),
      description: optionalText(raw?.description),
      thumbnail_url: optionalText(raw?.thumbnail),
      brand: metadataValue(metadata, "brand_name", "brand_raw", "brand", "corporation"),
      manufacturer: metadataValue(metadata, "manufacturer_official", "manufacturer"),
      country: metadataValue(metadata, "country_official", "country"),
      mnn: metadataValue(metadata, "mnn"),
      atc: metadataValue(metadata, "atc", "atc_1"),
      rx_otc: metadataValue(metadata, "rx_otc"),
      metadata,
      source_created_at: safeTimestamp(raw?.created_at),
      source_updated_at: safeTimestamp(raw?.updated_at),
    });

    for (const rawVariant of Array.isArray(raw?.variants) ? raw.variants : []) {
      const variantId = optionalText(rawVariant?.id);
      if (!variantId) continue;
      const calculated = safeObject(rawVariant?.calculated_price);
      variants.push({
        id: variantId,
        product_id: id,
        title: optionalText(rawVariant?.title) || title,
        sku: optionalText(rawVariant?.sku),
        barcode: optionalText(rawVariant?.barcode),
        price_amount: finiteInteger(calculated.calculated_amount),
        original_price_amount: finiteInteger(calculated.original_amount),
        currency_code: optionalText(calculated.currency_code)?.toLowerCase() || null,
        metadata: safeObject(rawVariant?.metadata),
      });
    }

    const rawCategories = Array.isArray(raw?.categories) ? raw.categories : [];
    rawCategories.forEach((rawCategory, position) => {
      const categoryId = optionalText(rawCategory?.id);
      const name = optionalText(rawCategory?.name);
      if (!categoryId || !name) return;
      const parentId = optionalText(rawCategory?.parent_category_id || rawCategory?.parent_category?.id);
      categoriesById.set(categoryId, {
        id: categoryId,
        handle: optionalText(rawCategory?.handle),
        name,
        parent_id: parentId,
        rank: finiteInteger(rawCategory?.rank) || 0,
        metadata: safeObject(rawCategory?.metadata),
        source_updated_at: safeTimestamp(rawCategory?.updated_at),
      });
      productCategories.push({
        product_id: id,
        category_id: categoryId,
        is_primary: position === 0,
      });
    });

    const seenUrls = new Set();
    (Array.isArray(raw?.images) ? raw.images : []).forEach((rawImage, position) => {
      const url = optionalText(rawImage?.url);
      if (!url || seenUrls.has(url)) return;
      seenUrls.add(url);
      const imageId = optionalText(rawImage?.id)
        || `img_${createHash("sha256").update(`${id}|${url}`).digest("hex").slice(0, 40)}`;
      images.push({
        id: imageId,
        product_id: id,
        url,
        position,
        metadata: safeObject(rawImage?.metadata),
      });
    });
  }

  return {
    products,
    variants,
    categories: [...categoriesById.values()],
    productCategories,
    images,
  };
}

function wait(milliseconds) {
  return new Promise((resolveWait) => setTimeout(resolveWait, milliseconds));
}

async function responseJson(response) {
  const declaredLength = Number(response.headers.get("content-length"));
  if (Number.isFinite(declaredLength) && declaredLength > maximumResponseBytes) {
    const error = new Error(`Medusa response exceeds ${maximumResponseBytes} bytes`);
    error.retryable = false;
    throw error;
  }
  if (!response.body) throw new Error("Medusa response has no body");

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  const chunks = [];
  let received = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      received += value.byteLength;
      if (received > maximumResponseBytes) {
        const error = new Error(`Medusa response exceeds ${maximumResponseBytes} bytes`);
        error.retryable = false;
        throw error;
      }
      chunks.push(decoder.decode(value, { stream: true }));
    }
    chunks.push(decoder.decode());
    return JSON.parse(chunks.join(""));
  } catch (error) {
    await reader.cancel(error).catch(() => undefined);
    throw error;
  } finally {
    reader.releaseLock();
  }
}

async function fetchJson(url, headers, attempts = 4) {
  let lastError;
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), requestTimeoutMs);
    try {
      const response = await fetch(url, { headers, signal: controller.signal });
      if (!response.ok) {
        const error = new Error(`GET ${url.pathname} -> HTTP ${response.status}`);
        error.status = response.status;
        const retryAfter = response.headers.get("retry-after");
        if (retryAfter && /^\d+$/.test(retryAfter)) error.retryAfterMs = Number(retryAfter) * 1_000;
        throw error;
      }
      return await responseJson(response);
    } catch (error) {
      lastError = error;
      const retryable = error?.retryable !== false && (error?.name === "AbortError"
        || !Number.isInteger(error?.status)
        || retryableStatuses.has(error.status));
      if (!retryable || attempt === attempts - 1) break;
      const backoff = Math.min(5_000, 400 * (2 ** attempt)) + Math.floor(Math.random() * 150);
      await wait(Math.max(backoff, Number(error?.retryAfterMs) || 0));
    } finally {
      clearTimeout(timeout);
    }
  }
  throw lastError;
}

function postgresOptions(connectionString) {
  const parsed = new URL(connectionString);
  if (!["postgres:", "postgresql:"].includes(parsed.protocol)) {
    throw new Error("DATABASE_URL must use postgres:// or postgresql://");
  }
  return {
    connectionString,
    statement_timeout: 120_000,
    application_name: "inkar-catalog-sync",
    ssl: parsed.searchParams.get("sslmode") === "require" ? { rejectUnauthorized: true } : undefined,
  };
}

function requiredDatabaseUrl() {
  const value = String(process.env.DATABASE_URL || process.env.POSTGRES_URL || "").trim();
  if (!value) throw new Error("DATABASE_URL or POSTGRES_URL is required");
  return value;
}

async function rollbackAfterError(client, originalError) {
  try {
    await client.query("ROLLBACK");
  } catch (rollbackError) {
    console.error("Could not roll back Postgres transaction", rollbackError);
  }
  throw originalError;
}

async function closePostgres(client, advisoryLockHeld) {
  if (advisoryLockHeld) {
    try {
      await client.query("SELECT pg_advisory_unlock($1)", [advisoryLockId]);
    } catch (error) {
      console.error("Could not explicitly release catalogue sync lock", error);
    }
  }
  try {
    await client.end();
  } catch (error) {
    console.error("Could not close catalogue sync database connection", error);
  }
}

async function recordFailedRun(client, runId, error) {
  const message = error instanceof Error ? error.message : String(error);
  try {
    await client.query(`
      UPDATE catalog_import_runs SET status = 'failed', finished_at = now(), error_message = $2
      WHERE id = $1
    `, [runId, message.slice(0, 4_000)]);
  } catch (recordError) {
    console.error("Could not record failed catalogue run", recordError);
  }
}

async function completeRun(client, runId, summary, markMissingInactive) {
  await client.query("BEGIN");
  try {
    if (markMissingInactive) {
      await client.query(`
        UPDATE catalog_products SET active = false, updated_at = now()
        WHERE source_run_id IS DISTINCT FROM $1 AND active
      `, [runId]);
      await client.query(`
        UPDATE catalog_variants v SET active = false, updated_at = now()
        WHERE v.active AND NOT EXISTS (
          SELECT 1 FROM catalog_products p WHERE p.id = v.product_id AND p.active
        )
      `);
    }
    const total = await client.query(
      "SELECT count(*)::integer AS active_count FROM catalog_products WHERE active",
    );
    const catalogTotal = Number(total.rows[0]?.active_count);
    if (!Number.isSafeInteger(catalogTotal) || catalogTotal < 0) {
      throw new Error("Could not determine active catalogue size");
    }
    const reconciledFullRun = summary.scope !== "full"
      || summary.expected_products === catalogTotal;
    const finalSummary = {
      ...summary,
      catalog_total: catalogTotal,
      checkpoint_eligible: summary.checkpoint_eligible && reconciledFullRun,
      complete: summary.complete && reconciledFullRun,
      reconciliation_required: summary.scope === "full" && !reconciledFullRun,
    };
    await client.query(`
      UPDATE catalog_import_runs SET
        status = 'completed', finished_at = now(),
        metrics = metrics || $2::jsonb
      WHERE id = $1
    `, [runId, JSON.stringify(finalSummary)]);
    await client.query("COMMIT");
    return finalSummary;
  } catch (error) {
    await rollbackAfterError(client, error);
  }
}

async function syncPage(client, runId, page) {
  const productIds = page.products.map((product) => product.id);
  await client.query("BEGIN");
  try {
    await client.query(`
      INSERT INTO catalog_products (
        id, handle, title, subtitle, description, thumbnail_url, brand, manufacturer,
        country, mnn, atc, rx_otc, metadata, active, source_created_at,
        source_updated_at, source_seen_at, source_run_id, updated_at
      )
      SELECT id, handle, title, subtitle, description, thumbnail_url, brand, manufacturer,
             country, mnn, atc, rx_otc, metadata, true, source_created_at,
             source_updated_at, now(), $1, now()
      FROM jsonb_to_recordset($2::jsonb) AS x(
        id text, handle text, title text, subtitle text, description text,
        thumbnail_url text, brand text, manufacturer text, country text, mnn text,
        atc text, rx_otc text, metadata jsonb, source_created_at timestamptz,
        source_updated_at timestamptz
      )
      ON CONFLICT (id) DO UPDATE SET
        handle = excluded.handle, title = excluded.title, subtitle = excluded.subtitle,
        description = excluded.description, thumbnail_url = excluded.thumbnail_url,
        brand = excluded.brand, manufacturer = excluded.manufacturer,
        country = excluded.country, mnn = excluded.mnn, atc = excluded.atc,
        rx_otc = excluded.rx_otc, metadata = excluded.metadata, active = true,
        source_created_at = excluded.source_created_at,
        source_updated_at = excluded.source_updated_at,
        source_seen_at = excluded.source_seen_at, source_run_id = excluded.source_run_id,
        updated_at = now()
    `, [runId, JSON.stringify(page.products)]);

    if (page.categories.length) {
      await client.query(`
        INSERT INTO catalog_categories (
          id, handle, name, parent_id, rank, metadata, active,
          source_updated_at, source_seen_at, source_run_id, updated_at
        )
        SELECT id, handle, name, parent_id, rank, metadata, true,
               source_updated_at, now(), $1, now()
        FROM jsonb_to_recordset($2::jsonb) AS x(
          id text, handle text, name text, parent_id text, rank integer,
          metadata jsonb, source_updated_at timestamptz
        )
        ON CONFLICT (id) DO UPDATE SET
          handle = excluded.handle, name = excluded.name, parent_id = excluded.parent_id,
          rank = excluded.rank, metadata = excluded.metadata, active = true,
          source_updated_at = excluded.source_updated_at,
          source_seen_at = excluded.source_seen_at, source_run_id = excluded.source_run_id,
          updated_at = now()
      `, [runId, JSON.stringify(page.categories)]);
    }

    await client.query(
      "UPDATE catalog_variants SET active = false, updated_at = now() WHERE product_id = ANY($1::text[])",
      [productIds],
    );
    if (page.variants.length) {
      await client.query(`
        INSERT INTO catalog_variants (
          id, product_id, title, sku, barcode, price_amount, original_price_amount,
          currency_code, metadata, active, source_seen_at, source_run_id, updated_at
        )
        SELECT id, product_id, title, sku, barcode, price_amount, original_price_amount,
               currency_code, metadata, true, now(), $1, now()
        FROM jsonb_to_recordset($2::jsonb) AS x(
          id text, product_id text, title text, sku text, barcode text,
          price_amount bigint, original_price_amount bigint, currency_code text, metadata jsonb
        )
        ON CONFLICT (id) DO UPDATE SET
          product_id = excluded.product_id, title = excluded.title, sku = excluded.sku,
          barcode = excluded.barcode, price_amount = excluded.price_amount,
          original_price_amount = excluded.original_price_amount,
          currency_code = excluded.currency_code, metadata = excluded.metadata,
          active = true, source_seen_at = excluded.source_seen_at,
          source_run_id = excluded.source_run_id, updated_at = now()
      `, [runId, JSON.stringify(page.variants)]);
    }

    await client.query(
      "DELETE FROM catalog_product_categories WHERE product_id = ANY($1::text[])",
      [productIds],
    );
    if (page.productCategories.length) {
      await client.query(`
        INSERT INTO catalog_product_categories (product_id, category_id, is_primary)
        SELECT product_id, category_id, is_primary
        FROM jsonb_to_recordset($1::jsonb) AS x(
          product_id text, category_id text, is_primary boolean
        )
        ON CONFLICT (product_id, category_id) DO UPDATE
          SET is_primary = excluded.is_primary
      `, [JSON.stringify(page.productCategories)]);
    }

    await client.query("DELETE FROM catalog_images WHERE product_id = ANY($1::text[])", [productIds]);
    if (page.images.length) {
      await client.query(`
        INSERT INTO catalog_images (
          id, product_id, url, position, metadata, source_seen_at, source_run_id, updated_at
        )
        SELECT id, product_id, url, position, metadata, now(), $1, now()
        FROM jsonb_to_recordset($2::jsonb) AS x(
          id text, product_id text, url text, position integer, metadata jsonb
        )
        ON CONFLICT (id) DO UPDATE SET
          product_id = excluded.product_id, url = excluded.url, position = excluded.position,
          metadata = excluded.metadata, source_seen_at = excluded.source_seen_at,
          source_run_id = excluded.source_run_id, updated_at = now()
      `, [runId, JSON.stringify(page.images)]);
    }

    await client.query(`
      UPDATE catalog_import_runs SET
        processed_products = processed_products + $2,
        processed_variants = processed_variants + $3,
        processed_images = processed_images + $4,
        metrics = jsonb_set(metrics, '{last_page_size}', to_jsonb($5::integer), true)
      WHERE id = $1
    `, [runId, page.products.length, page.variants.length, page.images.length, productIds.length]);
    await client.query("COMMIT");
  } catch (error) {
    await rollbackAfterError(client, error);
  }
}

async function main() {
  const args = parseCatalogSyncArgs(process.argv.slice(2), process.env);
  if (args.help) {
    console.log(usage());
    return;
  }
  if (args.ignoredStandaloneMarkMissingInactive) {
    console.warn(JSON.stringify({
      event: "standalone_mark_missing_ignored",
      reason: "use --full --apply --mark-missing-inactive for a manual reconcile",
    }));
  }

  const medusaUrl = secureMedusaUrl(requiredEnvironment("MEDUSA_URL"));
  const publishableKey = requiredEnvironment("MEDUSA_PUBLISHABLE_KEY");
  const salesChannel = optionalText(process.env.MEDUSA_SALES_CHANNEL);
  const region = optionalText(process.env.MEDUSA_REGION);
  const headers = { Accept: "application/json", "x-publishable-api-key": publishableKey };
  const runId = randomUUID();
  const runStartedAt = new Date().toISOString();
  let updatedSince = null;
  const summary = {
    mode: args.apply ? "apply" : "dry_run",
    scope: args.full ? "full" : "incremental",
    run_id: runId,
    expected_products: null,
    processed_products: 0,
    processed_variants: 0,
    processed_images: 0,
    categories: new Set(),
    started_offset: args.offset,
    next_offset: args.offset,
    source_high_watermark: runStartedAt,
    source_updated_since: null,
    overlap_seconds: args.overlapSeconds,
  };
  const seenProductIds = new Set();
  let countPresentOnEveryPage = true;

  let client = null;
  let advisoryLockHeld = false;
  let runRecorded = false;
  const databaseRequired = args.apply || !args.full;
  if (databaseRequired) {
    client = new Client(postgresOptions(requiredDatabaseUrl()));
    try {
      await client.connect();
      if (args.apply) {
        const lock = await client.query("SELECT pg_try_advisory_lock($1) AS locked", [advisoryLockId]);
        if (!lock.rows[0]?.locked) throw new Error("Another catalogue sync is already running");
        advisoryLockHeld = true;
      }
      const tableCheck = await client.query("SELECT to_regclass('catalog_products') AS table_name");
      if (!tableCheck.rows[0]?.table_name) {
        throw new Error("Catalog schema is missing; run npm run db:migrate first");
      }
      if (!args.full) {
        const checkpoint = await client.query(`
          SELECT started_at, metrics->>'source_high_watermark' AS source_high_watermark
          FROM catalog_import_runs
          WHERE status = 'completed' AND source = $1
            AND (
              metrics @> '{"checkpoint_eligible":true}'::jsonb
              OR (
                NOT (metrics ? 'checkpoint_eligible')
                AND metrics @> '{"complete":true,"start_offset":0,"started_offset":0,"limit":null}'::jsonb
              )
            )
          ORDER BY finished_at DESC NULLS LAST, started_at DESC, id DESC
          LIMIT 1
        `, [medusaUrl.origin]);
        updatedSince = incrementalUpdatedSince(checkpoint.rows[0], args.overlapSeconds);
        if (!updatedSince) {
          throw new Error("No completed catalogue checkpoint; bootstrap with --apply --full");
        }
        summary.source_updated_since = updatedSince;
      }
      if (args.apply) {
        await client.query(`
          INSERT INTO catalog_import_runs (id, source, status, metrics)
          VALUES ($1, $2, 'running', $3::jsonb)
        `, [runId, medusaUrl.origin, JSON.stringify({
          scope: summary.scope,
          start_offset: args.offset,
          limit: args.limit,
          source_high_watermark: runStartedAt,
          source_updated_since: updatedSince,
          overlap_seconds: args.overlapSeconds,
        })]);
        runRecorded = true;
      }
    } catch (error) {
      await closePostgres(client, advisoryLockHeld);
      client = null;
      advisoryLockHeld = false;
      throw error;
    }
  }

  try {
    let offset = args.offset;
    while (true) {
      const remaining = args.limit === null ? args.pageSize : args.limit - summary.processed_products;
      if (remaining <= 0) break;
      const requestedPageSize = Math.min(args.pageSize, remaining);
      const url = buildCatalogProductsUrl(medusaUrl, {
        limit: requestedPageSize,
        offset,
        salesChannel,
        region,
        updatedSince,
      });

      const response = await fetchJson(url, headers);
      const rawProducts = Array.isArray(response?.products) ? response.products : null;
      if (!rawProducts) throw new Error("Medusa response does not contain products[]");
      if (rawProducts.length > requestedPageSize) {
        throw new Error(`Medusa returned ${rawProducts.length} products for limit ${requestedPageSize}`);
      }
      const responseCount = response?.count === undefined || response?.count === null
        ? null
        : Number(response.count);
      if (responseCount !== null
          && (!Number.isSafeInteger(responseCount) || responseCount < 0 || responseCount > 2_147_483_647)) {
        throw new Error("Medusa response contains an invalid product count");
      }
      if (responseCount === null) countPresentOnEveryPage = false;
      if (summary.expected_products === null && responseCount !== null) {
        summary.expected_products = responseCount;
        if (runRecorded) {
          await client.query(
            "UPDATE catalog_import_runs SET expected_products = $2 WHERE id = $1",
            [runId, summary.expected_products],
          );
        }
      } else if (responseCount !== null && responseCount !== summary.expected_products) {
        throw new Error(
          `Medusa product count changed during pagination (${summary.expected_products} -> ${responseCount})`,
        );
      }
      if (!rawProducts.length) break;

      const page = normalizePage(rawProducts);
      for (const product of page.products) {
        if (seenProductIds.has(product.id)) {
          throw new Error(`Medusa pagination returned duplicate product ${product.id}`);
        }
        seenProductIds.add(product.id);
      }
      if (runRecorded) await syncPage(client, runId, page);
      summary.processed_products += page.products.length;
      summary.processed_variants += page.variants.length;
      summary.processed_images += page.images.length;
      page.categories.forEach((category) => summary.categories.add(category.id));
      offset += rawProducts.length;
      summary.next_offset = offset;
      console.log(JSON.stringify({
        event: "page_synced",
        mode: summary.mode,
        scope: summary.scope,
        offset,
        products: summary.processed_products,
        expected: summary.expected_products,
      }));

      if (rawProducts.length < requestedPageSize) break;
      if (summary.expected_products !== null && offset >= summary.expected_products) break;
      if (offset > 1_000_000) throw new Error("Product pagination safety limit exceeded");
    }

    const isWindowComplete = args.offset === 0
      && args.limit === null
      && countPresentOnEveryPage
      && summary.expected_products !== null
      && summary.processed_products === summary.expected_products
      && seenProductIds.size === summary.expected_products
      && summary.next_offset === summary.expected_products;
    if (args.markMissingInactive && !isWindowComplete) {
      throw new Error("Refusing to mark rows inactive because the source run was incomplete");
    }
    const serializableSummary = {
      ...summary,
      categories: summary.categories.size,
      window_complete: isWindowComplete,
      complete: args.full && isWindowComplete,
      checkpoint_eligible: isWindowComplete,
    };
    const finalSummary = runRecorded
      ? await completeRun(client, runId, serializableSummary, args.markMissingInactive)
      : serializableSummary;
    console.log(JSON.stringify({ event: "run_completed", ...finalSummary }));
  } catch (error) {
    if (runRecorded) await recordFailedRun(client, runId, error);
    throw error;
  } finally {
    if (client) await closePostgres(client, advisoryLockHeld);
  }
}

main().catch((error) => {
  console.error(error instanceof Error ? error.message : String(error));
  process.exitCode = 1;
});
