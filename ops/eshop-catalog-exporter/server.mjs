import { createServer } from 'node:http';
import pg from 'pg';
import { createHandler } from './catalog.mjs';

const token = process.env.EXPORT_TOKEN;
const connectionString = process.env.DATABASE_URL;
if (!connectionString) throw new Error('DATABASE_URL is required');
if (!token) throw new Error('EXPORT_TOKEN is required');
const pool = new pg.Pool({
  connectionString,
  max: 2,
  connectionTimeoutMillis: 5_000,
  idleTimeoutMillis: 30_000,
  application_name: 'epharm-eshop-catalog-exporter',
});
const server = createServer(createHandler(pool, token));
server.requestTimeout = 190_000;
server.headersTimeout = 10_000;
server.listen(8080, '0.0.0.0');

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.once(signal, () => {
    server.close(() => pool.end().finally(() => process.exit(0)));
    setTimeout(() => process.exit(1), 10_000).unref();
  });
}
