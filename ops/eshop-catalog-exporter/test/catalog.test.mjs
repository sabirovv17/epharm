import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createServer } from 'node:http';
import { afterEach, test } from 'node:test';
import { createHandler, projectProduct } from '../catalog.mjs';

const TOKEN = 'test-token-with-at-least-thirty-two-characters';
const CATALOG_RUN = '11111111-1111-4111-8111-111111111111';
const AVAILABILITY_RUN = '22222222-2222-4222-8222-222222222222';
const HEADER_ROW = {
  catalog_run_id: CATALOG_RUN,
  catalog_count: 2,
  catalog_generated_at: '2026-10-09T22:37:41.026Z',
  availability_run_id: AVAILABILITY_RUN,
  availability_finished_at: '2026-10-10T12:53:54.173Z',
  availability_valid_until: '2026-10-10T14:53:54.173Z',
};
const ROWS = [
  {
    sku: '100', product_id: 'prod_DaribarMTAw', variant_id: 'variant_DaribarMTAw',
    product: {
      id: 'prod_DaribarMTAw', variantId: 'variant_DaribarMTAw', sku: '100',
      source: 'daribar', price: 0, priceTBD: true, inStock: false,
      stockPharmacies: 0, variants: [{ id: 'variant_DaribarMTAw', sku: '100' }],
    },
    price_amount: null, in_stock: true, min_price: '25.00', pharmacy_count: 2,
    checked_at: '2026-10-10T12:53:54.173Z',
    alias_ids: ['prod_OLD2', 'prod_OLD1', 'prod_OLD2'],
  },
  {
    sku: '200', product_id: 'prod_DaribarMjAw', variant_id: 'variant_DaribarMjAw',
    product: {
      id: 'prod_DaribarMjAw', variantId: 'variant_DaribarMjAw', sku: '200',
      source: 'daribar', price: 0, priceTBD: true, inStock: false,
      stockPharmacies: 0, variants: [{ id: 'variant_DaribarMjAw', sku: '200' }],
    },
    price_amount: null, in_stock: false, min_price: null, pharmacy_count: 0,
    checked_at: '2026-10-10T12:53:54.173Z', alias_ids: [],
  },
];

function fakePool(rows = ROWS, header = HEADER_ROW) {
  const calls = [];
  let releaseCount = 0;
  const query = async (sql, params) => {
    calls.push({ sql, params });
    if (sql.includes('FROM daribar_catalog_state state')) return { rows: header ? [header] : [] };
    if (sql.includes('FROM daribar_catalog_products product')) {
      const after = params[2];
      return { rows: rows.filter((row) => row.sku > after).slice(0, params[3]) };
    }
    return { rows: [] };
  };
  return {
    calls,
    get releaseCount() { return releaseCount; },
    query,
    async connect() { return { query, release() { releaseCount += 1; } }; },
  };
}

const servers = [];
afterEach(async () => {
  for (const server of servers.splice(0)) await new Promise((resolve) => server.close(resolve));
});

async function listen(pool) {
  const server = createServer(createHandler(pool, TOKEN, { now: () => Date.parse('2026-10-10T13:00:00Z') }));
  servers.push(server);
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  return `http://127.0.0.1:${server.address().port}`;
}

test('token is mandatory at startup and both routes reject unauthorized callers', async () => {
  assert.throws(() => createHandler(fakePool(), ''), /EXPORT_TOKEN/);
  const pool = fakePool();
  const base = await listen(pool);
  for (const path of ['/health', '/v1/snapshot']) {
    assert.equal((await fetch(base + path)).status, 401);
    assert.equal((await fetch(base + path, {
      headers: { authorization: 'Bearer wrong' },
    })).status, 401);
  }
  assert.equal(pool.calls.length, 0);
});

test('health reads published run metadata without streaming products', async () => {
  const pool = fakePool();
  const base = await listen(pool);
  const response = await fetch(base + '/health', { headers: { authorization: `Bearer ${TOKEN}` } });
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), {
    status: 'ok', schemaVersion: 1, catalogRunId: CATALOG_RUN,
    catalogCount: 2, availabilityRunId: AVAILABILITY_RUN,
  });
  assert.equal(pool.calls.length, 1);
});

test('health fails closed when no published catalog exists', async () => {
  const base = await listen(fakePool(ROWS, null));
  const response = await fetch(base + '/health', { headers: { authorization: `Bearer ${TOKEN}` } });
  assert.equal(response.status, 503);
});

test('snapshot streams one consistent version and exact product-line digest', async () => {
  const pool = fakePool();
  const base = await listen(pool);
  const response = await fetch(base + '/v1/snapshot', { headers: { authorization: `Bearer ${TOKEN}` } });
  assert.equal(response.status, 200);
  assert.match(response.headers.get('content-type'), /^application\/x-ndjson/);
  const body = await response.text();
  const lines = body.trimEnd().split('\n');
  assert.equal(lines.length, 4);
  const [header, first, second, footer] = lines.map((line) => JSON.parse(line));
  assert.deepEqual([header.type, first.type, second.type, footer.type], ['header', 'product', 'product', 'footer']);
  assert.equal(header.catalogRunId, CATALOG_RUN);
  assert.equal(header.availabilityRunId, AVAILABILITY_RUN);
  assert.equal(first.published, true);
  assert.equal(first.priceAmount, 25);
  assert.equal(first.product.price, 25);
  assert.deepEqual(first.aliasIds, ['prod_OLD1', 'prod_OLD2']);
  assert.equal(second.published, false);
  assert.equal(second.priceAmount, null);
  assert.equal(footer.productCount, 2);
  assert.equal(footer.sha256, createHash('sha256').update(lines[1] + '\n' + lines[2] + '\n').digest('hex'));
  assert.ok(pool.calls.some((call) => call.sql.startsWith('BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY')));
  assert.ok(pool.calls.some((call) => call.sql === 'COMMIT'));
  assert.equal(pool.releaseCount, 1);
});

test('snapshot fails closed before headers if published metadata is missing', async () => {
  const pool = fakePool(ROWS, null);
  const base = await listen(pool);
  const response = await fetch(base + '/v1/snapshot', { headers: { authorization: `Bearer ${TOKEN}` } });
  assert.equal(response.status, 503);
  assert.ok(pool.calls.some((call) => call.sql === 'ROLLBACK'));
  assert.equal(pool.releaseCount, 1);
});

test('availability overrides base price; stale data is labelled without hiding a priced card', () => {
  const header = {
    availabilityRunId: AVAILABILITY_RUN,
    availabilityValidUntil: '2026-10-10T12:00:00Z',
  };
  const priced = projectProduct({ ...ROWS[0], price_amount: '100' }, header, Date.parse('2026-10-10T13:00:00Z'));
  assert.equal(priced.priceAmount, 25);
  assert.equal(priced.product.stockStale, true);
  assert.equal(priced.published, true);
  const baseOnly = projectProduct({ ...ROWS[0], in_stock: false, min_price: null, price_amount: '100' }, header);
  assert.equal(baseOnly.priceAmount, 100);
  assert.equal(baseOnly.published, true);
});
