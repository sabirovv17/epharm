import assert from 'node:assert/strict';
import { copyFileSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { verifyFindings } from './check-frontend-audit.mjs';

const minimatch = 'https://github.com/advisories/GHSA-g3hv-75wg-m249';
const braces = 'https://github.com/advisories/GHSA-vfj7-8cjw-p6xm';
const beforeExpiry = Date.parse('2026-11-02T00:00:00Z');
const reviewedPackage = JSON.parse(readFileSync(new URL('../storefront/package-lock.json', import.meta.url), 'utf8'))
  .packages['node_modules/minimatch'];
const reviewedSource = new URL('../storefront/node_modules/minimatch/minimatch.js', import.meta.url);

function fixture(t) {
  const root = mkdtempSync(join(tmpdir(), 'epharm-audit-test-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  mkdirSync(join(root, 'node_modules/minimatch'), { recursive: true });
  copyFileSync(reviewedSource, join(root, 'node_modules/minimatch/minimatch.js'));
  const lock = { packages: {
    'node_modules/minimatch': { ...reviewedPackage },
    'node_modules/eslint': { dev: true },
    'node_modules/braces': { version: '3.0.3', dev: true },
  } };
  const findings = {
    minimatch: { via: [{ url: minimatch }], nodes: ['node_modules/minimatch'] },
    eslint: { via: ['minimatch'], nodes: ['node_modules/eslint'] },
  };
  const verify = (production = {}, now = beforeExpiry) =>
    verifyFindings(production, findings, lock, now, (name) => readFileSync(join(root, name)));
  return { root, lock, findings, verify };
}

test('accepts only the actual reviewed installed legacy package, alone or with the unchanged braces triage', (t) => {
  const f = fixture(t);
  assert.deepEqual(f.verify(), [minimatch]);
  f.findings.braces = { via: [{ url: braces }], nodes: ['node_modules/braces'] };
  assert.deepEqual(new Set(f.verify()), new Set([minimatch, braces]));
  delete f.findings.minimatch;
  delete f.findings.eslint;
  assert.deepEqual(f.verify(), [braces]);
  f.lock.packages['node_modules/braces'].version = '3.0.4';
  assert.throws(() => f.verify(), /braces version changed/);
});

test('production findings remain forbidden even when their advisory and source are reviewed', (t) => {
  const f = fixture(t);
  assert.throws(() => f.verify({ minimatch: f.findings.minimatch }), /production dependencies/);
});

for (const version of ['3.1.4', '10.2.5', '11.0.4']) {
  test(`rejects unreviewed version ${version} despite matching source and integrity`, (t) => {
    const f = fixture(t);
    f.lock.packages['node_modules/minimatch'].version = version;
    assert.throws(() => f.verify(), /reviewed dev-only 3\.1\.5/);
  });
}

test('rejects changed tarball integrity or installed source bytes', (t) => {
  const f = fixture(t);
  f.lock.packages['node_modules/minimatch'].integrity = 'sha512-unreviewed';
  assert.throws(() => f.verify(), /reviewed dev-only 3\.1\.5/);
  f.lock.packages['node_modules/minimatch'].integrity = reviewedPackage.integrity;
  writeFileSync(join(f.root, 'node_modules/minimatch/minimatch.js'), 'module.exports = () => true');
  assert.throws(() => f.verify(), /source differs/);
});

test('every direct and propagated finding must be confined to dev dependencies', (t) => {
  const f = fixture(t);
  f.lock.packages['node_modules/minimatch'].dev = false;
  assert.throws(() => f.verify(), /reviewed dev-only/);
  f.lock.packages['node_modules/minimatch'].dev = true;
  f.lock.packages['node_modules/eslint'].dev = false;
  assert.throws(() => f.verify(), /not confined to dev/);
});

test('unknown advisories, missing packages and empty finding nodes fail closed', (t) => {
  const f = fixture(t);
  f.findings.minimatch.via.push({ url: 'https://github.com/advisories/GHSA-unknown' });
  assert.throws(() => f.verify(), /untriaged audit/);
  f.findings.minimatch.via.pop();
  delete f.lock.packages['node_modules/minimatch'];
  assert.throws(() => f.verify(), /reviewed dev-only/);
  f.findings.minimatch.nodes = [];
  assert.throws(() => f.verify(), /no auditable dependency nodes/);
});

test('additional minimatch nodes require the same reviewed package and source', (t) => {
  const f = fixture(t);
  const nested = 'node_modules/consumer/node_modules/minimatch';
  f.findings.minimatch.nodes.push(nested);
  f.lock.packages[nested] = { ...reviewedPackage };
  mkdirSync(join(f.root, nested), { recursive: true });
  copyFileSync(reviewedSource, join(f.root, nested, 'minimatch.js'));
  assert.deepEqual(f.verify(), [minimatch]);
  f.lock.packages[nested].version = '10.2.5';
  assert.throws(() => f.verify(), /reviewed dev-only/);
});

test('unsafe finding paths and missing installed source cannot be accepted', (t) => {
  const f = fixture(t);
  const unsafe = 'node_modules/../../node_modules/minimatch';
  f.findings.minimatch.nodes = [unsafe];
  f.lock.packages[unsafe] = { ...reviewedPackage };
  assert.throws(() => f.verify(), /reviewed dev-only/);
  f.findings.minimatch.nodes = ['node_modules/minimatch'];
  rmSync(join(f.root, 'node_modules/minimatch/minimatch.js'));
  assert.throws(() => f.verify(), /ENOENT/);
});

test('both exceptions expire at the existing November 6 boundary; zero findings stay valid', (t) => {
  const f = fixture(t);
  const expiry = Date.parse('2026-11-06T00:00:00Z');
  assert.deepEqual(f.verify({}, expiry - 1), [minimatch]);
  assert.throws(() => f.verify({}, expiry), /triage expired/);
  delete f.findings.minimatch;
  delete f.findings.eslint;
  f.findings.braces = { via: [{ url: braces }], nodes: ['node_modules/braces'] };
  assert.throws(() => f.verify({}, expiry), /triage expired/);
  assert.deepEqual(verifyFindings({}, {}, f.lock, expiry), []);
});
