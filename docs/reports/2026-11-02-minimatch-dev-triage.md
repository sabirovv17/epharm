# Temporary minimatch 3.1.5 development-dependency triage

The npm advisory range flags all versions below 11.0.1. This decision accepts only
the reviewed legacy **3.1.5 development package**, until the existing deadline
**2026-11-06T00:00:00Z**. It does not extend that deadline or accept a production
finding. Modern minimatch 10 is excluded; its storefront dependency was updated
separately to 11.0.4.

## Evidence and interpretation

- The [maintainer advisory](https://github.com/isaacs/minimatch/security/advisories/GHSA-g3hv-75wg-m249)
  identifies the 10.2.6 optimization pipeline. The
  [reviewed database entry](https://github.com/advisories/GHSA-g3hv-75wg-m249)
  has the broader version range.
- The [upstream fix](https://github.com/isaacs/minimatch/commit/77a0f5c4b80bdc641c3c930a1d9edf5ad2db57f8)
  bounds first-phase expansion and subsequent pairwise comparisons. The
  [official 3.1.5 implementation](https://github.com/isaacs/minimatch/blob/v3.1.5/minimatch.js)
  has neither optimization phase nor `optimizationLevel` handling.
- Registry tarball integrity matched the lockfile. Its executable source was
  byte-identical to the official tag: 27,460 bytes, SHA-256
  `746849f267c4868a53729225bd85c7dccd387712136fb305fcc0a3eadd4a3d9d`.
- In an isolated installation, the upstream attack pattern with 30 through 6,000
  repeated segments and optimization level 2 retained one pattern entry. The
  59,999-character case compiled in approximately 3 ms under a bounded process.

Inference: the described amplification mechanism is absent from these exact
legacy bytes. This is not an upstream correction to the advisory range or a
claim that every possible minimatch issue is absent. Verified ESLint plugin
sources call the package as a function; replacing that export with minimatch 11's
object would break their API.

## Enforced boundary

`tools/check-frontend-audit.mjs` requires all of the following:

- Production audit has zero findings, regardless of severity or advisory.
- Only this exact advisory URL and/or the previously reviewed braces advisory
  remain; the existing braces 3.0.3 check is preserved.
- Every finding node, including propagated tooling findings, is `dev: true`.
- Every affected minimatch node is version 3.1.5 with registry SRI
  `sha512-VgjWUsnnT6n+NUk6eZq77zeFdpW2LWDzP6zFGrCbHXiYNul5Dzqk2HHQ5uFH2DNW5Xbp8+jVzaeNt94ssEEl4w==`.
- Each installed `minimatch.js` has the reviewed source hash. Additional nodes
  must meet the same conditions; missing, unsafe or changed paths fail closed.
- Both temporary exceptions expire at the unchanged deadline above.

The storefront CI runs `node --test ../tools/check-frontend-audit.test.mjs` after
`npm ci` and before the audit gate. Fixtures copy the actual installed reviewed
source; negative cases cover production use, modern/changed versions, SRI and
source changes, additional nodes, unknown advisories, missing data and expiry.
