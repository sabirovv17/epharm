# Private PR164 production-budget qualification

This directory is a **harness deliverable only**. No Docker, VM, server or application was executed locally while preparing it. It never changes product source or production configuration. Original private CI43157241311 remains a recorded failed registration latency gate (p95=2110.50ms versus<2000ms); it is not replaced by a later result.

## Original fresh-fixture candidate qualification

Runtime follows the original frozen5852f56a wrapper, with only safe failure-location
metadata added: official15 starts once for normal Flyway99 and seed; the original
zero-profile assertion passes; old15 stops; official16 cold-starts. No optional
reference registration, SQL snapshot restore or Redis reset runs. All original
candidate workload, JWT/security, financial preservation and15→16→15→16 gates
remain mandatory, with unchanged thresholds and rate limits, no warmup or retry.
The separately declared production-budget resources below apply to every backend
version. Exactly one complete qualification is planned; failure stops.

The snapshot/restore helper was introduced only to undo the additional old15
reference workload. With that workload removed there are no such business writes
to undo. Removing both optional operations restores the original fresh-fixture
path; it does not declare the optional restore passed. Run43167159318 remains a
snapshot FAIL with candidate NOT_RUN: public data, sequence counters, counts and
schema99 history matched, while raw DDL and sorted intact-block hashes differed.
The cause remains unknown; this evidence is not restore-equivalence proof.
Separate production offsite backup/isolated restore has independent scope.

Prior reference15 measurements remain historical:43159736797 FAIL (485created,
454completed,46HTTP5xx,1drop),43162105153 PASS500/500 then snapshot blocker,
43165054420 FAIL (482created,441completed,59DEVICE_REGISTER503,2drops). They never
become candidate acceptance. SnapshotRoundtrip and validate_reference remain
only diagnostic/offline helpers and are absent from the runtime qualification
call path. Original candidate43157241311 latencyFAIL remains preserved as well.

The original-flow2CPU candidate43167673998 is also retained as **FAIL**:
477created,431completed,69DEVICE_REGISTER503,1drop,p95=2541.754ms. A result under
the new profile cannot supersede that2CPU capacity failure or claim2CPU acceptance.

## Separately declared production-budget profile

This is **PRODUCTION_BUDGET_NOT_EXACT_REPLICA**, a separate profile motivated by
the freshly observed production backend2GiB/JVM65% budget on a4CPU host. It is not
production-capacity certification or an exact reproduction of production hardware,
data, host contention or configuration. The test backend has an explicit4CPU
quota; production's backend quota was unset. No physical-core exclusivity is claimed.

The runner must expose exactly4 available logical CPUs. Every backend version uses
4CPU/2048MiB and exactly `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=65.0`, without
Xmx/metaspace/ActiveProcessorCount overrides. PostgreSQL stays2CPU/768MiB,
Redis1CPU/128MiB and both generators2CPU/1024MiB. All containers have unset CPU
affinity; no cpuset flag or `docker update` is used. Hikari remains10 and admission
remains4 with the existing2second wait. Workloads, arrival rates and every SLO/error
gate remain unchanged.

Actual BE/PG/Redis quotas, memory, empty cpuset and exact backend JVM environment
are inspected before business load. Generators retain the original command/stdin/
`--rm`/exit behavior and are inspected after start at the first polling opportunity.
Read-only numeric CPU topology exports only logical/core/package IDs and numeric
family/model/stepping/core/sibling fields. Backend readiness checks numeric cgroup
quota/memory, Prometheus systemCPU4 and poolmax10. Positive heap-pool maxima are
recorded as pool metadata, explicitly **not** a claimed total heap maximum.

Before cleanup, phase diagnostics retain CPU/cgroup/Hikari observations and JVM GC
numeric counters/phase-end rolling maxima. Prometheus HTTP counter deltas export
only fixed route buckets, numeric status and allowlisted exception buckets; this
distinguishes observed503 responses from unhandled500. Backend logs are
parsed only into fixed exception-class counts; messages, raw URIs, HTTP bodies and
credentials never enter reports. Log class occurrences are not request counts,
and handled `AppException`503 may produce no backend error log. Raw logs remain
private and excluded from upload. One production-budget qualification is planned; another
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

`legacy-run-isolated.py` derives from the previously reviewed PR160 helper7037b1b5, with owner/run-ID prefixes, owner-prefixed stopped extraction names, numeric diagnostics and the explicit production-budget profile above. All46 original assertion ASTs remain identical after the intentional unique owner-prefix normalization `pr160`→`pr164`. `legacy-source-sha256.txt`, cumulative `legacy-scaffold.diff` and `paired-provenance.json` record this controlled change. It retains exact image/JAR extraction/provenance, all98 migration hashes/normal Flyway, original500-desk register/login/staff-session workloads and thresholds, unchanged support-service caps, nonempty synthetic125KZT wallet/checkpoint preservation, previous-version grant/logout compatibility, and label/ID-scoped cleanup. Original arrival scheduling can count2000 or2001 session iterations; the real cross-version staff check adds1. The report records actual counts and requires at least2001 total, without inventing or overriding metrics.

The wrapper adds a distinct learner path after those phases:

1. Normal `/api/mobile/auth/login` for the **same500 synthetic profiles**; at most5 in flight, scheduled4/sec. Validate each issued JWT using `/api/mobile/auth/me`. Existing rate limits are unchanged; no token is forged, no auth warmup or credential retry.
2. A separate capped k6 container makes Bearer `GET /api/mobile/training` requests for2minutes at500requests/30sec,25preallocated/50max VUs. Fixed gates: at least2000 valid overviews, zero errors/429/5xx/transport/unexpected status/dropped iterations, p95<2s/p99<5s, zero Hikari timeouts, corresponding pool acquisitions, and sampled PostgreSQL/Hikari/CPU/memory metrics. This is a bounded synthetic scenario, **not production-capacity certification**.
3. Two additional synthetic probes use normal POSM registration-without-SMS and normal password JWT login; their device grants are immediately logged out. On the **same issued JWT**, active and pending status reads succeed; blocked reads/start fail403 `USER_NOT_ACTIVE`; actual deleted profile fails401 `USER_NOT_FOUND`. An authorized nonexistent-assignment start gives404, so denial is distinguished from a service-level missing record. Training fingerprints before/after denied requests stay identical. These status-fixture cases do not claim a complete frontend/registration onboarding workflow.
4. A blocked probe stays blocked through **15→16→15→16**. Previous15 is expected to permit its training overview200: report this as a known baseline security weakness, not security rollback acceptance. Final16 must immediately deny403 again, permit the same token after active status, and deny after re-blocking. Schema, primary500 identity/credential/counter data and synthetic financial/training fingerprints remain unchanged. Financial balances/earned totals have a separate fingerprint because the preserved legacy scenario deliberately inserts its125KZT ledger/checkpoint after the load phases.

Backend4CPU/2048MiB, JVMMaxRAMPercentage65%/Hikari10; PostgreSQL2CPU/768MiB, Redis1CPU/128MiB, each generator2CPU/1GiB, all without affinity. Containers share only a uniquely labelled **internal** network; no ports or production URLs. Existing integration-disable/SMS-dev settings remain synthetic-only. Source migration changes fail closed and require replanning.

## Evidence and cleanup

Upload only `assessment.json`, `results/rps-*.json`, `results/learner-jwt-summary.json`, and `artifacts/verified-identities.json`. **Never upload** `*.private.*`, credentials, token fixture, raw logs, HTTP payloads or SQL. k6 summary intentionally excludes setup data/tokens. The exact owner is persisted before any resource can be created; cleanup reconciles late labelled containers and an orphan internal network and refuses foreign/nonempty/changed ownership. Runtime/cleanup failures stay failures.

Offline checks: `python3 -m unittest discover -s . -p test_contracts.py -v`, Python AST parsing, and `node --check learner-jwt.mjs`. These validate trust boundaries and report shape only; they are not executed-app acceptance. Actual Linux CI and exact-artifact outcomes remain pending until root runs the workflow.

Provenance patches use zero context (`git apply --unidiff-zero`) so required blank context markers do not look like source trailing whitespace. `paired-change.diff` contains code, tests, README and source-hash changes; generated provenance files have independent hashes in `delivery.json`.
