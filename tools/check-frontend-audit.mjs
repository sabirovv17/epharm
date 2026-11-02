#!/usr/bin/env node
// Temporary, fail-closed triage for reviewed build-time advisories.
// Production dependencies must always have zero npm audit findings.
import { spawnSync } from 'node:child_process';
import { readFileSync, realpathSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { pathToFileURL } from 'node:url';

const bracesAdvisory = 'https://github.com/advisories/GHSA-vfj7-8cjw-p6xm';
const minimatchAdvisory = 'https://github.com/advisories/GHSA-g3hv-75wg-m249';
const expiresAt = Date.parse('2026-11-06T00:00:00Z');
const minimatchIntegrity = 'sha512-VgjWUsnnT6n+NUk6eZq77zeFdpW2LWDzP6zFGrCbHXiYNul5Dzqk2HHQ5uFH2DNW5Xbp8+jVzaeNt94ssEEl4w==';
const minimatchSourceHash = '746849f267c4868a53729225bd85c7dccd387712136fb305fcc0a3eadd4a3d9d';

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

export function verifyFindings(production, findings, lock, now = Date.now(), read = readFileSync) {
  if (Object.keys(production).length !== 0) {
    throw new Error(`production dependencies have ${Object.keys(production).length} findings`);
  }
  const entries = Object.entries(findings);
  if (entries.length === 0) return [];
  if (now >= expiresAt) {
    throw new Error('the temporary dev-only triage expired; upgrade/review required');
  }

  const directAdvisories = new Set(entries.flatMap(([, finding]) =>
    finding.via.filter((via) => typeof via !== 'string').map((via) => via.url)));
  if (directAdvisories.size === 0 || [...directAdvisories].some((url) =>
    url !== bracesAdvisory && url !== minimatchAdvisory)) {
    throw new Error(`untriaged audit advisory: ${[...directAdvisories].join(', ')}`);
  }
  if (directAdvisories.has(bracesAdvisory) && (!findings.braces ||
    lock.packages?.['node_modules/braces']?.version !== '3.0.3')) {
    throw new Error('braces version changed; repeat the security triage');
  }
  if (directAdvisories.has(minimatchAdvisory)) {
    const nodes = findings.minimatch?.nodes;
    if (!Array.isArray(nodes) || nodes.length === 0) throw new Error('minimatch has no auditable dependency nodes');
    for (const node of nodes) {
      const dependency = lock.packages?.[node];
      if (!/^node_modules\/(?:[^/]+\/)*minimatch$/.test(node) || node.split('/').some((part) => part === '..' || part === '.') ||
        node.includes('\\') || dependency?.dev !== true || dependency.version !== '3.1.5' || dependency.integrity !== minimatchIntegrity) {
        throw new Error('minimatch differs from the reviewed dev-only 3.1.5 package');
      }
      // Only this legacy implementation lacks the advisory's two optimization phases; see the dated triage.
      if (createHash('sha256').update(read(`${node}/minimatch.js`)).digest('hex') !== minimatchSourceHash) {
        throw new Error('minimatch source differs from the reviewed legacy implementation');
      }
    }
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
  return [...directAdvisories];
}

if (process.argv[1] && import.meta.url === pathToFileURL(realpathSync(process.argv[1])).href) {
  try {
    const production = audit(['--omit=dev']);
    if (Object.keys(production).length !== 0) throw new Error(`production dependencies have ${Object.keys(production).length} findings`);
    const findings = audit([]);
    const lock = JSON.parse(readFileSync('package-lock.json', 'utf8'));
    const accepted = verifyFindings(production, findings, lock);
    if (accepted.length === 0) console.log('npm audit: zero vulnerabilities (production and full tree)');
    else console.warn(`npm audit: reviewed dev-only ${accepted.join(', ')}; narrow triage expires 2026-11-06`);
  } catch (error) {
    console.error(`npm audit gate failed: ${error.message}`);
    process.exitCode = 1;
  }
}
