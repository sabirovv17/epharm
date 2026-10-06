#!/usr/bin/env node
// Temporary, fail-closed triage for an unpatched, build-time-only advisory.
// Production dependencies must always have zero npm audit findings.
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';

const allowedAdvisory = 'https://github.com/advisories/GHSA-vfj7-8cjw-p6xm';
const expiresAt = Date.parse('2026-11-06T00:00:00Z');

function audit(extraArgs) {
  const result = spawnSync('npm', ['audit', '--json', ...extraArgs], {
    encoding: 'utf8',
    maxBuffer: 16 * 1024 * 1024,
  });
  if (result.error || ![0, 1].includes(result.status)) {
    throw new Error(`npm audit failed to run (${result.status ?? result.error?.message})`);
  }
  let report;
  try {
    report = JSON.parse(result.stdout);
  } catch {
    throw new Error('npm audit did not return valid JSON');
  }
  if (report.error || report.auditReportVersion !== 2 || !report.vulnerabilities) {
    throw new Error('npm audit returned an incomplete report');
  }
  return report.vulnerabilities;
}

try {
  const production = audit(['--omit=dev']);
  if (Object.keys(production).length !== 0) {
    throw new Error(`production dependencies have ${Object.keys(production).length} findings`);
  }

  const findings = audit([]);
  const entries = Object.entries(findings);
  if (entries.length === 0) {
    console.log('npm audit: zero vulnerabilities (production and full tree)');
    process.exit(0);
  }
  if (Date.now() >= expiresAt) {
    throw new Error('the temporary dev-only braces triage expired; upgrade/review required');
  }

  const directAdvisories = new Set(entries.flatMap(([, finding]) =>
    finding.via.filter((via) => typeof via !== 'string').map((via) => via.url)));
  if (directAdvisories.size !== 1 || !directAdvisories.has(allowedAdvisory) || !findings.braces) {
    throw new Error(`untriaged audit advisory: ${[...directAdvisories].join(', ')}`);
  }

  const lock = JSON.parse(readFileSync('package-lock.json', 'utf8'));
  if (lock.packages?.['node_modules/braces']?.version !== '3.0.3') {
    throw new Error('braces version changed; repeat the security triage');
  }
  for (const [name, finding] of entries) {
    if (!Array.isArray(finding.nodes) || finding.nodes.length === 0) {
      throw new Error(`${name} has no auditable dependency nodes`);
    }
    for (const node of finding.nodes) {
      if (lock.packages?.[node]?.dev !== true) {
        throw new Error(`${name} is not confined to dev dependencies at ${node}`);
      }
    }
  }
  console.warn(`npm audit: only dev-only ${allowedAdvisory} remains; narrow triage expires 2026-11-06`);
} catch (error) {
  console.error(`npm audit gate failed: ${error.message}`);
  process.exitCode = 1;
}
