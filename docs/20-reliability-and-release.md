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
3. `tools/offsite-backup.sh` encrypts and copies both artifact sets to restic, applies independent
   daily/weekly/monthly retention and runs a repository check.
4. `tools/restore-test.sh` checksum-validates the newest artifacts, restores them into disposable
   PostgreSQL and MinIO containers and verifies Flyway history, public tables and restored buckets.

No restore script mounts a production volume. Local retention defaults to 30 days. The expected
production location is `/var/backups/epharm`, on storage independent from Docker's data disk.

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
restic password only in `/etc/epharm/backup.env` (mode `0600`). A successful run updates Prometheus
textfile metrics; alerts fire after 36 hours without backup or 8 days without restore verification.

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

Explicit rollback:

```bash
./tools/release/rollback.sh v0.9.9
```

Flyway remains forward-only. Therefore every migration must be expand/contract and compatible with
the immediately previous application release; rollback changes application images, not production
data. Run an actual previous->current rollback drill in staging before approving each tag.

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
