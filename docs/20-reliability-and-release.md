# Reliability, observability and releases

This document is the operational contract for the P2 reliability work. Configuration is committed;
production acceptance still requires real credentials, an off-site repository, notification channel,
Sentry projects, Apple signing and a staging load-test run.

## Backups and restore tests

The backup chain is:

1. `tools/pg-backup.sh` writes an atomic custom-format PostgreSQL dump, gzip-validates it and stores a
   SHA-256 sidecar.
2. `tools/minio-backup.sh` mirrors every `MINIO_BACKUP_BUCKETS` bucket through the private container
   network, archives it atomically and stores a SHA-256 sidecar.
3. `tools/offsite-backup.sh` encrypts and copies the artifacts to restic (including both private-file
   sets when required below), applies independent
   daily/weekly/monthly retention and runs a repository check.
4. `tools/restore-test.sh` checksum-validates the newest artifacts, restores them into disposable
   PostgreSQL and MinIO containers and verifies Flyway history, public tables and restored buckets.
5. Only after the complete `backup-all.sh` chain succeeds, verified local rotation retains
   `BACKUP_KEEP_COUNT` newest PostgreSQL and MinIO backups (default 7 per component, minimum 2).
   Archives and their checksums are treated together. Failed creation, missing/bad checksum, corrupt
   gzip/tar, or required off-site failure stops rotation without evicting the old restore points.

For the current source, `MINIO_BACKUP_BUCKETS=epharm-receipts,pim-product-images` is the complete
known set and the code/example default. A configured missing bucket fails the backup; it is never
silently omitted or created on the source. New MinIO artifacts contain versioned per-bucket
anonymous policy JSON in addition to preserved objects and metadata. `{}` means the source bucket
is private. Isolated restoration applies exactly that policy (`private` or the saved custom JSON),
reads it back and compares semantic JSON before reporting success. It does not replace a restricted
prefix with a general download/public grant. Policy/path/inventory validation runs before extraction.
The object companion `object-metadata.json` records exact content headers, user metadata, tags,
body sizes and SHA-256 values. Capture checks source drift against the mirrored bodies; archive
validation checks every body before extraction. Restore replays and verifies metadata only in a
new isolated container with an exact Docker identity and synthetic credentials. Original ETag and
LastModified remain source witnesses. Versioned buckets and objects over 5 GiB are refused.
Headers retain their HTTP bytes, including semicolons and non-ASCII values, rather than being
reconstructed from file extensions. Source credentials never enter the restore helper.

Older artifacts without policies or object metadata remain an explicitly labelled legacy restore mode only when the
native/private requirement is false; they cannot satisfy the new native release recovery gate.

### Native private files and recoverable credential keys

Before enabling the native merchandising runtime or D9 credential capture, the protected backup
environment must explicitly set `BACKUP_PRIVATE_FILES_REQUIRED=true` and all three physical host
paths: `MERCH_EVIDENCE_HOST_ROOT`, `MERCH_LEGACY_FILES_HOST_ROOT`, and
`LEARNER_CREDENTIALS_HOST_KEY_DIRECTORY`. These are the same host directories mounted into the
backend; there are no inferred defaults in the backup producer. Keep roots and all nested
directories owned by the backup user with mode `0700`, files `0600` (or read-only `0400`), and do not
use symlinks or hardlinks. An unset requirement preserves the older PG/MinIO-only installation;
it is not an acceptable native-runtime release configuration.

`tools/private-files-backup.sh` runs between MinIO and offsite backup. It creates two distinct
artifacts: `native-files/epharm-native-files-<UTC>.tar.gz` for native evidence, instructions,
reference photos and imported private files; and
`credential-keys/epharm-credential-keys-<UTC>.tar.gz` for the whole explicitly configured key ring
(including `keys.json`). The latter is a separate mode-`0600` recovery artifact outside PostgreSQL,
never embedded in a database dump, normal report, log, or native-file archive. Both archives have
an external SHA-256 sidecar and an internal path/size/SHA-256 manifest. The producer rejects changed
source files, unsafe filesystem objects, overlapping roots and insufficient temporary free space.
The same restic run encrypts and copies all four artifact directories; a missing or invalid required
private snapshot fails before offsite backup and prevents subsequent local rotation.

Native snapshots use `BACKUP_KEEP_COUNT` (minimum 2) through the existing verified, race-checked
rotation, only after the entire chain succeeds. Credential-key recovery archives are deliberately
not locally pruned: rotation requires a separate key-custody review proving every retained vault
key version remains recoverable. Monitor their cumulative size. The producer refuses a new archive
unless its uncompressed estimate plus 5% and a 256 MiB reserve fit; this is a stop-safe floor,
not a forecast of database/WAL/image/migration headroom. Neither producer nor retention deletes
business files in the source host directories.

`restore-test.sh` with the requirement enabled needs both private artifacts as well as PG/MinIO.
It validates each manifest and byte digest and restores files into a fresh, mode-`0700` disposable
stage before recording success. It never targets the mounted source roots or replaces live keys.
For a manual offline file recovery drill, select a specific artifact recovered from restic, then run:

```bash
python3 tools/ops/private_backup.py verify --kind native-files --archive /protected/native-files/epharm-native-files-<UTC>.tar.gz
python3 tools/ops/private_backup.py restore --kind native-files --archive /protected/native-files/epharm-native-files-<UTC>.tar.gz --destination /protected/drill/new-native-files
python3 tools/ops/private_backup.py restore --kind credential-keys --archive /protected/credential-keys/epharm-credential-keys-<UTC>.tar.gz --destination /protected/drill/new-key-recovery
```

The destination must not already exist, its parent must be private, and original relative paths are
retained below `merch`, `merch-legacy` or `learner-credentials`. A file-copy drill proves file integrity;
full D9 disaster recovery additionally requires restoring a matching database in isolation and
decrypting a synthetic vault record with the recovered key ring. Preserve the independent restic
recovery secret outside both application and backup hosts; having a key archive accessible only
through credentials lost with the application host is not sufficient recovery. No acceptance gate
is passed merely by adding these scripts or by an old PG/MinIO-only restore success metric.

No restore script mounts a production volume. Local retention is count-based, not unbounded by
repeated deployments on the same day. Standalone `pg-backup.sh` and `minio-backup.sh` are additive;
they never delete old copies. The full chain and restore-test share a lock, taken before selecting
restore files. Do not install a second cron producer alongside `epharm-backup.timer`. The expected
production location is `/var/backups/epharm`, on storage independent from Docker's data disk.

Optional `BACKUP_RELEASE_ROOT` enables daily rotation of dedicated pre-deploy rollback bundles:
keep the newest `BACKUP_RELEASE_KEEP_COUNT` (default 3, minimum 2) **plus** bundles referenced by
the active backend-only/frontend-only/archive transactions and their pre-rollback markers in every deployment
listed in `BACKUP_RELEASE_DEPLOY_DIRS` (colon-separated). The wrapper holds all canonical deployment
locks; malformed references, unsafe paths, unknown bundle contents or altered manifests fail closed.
Unknown files, legacy SQL dumps, signing material, POSM downloads and Docker volumes are not targets.

`active-transaction` must contain a physical canonical absolute direct-child path and exactly
one final LF. Do not remove a malformed reference or weaken retention checks. The reviewed
`tools/ops/repair-frontend-pointer.py` defaults to read-only dry-run and accepts only the narrow
legacy frontend relative format, pinned pointer/manifest SHA256 and exact running image IDs.
It validates protected bundles under the existing deploy lock. Explicit `--apply` additionally
preserves the original mode-0600 bytes outside bundles and replaces only that pointer atomically;
it never deploys, restarts, restores a database or runs retention. Live repair requires separate
authorization. The 29 October authorized repair and subsequent strict dry-run are recorded in
`docs/reports/unification-2026-10-29/backup-blocker-resolution.json`.

POSM files served through `/downloads/*` live outside the receipt bucket: MinIO backup alone
does not preserve them. Before unification/cutover, verify the dedicated downloads manifest,
copies and SHA256 alongside DB+uploads. Do not add these files to rollback retention targets.

`BACKUP_RETENTION_DRY_RUN=true ./tools/backup-all.sh` creates and verifies fresh backups but only
prints the proposed retention. Verify off-site recovery and an isolated restore before an initial
historical cleanup. Legacy archives need a separate exact reviewed inventory; never use blanket
recursive deletion or `docker volume prune` as a substitute for this policy.

For an archive-based application host, install the reviewed backup tools under a separate root-owned
versioned operations directory, set `EPHARM_DEPLOY_DIR` there in the existing protected backup
EnvironmentFile, and preserve `EPHARM_ENV_FILE` pointing to the live app configuration. This prevents
an older application source directory from accidentally replacing the backup policy. No application
container/image change is required for a backup-tools-only deployment.

Install on a systemd host:

```bash
sudo install -d -m 700 /etc/epharm /var/backups/epharm
sudo install -m 600 .env.ops.example /etc/epharm/backup.env
sudoedit /etc/epharm/backup.env
sudo install -m 644 ops/systemd/epharm-backup.{service,timer} /etc/systemd/system/
sudo install -m 644 ops/systemd/epharm-restore-test.{service,timer} /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now epharm-backup.timer epharm-restore-test.timer
sudo systemctl start epharm-backup.service
sudo systemctl start epharm-restore-test.service
systemctl list-timers 'epharm-*'
```

`BACKUP_REQUIRE_OFFSITE=true` makes a missing or failed restic copy fail the daily job. Store the
restic password only in `/etc/epharm/backup.env` (mode `0600`), or reference a separate root-owned
mode-`0600` file through `RESTIC_PASSWORD_FILE`. Keep a recoverable copy of that key outside both
application and backup servers. A successful run updates Prometheus
textfile metrics; alerts fire after 36 hours without backup or 8 days without restore verification.
With an explicitly local-only installation (`BACKUP_REQUIRE_OFFSITE=false` and no restic destination),
rotation still retains verified local copies; that is not disaster recovery or proof of a fresh external
backup. Keep an encrypted external copy independently and do not silently disable a required off-site
check to make retention pass.

For an SFTP repository on an independent host, pin its SSH host key and use a dedicated key/user
restricted to SFTP and the application server's source IP. Configure `RESTIC_SFTP_COMMAND` with
`IdentitiesOnly=yes`, `BatchMode=yes`, `StrictHostKeyChecking=yes` and a protected known-hosts file.
Load these settings through an additional systemd `EnvironmentFile` rather than replacing existing
backup settings. A second server on the same LAN protects against one host's failure, not a
site-wide disaster; retain a separate key recovery copy and plan a truly external destination.

## Build exact-main application archives

`release-images.yml` is a manual build-only workflow. Dispatch it on `main` with
`exact_commit` set to the full current main SHA and `release_id` set to the reviewed
version. Its existing source gate requires successful **push CI** for that exact
main before and after the build; the manual run is not a replacement for CI.
An existing version tag must resolve to the same commit. The workflow creates no
Git tag, registry publication, production connection or deployment.

```bash
gh workflow run release-images.yml --ref main \
  -f exact_commit=EXACT_MAIN_40_HEX -f release_id=v0.2.0
gh run download RUN_ID -R sabirovv17/epharm \
  -n epharm-images-v0.2.0-EXACT_MAIN_40_HEX --dir NEW_PRIVATE_DIRECTORY
```

Both production Dockerfiles build sequentially as linux/amd64 with truthful
version/revision arguments. A Git-object archive supplies their clean contexts and
the required release tools, Compose and Caddy files; it does not include live dotenv,
Git credentials or documentation containing historical credentials. The workflow
checks unchanged source bytes, actual OCI metadata, the admin/merchandising artifacts,
the extracted JAR's financial/access contract and each saved archive's runnable
content. It retains archive/file/config/JAR hashes, actual Docker engine metadata
and the Actions run/attempt in `provenance.json` and `SHA256SUMS`.

Before using the download, verify its repository, workflow path, manual event,
exact head SHA, successful run, artifact identity/digest and expiry, then validate
`SHA256SUMS`. Preserve these exact archives rather than rebuilding an assumed
equivalent image. Docker stores can report different local image IDs after load;
use the existing archive verifier and record actual destination IDs. Downloaded
artifacts are build evidence, not acceptance: main OCI recovery and all migration,
file, identity, POSM and single-writer gates still apply before application cutover.

## Monetary release boundary

The unified wallet release adds forward-only financial migrations. Keep the CRM reward and
POSM sale-bonus flags off until paired CI, exact HQ-confirmed executor mapping, synchronized
clocks and the controlled real-phone approval pilot pass. A working recommendation popup is
not evidence that the staff identity is trusted for money.

A legacy backend booting on the expanded schema proves **read compatibility only**. Its training,
receipt, adjustment and payout writers do not append to the new ledger. After ledger-backed
movements exist, do not use an ordinary two-image legacy rollback while those writers remain
available. Prefer a tested roll-forward; any exceptional rollback requires freezing **all** monetary
writers and reconciling projection/ledger before reopening them. Disabling CRM rewards alone is
not sufficient. Never drop migrations or rewrite entries to make an old binary appear compatible.

The actual v0.1.47 artifact is wallet-capable; its isolated forward-schema financial rehearsal is
not an authorization recovery approval. Once the V066 managed-access schema exists, a recovery
artifact must also contain the reviewed scope and fresh-revocation implementation.
`verify-financial-transition.py` checks both boundaries on the exact image and refuses v0.1.47
on that expanded schema. The existing archive transaction saves only its real predecessor; it
cannot substitute an unpromoted development image as recovery. Before the first unified release,
review and rehearse a pinned RBAC-compatible recovery/roll-forward procedure. Do not weaken this
guard, relabel an older binary as current main, or restore an old database over new POSM sales.

For an unresolved **post-switch** archive failure, the deploy writer retains
`candidate.release.env` and creates a caller-owned 0600 `failed-state.json` in its
0700 transaction directory. This records the actual application container/image IDs,
Caddy/PostgreSQL container IDs, immutable predecessor/candidate record hashes and protected
configuration hashes. It does not replace the healthy-baseline check for normal deployment.
An absent, altered or incomplete record requires manual investigation; do not manufacture one.

`tools/release/recover-archive-two-image.sh` is the dedicated forward-only entrypoint:

```bash
tools/release/recover-archive-two-image.sh \
  /absolute/deploy/releases/archive-two-image/TRANSACTION/failed-state.json \
  FAILURE_SHA256 BACKEND_TAG FRONTEND_TAG EXACT_MAIN_COMMIT \
  sha256:BACKEND_IMAGE_ID sha256:FRONTEND_IMAGE_ID \
  /absolute/epharm-backend-only-backups COMPOSE_SHA256 --dry-run
```

After reviewing the preflight, repeat the same command without `--dry-run`. The helper
accepts only the exactly observed saved pair, including an explicitly recorded mixed
previous/candidate pair. It refuses unknown or missing containers and any config, pins,
artifact, physical database identity/Flyway-history or failure-record drift. It retains all existing source,
artifact, current-main/CI and financial/RBAC checks. A fresh PostgreSQL/config/image backup is
created **before** taking the shared deployment lock, then revalidated under the lock; the
unhealthy observed pair is not a healthy rollback baseline. Sales can continue: the helper
does not restore PostgreSQL, rewind migrations or compare mutable business row counts.
The target's ordinary Flyway startup may complete a partially applied reviewed suffix.
All pre-existing successful Flyway rows must remain an exact prefix, while the completed
version/script/checksum list must match the exact target JAR. Checksums use the verified
Flyway 10.20.1 line/BOM/CRC32 algorithm; unknown implementations or history edits are refused.

Only backend/frontend are recreated using the same capable pair or a newly reviewed exact-main
pair. Full release smoke is mandatory. Each attempt gets a separate protected audit directory;
the original predecessor and failure evidence remain unchanged. If another forward fails, it
records the actual partial state in that attempt's `failed-state.json`, which can be supplied
with its new digest for a later reviewed retry. There is no automatic predecessor fallback.
Configuration/Caddy/PostgreSQL drift during a failed attempt still requires investigation.
Successful recovery does not rewrite the original rollback record or claim a different image
was the predecessor. A later normal healthy deployment establishes its own actual baseline.
Before recreating application containers, the helper atomically records the fresh bundle in
the separate 0600 `releases/archive-two-image/recovery-backup` reference under the deployment
flock. The previous reference is retained in the new immutable attempt audit. Reviewed release
retention protects this one exact bundle independently of an active/healthy transaction,
using the same strict configured backup-root checks; corrupt, stale or escaping references
stop retention. The ordinary keep count is unchanged. Retain the incident reference until
reviewed closure; do not delete a bundle merely because recovery has become healthy.
**Before real use, install the reviewed retention reader together with this helper.** Older
backup tooling which only reads active transactions cannot protect this new incident reference.

The inert Docker contract suite proves orchestration and refusals; it is not a real main-image
deployment, Windows popup proof or authorization to deploy. Rehearse the exact promoted main
artifacts before the first production application switch, including fault/restart with new
sales, wallet entries, managed grants and revocations retained. A branch JAR restart can prove
application persistence separately, but cannot substitute for that main/CI image rehearsal.

## Monitoring and alerting

The `monitoring` Compose profile includes Prometheus, Alertmanager, Grafana, blackbox exporter,
PostgreSQL/Redis exporters, node exporter and cAdvisor. MinIO exposes metrics only inside the Docker
network. Spring Boot publishes request counters and latency histograms through
`/actuator/prometheus`; Caddy does not expose `/actuator/*` publicly.

```bash
docker compose --env-file .env.prod --env-file .release.env \
  -f docker-compose.prod.yml --profile monitoring up -d
ssh -L 3000:127.0.0.1:3000 -L 9090:127.0.0.1:9090 -L 9093:127.0.0.1:9093 \
  adm-quasar@inkpim.inkar.kz
```

Grafana, Prometheus and Alertmanager bind to server localhost. The provisioned dashboard covers
public uptime, 5xx ratio, p95 latency, database connections, host disk, core services and backup age.
Alerts cover public/backend/PostgreSQL/Redis/MinIO availability, API error rate and latency, disk
headroom, connection pressure, restart loops and stale backup/restore tests.

Set `ALERT_WEBHOOK_URL` to the HTTPS endpoint of the incident channel. An empty value deliberately
keeps alerts visible in Alertmanager while logging that external notifications are muted; that state
does not pass production acceptance. Send a test alert and verify both firing and resolved messages.

## Sentry

All three applications use the same immutable release id with component prefixes:

- backend: `epharm-backend@<RELEASE_ID>`;
- admin: `epharm-admin@<RELEASE_ID>`;
- mobile: `epharm-mobile@<RELEASE_ID>`.

Set `SENTRY_BACKEND_DSN`, `SENTRY_FRONTEND_DSN` and `SENTRY_MOBILE_DSN` in secret storage. Empty DSNs
disable export. Default PII and mobile screenshots are disabled because errors can occur while a
receipt or identity data is visible. Admin hidden source maps must be uploaded by a trusted CI step
with `SENTRY_AUTH_TOKEN`, `SENTRY_ORG` and `SENTRY_FRONTEND_PROJECT`; the Docker build never accepts
that token and always removes maps from the nginx image. The tag-triggered
`release-observability.yml` workflow performs the trusted upload and fails closed when credentials
are absent. The TestFlight workflow uploads dSYMs when mobile Sentry credentials exist.

Acceptance: trigger one controlled error per component in staging, confirm its environment/release,
stack trace and source mapping, then resolve the issues. Never add an unauthenticated production
endpoint whose only purpose is generating errors.

## Immutable application releases and rollback

`main` is the release source of truth. The **Main promotion owner** GitHub ruleset restricts main
updates to `sabirovv17` on PRs only (policy snapshot: `.github/rulesets/main-release-owner.json`).
Other collaborators can still push task branches and open PRs. The owner exemption is limited to
that update-ownership rule: the separate PR/CodeQL rules and strict GitHub-Actions-pinned P0 check
still apply. Do not add CI or review bypasses to solve a deployment failure. There is currently one
code owner, so mandatory self-approval is not used as a substitute for owner-controlled promotion.

For a new promotion, `verify-release-source.py <full-commit>` requires the exact current remote
`main`, its newest successful push-run of `ci.yml`, and the successful P0 gate from the GitHub
Actions app in that run. It rechecks main before succeeding and fails closed on missing/stale CI
or unavailable evidence. It uses standard HTTPS; a public repository needs no credential, while
`GH_TOKEN`/`GITHUB_TOKEN` can be supplied from secret storage for private access or API limits.
The gate is called by prepare and new deployment transactions, never by explicit rollback.

The `container / admin production build` CI job runs isolated browser scenarios against the
actual nginx image. All API requests are mocked/deny-by-default; production reset, login and draft
creation are never sent. The tests require all four campaign trigger types and verify their request
payloads. The real router must also keep learner recommendations, direct reload and sign-in
inside the pharmacist cabinet without an HQ session; personal data cannot leak across account
changes or become a fallback balance after an authorization failure.
`verify-admin-artifact.py` additionally rejects the known obsolete compiled form before
promotion. Candidate smoke compares the public `/`, `/promo`, index and protected JS/CSS bytes
with the exact image and checks both public commit identifiers. These guards complement backend
integration tests, not replace them.

Repository rules cannot prevent somebody who still has root/sudo/Docker access from bypassing the
release scripts. Keep one designated production operator, separate ordinary developer access from
deployment access, and avoid shared privileged credentials. Changing server accounts or rotating
credentials requires a coordinated access handoff; GitHub settings alone do not secure SSH.

Application release tags must match `vX.Y.Z` (optional prerelease suffix), point to the deployed
commit and have a dated matching `CHANGELOG.md` section. Protect `v*` tags in the Git hosting rules:
scripts cannot prevent a repository administrator from force-moving a tag.

Release sequence after merging and updating the changelog:

```bash
git tag -s v1.0.0 -m 'Epharm v1.0.0'
git push origin v1.0.0
./tools/release/prepare.sh v1.0.0
./tools/release/deploy.sh v1.0.0
```

`prepare.sh` builds `epharm/backend:<tag>` and `epharm/frontend:<tag>`, embeds the tag and commit into
OCI labels/Sentry/app health and writes a content-addressed local manifest. `deploy.sh` refuses
missing images, performs PostgreSQL + MinIO + off-site backup, updates `.release.env`, starts without
rebuilding and verifies both `/api/health` and `/release.json` report the expected id. A failed smoke
automatically returns to the previous images.
The backend CI must also check runtime-only integrations in the final `bootJar`, not just that
application sources compile. Standard-N pharmacist synchronization requires the Jaybird Firebird
driver; the production JAR packaging check guards against silently losing it in a release.
`verifyMerchandisingArtifact` checks the protected CRM bridge, disabled redirects and configuration
in that same JAR. Docker runs it even when unit tests are delegated to CI. All release transactions
also extract the JAR from the exact candidate image into a stopped, network-isolated container and
run `tools/release/verify-merch-artifact.py`. Stage the merged verifier and
`merch-artifact-contract.properties` with the release scripts. An obsolete bridge cannot pass these
gates by changing only OCI labels. This rejects the known regression; it does not replace
exact-commit CI, review or Windows acceptance. Privileged manual bypass is not a supported release.
For an exceptional backend-only release, use the separate, tested archive-host transaction in
`06-deployment-and-ops.md`; it persists distinct backend/frontend tags and commits and requires a
fresh verified rollback bundle **in addition to**, not instead of, encrypted off-site backup and a
recent restore-test. The generic two-image release scripts remain backward-compatible.
When both images change on an archive-based host, use the dedicated two-image transaction in that
same operations document. It verifies exact running/candidate image IDs and labels, tolerates stale
generic `.release.env` metadata by pinning each component explicitly, and never recreates Caddy.
An already-completed archive release uses `rollback-archive-two-image.sh` with its saved transaction,
not the main-only promotion script or the Git-checkout-only generic rollback. It accepts only the
saved predecessor of the exact current image pair, takes a fresh current backup, and restores the
candidate automatically if recovery fails. It never rolls back the database or requires GitHub.
Its local rollback bundle and automatic application rollback also require the off-site backup and
forward-schema compatibility drill; neither rolls back a Flyway migration.
The initial route check tolerates brief 502/connection failures while containers start, but both
routes must report the new release id within 120 seconds (configurable through
`RELEASE_READINESS_WAIT_SECONDS`). A persistent error or an old release id still triggers rollback.

Explicit rollback:

```bash
./tools/release/rollback.sh v0.9.9
```

Flyway remains forward-only. Therefore every migration must be expand/contract and compatible with
the immediately previous application release; rollback changes application images, not production
data. Run an actual previous->current rollback drill in staging before approving each tag.

The admin and storefront audit gate runs a full npm audit and a second production-only audit.
`tools/check-frontend-audit.mjs` allows only
[`GHSA-vfj7-8cjw-p6xm`](https://github.com/advisories/GHSA-vfj7-8cjw-p6xm), while the sole
affected `braces@3.0.3` and every derived finding remain confined to dev dependencies. There is
no patched `braces` release as of 2026-10-13; removing it requires a reviewed major Tailwind or
ESLint migration. This exception expires automatically on 2026-11-06 and fails closed on any new
advisory, production exposure, changed package version, or incomplete audit report. It does not
waive runtime vulnerability checks or authorize unreviewed dependencies.

## 500 cash-desk load model

`load-tests/k6/500-cash-desks.js` models heartbeat, playlist polling, recommendations, completed
sales, a 500-desk offline-outbox replay burst and authenticated concurrent video uploads. It enforces
per-workload p95/p99 budgets and a global error budget. Run it only against a staging stack restored
from production-size data and correlate it with Grafana/PostgreSQL/MinIO metrics.

See `load-tests/README.md` for commands. `load-tests/results/summary-<run-id>.json`, dashboard
screenshots, server sizing and bottleneck/remediation notes are required release evidence. The
runner refuses the production hostname unless an explicit impact acknowledgement is supplied.

## Mobile/TestFlight acceptance

Committed automated coverage includes:

- Sentry release/environment setup;
- app privacy manifest included in the Runner target;
- `epharm:///...` custom-scheme deep links on iOS and Android;
- secure session restoration tests;
- primary/fallback success and both-origins-unavailable tests;
- a manual TestFlight workflow with immutable tag verification, signing, IPA upload and retained
  artifact;
- `tools/mobile-release-check.sh`, which validates native declarations and runs all Flutter checks.

The remaining checks require Apple infrastructure and a physical iPhone. Copy
`docs/mobile-release-evidence.example.json`, fill it for the exact TestFlight build and run:

```bash
RELEASE_ID=v1.0.0-build.1 \
MOBILE_RELEASE_EVIDENCE=/secure/release-ticket/mobile-evidence.json \
REQUIRE_STORE_VERSION=true \
./tools/mobile-release-check.sh
```

The evidence expires after 14 days and must attest TestFlight installation, generated privacy report,
receipt camera, training QR, cold/warm deep links, process-kill session restoration and graceful UX
when both HTTPS and `:8060` are unavailable. Universal links are intentionally not claimed yet: they
also require the final Apple Team ID, Android signing fingerprint and hosted AASA/assetlinks files.

The native merchandising cutover also retains the old CRM address through a
default-off edge compatibility switch, `EPHARM_UNIFIED_CRM_CUTOVER_ENABLED`, in the
Shop edge configuration on .80. It forwards only external producer/staff/task
paths to the unchanged ePharm origin; admin and personal `/learn` redirect to the
canonical domain. Retired CRM APIs fail409 rather than reaching an old writer.
The source archive includes this exact edge configuration. Add precisely
`https://crm.inkar.kz` to the backend CORS allowlist only at that reviewed cutover;
do not rewrite a browser Origin header or enable wildcard CORS. HTTP and internal
TLS must use the same routes, preserve the unrelated Shop upstream and strip the
server integration key. The edge switch is not permission to stop CRM or skip
the final writer freeze, claim-issuer drain, source delta/file check and backup.
Enable this .80 edge switch only after the .76 native task, retired staff-entry and producer
routes have passed their checks with the reviewed capabilities enabled. Otherwise
the old .76 staff redirect and the new .80 forwarding path can form a redirect loop.

Shared monthly-code access is retired: keep `MERCH_STAFF_ACCESS_ENABLED=false`.
Preserve its historical rows; use personal `/learn` for monthly reports and QR for
individual tasks. Retired plain/access browser entries show the native transition
page without issuing a shared session or forwarding query credentials.

For native public authentication, configure the Tomcat forward-header
valve with an explicitly reviewed project-proxy network pattern. Compose defaults
to `SERVER_FORWARD_HEADERS_STRATEGY=none` and an internal-proxy pattern matching
nothing. Do not use broad default private-network trust or FRAMEWORK trust of
arbitrary Forwarded headers. Caddy trusts only the verified .74/.80 ingress hops
(.80 trusts .74) with strict right-to-left parsing and overwrites backend XFF
with its resolved client IP. The internal frontend path preserves that boundary.
Re-observe the actual project subnet before a release, and include all actual
Caddy/nginx hops in the reviewed pattern. A network recreation requires review;
three failed code entries must not rate-limit unrelated clients behind the proxy.
A direct untrusted caller's forged XFF must not change its rate-limit identity.
See [Caddy trusted proxies](https://caddyserver.com/docs/caddyfile/options#trusted-proxies)
and [Spring Boot reverse proxies](https://docs.spring.io/spring-boot/3.3/how-to/webserver.html#howto.webserver.use-behind-a-proxy-server).
