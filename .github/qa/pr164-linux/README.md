# Private PR164 Linux acceptance extension

This directory is a **harness deliverable only**. No Docker, VM, server or application was executed locally while preparing it. It never changes product source or production configuration. Original private CI43157241311 remains a recorded failed registration latency gate (p95=2110.50ms versus<2000ms); it is not replaced by a later result.

## One controlled same-host registration comparison

The first normal old15 stop is intercepted once, after normal Flyway99 and seed
verification with zero pharmacist profiles. `paired_registration.py` saves an
owner-only private SQL dump of that exact fresh fixture, then calls the unchanged
registration phase against official15, on the same hosted runner and prescribed
resource caps. Reference PASS or FAIL remains explicitly recorded under
`pairedRegistration15`; a completed threshold failure is diagnostic only and is
never relabelled PASS. Missing/incomplete reference evidence, infrastructure error,
OOM or ownership mismatch aborts the experiment.

After stopping old15, restore checks the exact recorded PG/Redis container IDs,
owner labels, internal network ID and membership, no published ports, generated
database identity, and unchanged owner-only dump digest. Only that disposable
database is restored; only its owned Redis logical DB0 is reset. Full public table
data, canonical schema, schema99 history, sequence counters and zero pharmacist/
device-session counts must exactly equal the saved baseline. Redis DB0 must have
zero keys; another populated logical DB is refused. Snapshot SQL/auth data never
enter uploaded artifacts.

Official16 then cold-starts normally. All original500-fresh-desk registration,
login/session, learner JWT/status and15→16→15→16 acceptance gates run once with no
threshold/resource/rate-limit changes and no candidate auth warmup. Reference
summary uses `results/rps-reference15-register-*.json`; unchanged candidate globs
remain `results/rps-register-*.json`. Numeric CPU family/model/stepping, CPU limits,
cgroup deltas, sampled resources and Hikari observations are retained for both,
including a failed k6 exit. A candidate failure stops the run; there is no rerun
loop or automatic acceptance retry.

## Owned-container placement and failure taxonomy

The next run narrows only our containers' CPU affinity: backend uses exactly two
available logical CPUs; PostgreSQL, Redis and both generators use two other
available logical CPUs. Fewer than four available CPUs is refused. All original
`--cpus` quotas, memory/JVM/Hikari settings, workloads, rates and thresholds remain
unchanged. This neither allocates physical cores nor prevents host/hypervisor work
on those CPUs. BE/PG/Redis actual CPU quota/memory/cpuset are inspected before
business load. Generator creation flags enforce the same quotas plus cpuset;
actual generator configuration is inspected at the existing first polling
opportunity **after start**. Original `docker run --rm`/stdin/exit semantics are
retained, and this harness never uses `docker update`.

Before cleanup, phase diagnostics retain CPU/cgroup/Hikari observations and JVM GC
numeric counters/phase-end rolling maxima. Prometheus HTTP counter deltas export
only fixed route buckets, numeric status and allowlisted exception buckets; this
distinguishes expected503 admission refusals from unhandled500. Backend logs are
parsed only into fixed exception-class counts; messages, raw URIs, HTTP bodies and
credentials never enter reports. Log class occurrences are not request counts,
and handled `AppException`503 may produce no backend error log. Raw logs remain
private and excluded from upload. One next paired run is permitted; another
candidate failure requires escalation, with previous failed runs retained.

Snapshot diagnostics preserve before/after hashes, counts and failed field names
before the unchanged strict comparisons. Safe failure locations contain only
helper basename/function/line. Optional dump diagnostics hash intact complete
object blocks in sorted order, with header/footer hashes separately; they never
replace the primary order-sensitive `ddlSha256` equality or expose SQL. Run43162105153
had a valid reference15 PASS and then an unlocalized snapshot assertion before16;
it is retained as a fixture blocker, not a candidate performance failure.

## Root integration interface

Copy these helpers to `.github/qa/pr164-linux/` on a disposable QA branch. Do not copy the old QA `ci.yml`, disable mandatory checks, or merge this harness branch. Root owns the workflow, artifact coordinates, all downloads and launch. `job-snippet.yml` is a job fragment, not a complete workflow or authorization.

Create `artifact-inputs.json` in that directory:

```json
{
  "previous": {
    "version": "v0.2.15",
    "commit": "464352af46872de6edcb474b5b78390325a4b076",
    "imagesRun": 43010008595,
    "artifactId": 13850451121
  },
  "candidate": {
    "version": "v0.2.16",
    "commit": "ROOT_SUPPLIES_EXACT_MERGED_MAIN_40_HEX",
    "imagesRun": 0,
    "artifactId": 0
  }
}
```

Placeholders deliberately fail. `fetch-official.py` preserves the previous downloader's workflow/run/head/artifact/hash/ZIP/source-manifest checks and anonymous Azure redirect handling, with parameterized versions/run IDs and an additional exact artifact-ID check. Token is host-only in that step. It writes `$QA_PRIVATE_ROOT/artifacts/verified-identities.json` with `previous` and `candidate` objects: `version, commit, runId, runAttempt, artifactId, artifactDigest, artifactBytes, artifactExpiresAt, sha256, directory`.

Runtime environment:

- `QA_PRIVATE_ROOT`: new physical owner-only directory, prepared before fetch.
- `QA_SOURCE_ROOT`: clean checkout of the exact merged candidate main, separate from harness checkout.
- `CANDIDATE_COMMIT`: that full main SHA.
- `ROOT_CANDIDATE_READY=APPROVED_OFFICIAL_CANDIDATE`.
- GitHub-hosted Linux only (`GITHUB_ACTIONS=true`, `RUNNER_ENVIRONMENT=github-hosted`).
- `GH_TOKEN` and `GITHUB_TOKEN` must be absent from runtime and cleanup steps.

The runner starts with `python3 run-linux-acceptance.py`; always run `--cleanup-only` afterward when `assessment.json` exists. Root must separately obtain a successful current-main PUSH CI and pass the repository source gate immediately before acceptance/deployment.

## Scope and fixed acceptance

`legacy-run-isolated.py` derives from the previously reviewed PR160 helper7037b1b5, with owner/run-ID prefixes, owner-prefixed stopped extraction names and the explicit paired-run numeric diagnostics before the existing exit assertion. All46 original assertion ASTs remain identical. `legacy-source-sha256.txt`, cumulative `legacy-scaffold.diff` and `paired-provenance.json` record this controlled change. It retains exact image/JAR extraction/provenance, all98 migration hashes/normal Flyway, original500-desk register/login/staff-session workloads and thresholds, resource caps, nonempty synthetic125KZT wallet/checkpoint preservation, previous-version grant/logout compatibility, and label/ID-scoped cleanup. Original arrival scheduling can count2000 or2001 session iterations; the real cross-version staff check adds1. The report records actual counts and requires at least2001 total, without inventing or overriding metrics.

The wrapper adds a distinct learner path after those phases:

1. Normal `/api/mobile/auth/login` for the **same500 synthetic profiles**; at most5 in flight, scheduled4/sec. Validate each issued JWT using `/api/mobile/auth/me`. Existing rate limits are unchanged; no token is forged, no auth warmup or credential retry.
2. A separate capped k6 container makes Bearer `GET /api/mobile/training` requests for2minutes at500requests/30sec,25preallocated/50max VUs. Fixed gates: at least2000 valid overviews, zero errors/429/5xx/transport/unexpected status/dropped iterations, p95<2s/p99<5s, zero Hikari timeouts, corresponding pool acquisitions, and sampled PostgreSQL/Hikari/CPU/memory metrics. This is a bounded synthetic scenario, **not production-capacity certification**.
3. Two additional synthetic probes use normal POSM registration-without-SMS and normal password JWT login; their device grants are immediately logged out. On the **same issued JWT**, active and pending status reads succeed; blocked reads/start fail403 `USER_NOT_ACTIVE`; actual deleted profile fails401 `USER_NOT_FOUND`. An authorized nonexistent-assignment start gives404, so denial is distinguished from a service-level missing record. Training fingerprints before/after denied requests stay identical. These status-fixture cases do not claim a complete frontend/registration onboarding workflow.
4. A blocked probe stays blocked through **15→16→15→16**. Previous15 is expected to permit its training overview200: report this as a known baseline security weakness, not security rollback acceptance. Final16 must immediately deny403 again, permit the same token after active status, and deny after re-blocking. Schema, primary500 identity/credential/counter data and synthetic financial/training fingerprints remain unchanged. Financial balances/earned totals have a separate fingerprint because the preserved legacy scenario deliberately inserts its125KZT ledger/checkpoint after the load phases.

Backend2CPU/1536MiB, Xmx768MiB/metaspace384MiB/Hikari10; PostgreSQL2CPU/768MiB, Redis1CPU/128MiB, each generator2CPU/1GiB. Containers share only a uniquely labelled **internal** network; no ports or production URLs. Existing integration-disable/SMS-dev settings remain synthetic-only. Source migration changes fail closed and require replanning.

## Evidence and cleanup

Upload only `assessment.json`, `results/rps-*.json`, `results/learner-jwt-summary.json`, and `artifacts/verified-identities.json`. **Never upload** `*.private.*`, credentials, token fixture, raw logs, HTTP payloads or SQL. k6 summary intentionally excludes setup data/tokens. The exact owner is persisted before any resource can be created; cleanup reconciles late labelled containers and an orphan internal network and refuses foreign/nonempty/changed ownership. Runtime/cleanup failures stay failures.

Offline checks: `python3 -m unittest discover -s . -p test_contracts.py -v`, Python AST parsing, and `node --check learner-jwt.mjs`. These validate trust boundaries and report shape only; they are not executed-app acceptance. Actual Linux CI and exact-artifact outcomes remain pending until root runs the workflow.

Provenance patches use zero context (`git apply --unidiff-zero`) so required blank context markers do not look like source trailing whitespace. `paired-change.diff` contains code, tests, README and source-hash changes; generated provenance files have independent hashes in `delivery.json`.
