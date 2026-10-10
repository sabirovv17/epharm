import { createHash, timingSafeEqual } from 'node:crypto';

export const SCHEMA_VERSION = 1;
export const PAGE_SIZE = 500;
const TOKEN_MIN_LENGTH = 32;
const EXPORT_TIMEOUT_MS = 180_000;

export const HEADER_SQL = `
  SELECT catalog.id AS catalog_run_id,
         catalog.normalized_count AS catalog_count,
         catalog.generated_at AS catalog_generated_at,
         availability.id AS availability_run_id,
         availability.finished_at AS availability_finished_at,
         availability.valid_until AS availability_valid_until
  FROM daribar_catalog_state state
  JOIN daribar_catalog_runs catalog
    ON catalog.id = state.active_run_id AND catalog.status = 'published'
  LEFT JOIN daribar_availability_state availability_state ON availability_state.singleton
  LEFT JOIN daribar_availability_runs availability
    ON availability.id = availability_state.active_run_id
   AND availability.status = 'published'
   AND availability.catalog_run_id = catalog.id
  WHERE state.singleton
  LIMIT 1
`;

export const PRODUCT_PAGE_SQL = `
  SELECT product.sku, product.product_id, product.variant_id,
         product.product, product.price_amount,
         availability.in_stock, availability.min_price,
         availability.pharmacy_count, availability.checked_at,
         COALESCE(aliases.ids, ARRAY[]::text[]) AS alias_ids
  FROM daribar_catalog_products product
  LEFT JOIN daribar_product_availability availability
    ON availability.run_id = $2::uuid AND availability.sku = product.sku
  LEFT JOIN LATERAL (
    SELECT array_agg(DISTINCT mapping.product_id ORDER BY mapping.product_id) AS ids
    FROM daribar_delivery_product_mappings mapping
    WHERE mapping.enabled AND upper(mapping.sku) = upper(product.sku)
  ) aliases ON true
  WHERE product.run_id = $1::uuid AND product.sku > $3::text
  ORDER BY product.sku
  LIMIT $4::integer
`;

function iso(value) {
  if (value == null) return null;
  const date = value instanceof Date ? value : new Date(value);
  if (!Number.isFinite(date.getTime())) throw new Error('invalid_source_timestamp');
  return date.toISOString();
}

function positiveNumber(value) {
  if (value == null) return null;
  const number = Number(value);
  return Number.isFinite(number) && number > 0 && number < 1_000_000_000 ? number : null;
}

function sourceHeader(row) {
  if (!row || typeof row.catalog_run_id !== 'string') throw new Error('catalog_unavailable');
  const catalogCount = Number(row.catalog_count);
  if (!Number.isSafeInteger(catalogCount) || catalogCount < 1 || catalogCount > 200_000) {
    throw new Error('invalid_catalog_count');
  }
  return {
    type: 'header',
    schemaVersion: SCHEMA_VERSION,
    catalogRunId: row.catalog_run_id,
    availabilityRunId: row.availability_run_id ?? null,
    catalogCount,
    catalogGeneratedAt: iso(row.catalog_generated_at),
    availabilityFinishedAt: iso(row.availability_finished_at),
    availabilityValidUntil: iso(row.availability_valid_until),
  };
}

export function projectProduct(row, header, now = Date.now()) {
  if (!row || typeof row.sku !== 'string' || !/^[A-Za-z0-9._:-]{1,96}$/.test(row.sku)) {
    throw new Error('invalid_catalog_sku');
  }
  if (!row.product || typeof row.product !== 'object' || Array.isArray(row.product)
      || row.product.id !== row.product_id || row.product.variantId !== row.variant_id
      || row.product.sku !== row.sku || row.product.source !== 'daribar') {
    throw new Error('invalid_catalog_product_identity');
  }
  const basePrice = positiveNumber(row.price_amount);
  const availabilityPrice = positiveNumber(row.min_price);
  const pharmacyCount = Number(row.pharmacy_count);
  const hasAvailability = Boolean(header.availabilityRunId && header.availabilityValidUntil
    && row.checked_at != null);
  const available = hasAvailability && row.in_stock === true && availabilityPrice != null
    && Number.isSafeInteger(pharmacyCount) && pharmacyCount > 0;
  const priceAmount = available ? availabilityPrice : basePrice;
  const product = { ...row.product };
  if (hasAvailability) {
    product.inStock = available;
    product.stockPharmacies = available ? pharmacyCount : 0;
    product.stockSourceDate = iso(row.checked_at);
    product.stockValidUntil = header.availabilityValidUntil;
    product.stockStale = Date.parse(header.availabilityValidUntil) <= now;
    if (available) {
      product.price = availabilityPrice;
      product.priceTBD = false;
      if (Array.isArray(product.variants)) {
        product.variants = product.variants.map((variant) => ({ ...variant, price: availabilityPrice }));
      }
    }
  } else {
    product.inStock = false;
    product.stockPharmacies = 0;
    product.stockStale = true;
  }
  if (!Array.isArray(row.alias_ids) || row.alias_ids.some((value) =>
    typeof value !== 'string' || !/^prod_[A-Za-z0-9_-]{1,120}$/.test(value))) {
    throw new Error('invalid_catalog_alias');
  }
  const aliasIds = [...new Set(row.alias_ids)].sort();
  return {
    type: 'product',
    sku: row.sku,
    productId: row.product_id,
    variantId: row.variant_id,
    product,
    priceAmount,
    published: Boolean(row.variant_id && priceAmount != null),
    aliasIds,
  };
}

function sendJson(response, status, body, extraHeaders = {}) {
  response.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store',
    'x-content-type-options': 'nosniff',
    ...extraHeaders,
  });
  response.end(JSON.stringify(body));
}

function authenticated(request, tokenDigest) {
  const authorization = request.headers.authorization;
  if (typeof authorization !== 'string' || !authorization.startsWith('Bearer ')) return false;
  const supplied = authorization.slice(7);
  if (!supplied || supplied.length > 8192) return false;
  const digest = createHash('sha256').update(supplied).digest();
  return timingSafeEqual(digest, tokenDigest);
}

async function writeLine(response, line) {
  if (response.destroyed) throw new Error('client_disconnected');
  if (response.write(line)) return;
  await new Promise((resolve, reject) => {
    const drained = () => { cleanup(); resolve(); };
    const closed = () => { cleanup(); reject(new Error('client_disconnected')); };
    const cleanup = () => {
      response.off('drain', drained);
      response.off('close', closed);
      response.off('error', closed);
    };
    response.once('drain', drained);
    response.once('close', closed);
    response.once('error', closed);
  });
}

export function createHandler(pool, token, { now = () => Date.now() } = {}) {
  if (typeof token !== 'string' || token.length < TOKEN_MIN_LENGTH) {
    throw new Error('EXPORT_TOKEN must contain at least 32 characters');
  }
  const tokenDigest = createHash('sha256').update(token).digest();
  let exporting = false;

  return async function handler(request, response) {
    if (!authenticated(request, tokenDigest)) {
      sendJson(response, 401, { error: 'unauthorized' }, { 'www-authenticate': 'Bearer' });
      return;
    }
    const path = new URL(request.url ?? '/', 'http://localhost').pathname;
    if (request.method !== 'GET') {
      sendJson(response, 405, { error: 'method_not_allowed' }, { allow: 'GET' });
      return;
    }
    if (path === '/health') {
      try {
        const result = await pool.query(HEADER_SQL);
        const header = sourceHeader(result.rows[0]);
        sendJson(response, 200, {
          status: 'ok', schemaVersion: SCHEMA_VERSION,
          catalogRunId: header.catalogRunId, catalogCount: header.catalogCount,
          availabilityRunId: header.availabilityRunId,
        });
      } catch {
        sendJson(response, 503, { status: 'unavailable', schemaVersion: SCHEMA_VERSION });
      }
      return;
    }
    if (path !== '/v1/snapshot') {
      sendJson(response, 404, { error: 'not_found' });
      return;
    }
    if (exporting) {
      sendJson(response, 429, { error: 'export_in_progress' }, { 'retry-after': '30' });
      return;
    }
    exporting = true;
    let client;
    let inTransaction = false;
    const timeout = setTimeout(() => response.destroy(new Error('export_timeout')), EXPORT_TIMEOUT_MS);
    timeout.unref();
    try {
      client = await pool.connect();
      await client.query('BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY');
      inTransaction = true;
      await client.query("SET LOCAL statement_timeout = '10000ms'");
      await client.query("SET LOCAL idle_in_transaction_session_timeout = '30000ms'");
      const header = sourceHeader((await client.query(HEADER_SQL)).rows[0]);
      response.writeHead(200, {
        'content-type': 'application/x-ndjson; charset=utf-8',
        'cache-control': 'no-store',
        'x-content-type-options': 'nosniff',
        'x-catalog-run-id': header.catalogRunId,
      });
      await writeLine(response, `${JSON.stringify(header)}\n`);
      const digest = createHash('sha256');
      let productCount = 0;
      let afterSku = '';
      const snapshotNow = now();
      while (true) {
        const page = await client.query(PRODUCT_PAGE_SQL, [
          header.catalogRunId, header.availabilityRunId, afterSku, PAGE_SIZE,
        ]);
        if (page.rows.length === 0) break;
        for (const row of page.rows) {
          const product = projectProduct(row, header, snapshotNow);
          const line = `${JSON.stringify(product)}\n`;
          digest.update(line);
          await writeLine(response, line);
          afterSku = row.sku;
          productCount += 1;
          if (productCount > header.catalogCount) throw new Error('catalog_count_exceeded');
        }
      }
      if (productCount !== header.catalogCount) throw new Error('catalog_count_mismatch');
      await client.query('COMMIT');
      inTransaction = false;
      await writeLine(response, `${JSON.stringify({
        type: 'footer', productCount, sha256: digest.digest('hex'),
      })}\n`);
      response.end();
    } catch {
      if (inTransaction && client) await client.query('ROLLBACK').catch(() => undefined);
      if (!response.destroyed) {
        if (!response.headersSent) sendJson(response, 503, { error: 'snapshot_unavailable' });
        else response.destroy();
      }
    } finally {
      clearTimeout(timeout);
      client?.release();
      exporting = false;
    }
  };
}
