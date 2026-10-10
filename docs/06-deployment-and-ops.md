# Deployment and Operations

## Current Server

Current shared environment:

| Item       | Value                     |
| ---------- | ------------------------- |
| Host       | `inkpim.inkar.kz`         |
| Public URL | `https://epharm.inkar.kz` |
| Deploy dir | `/home/adm-quasar/epharm` |
| Stack      | Docker Compose + Caddy    |

The intended public setup is one host with path routing: `epharm.inkar.kz`. Do not configure separate
`api`, `admin`, or `s3` domains unless DNS, `.env.prod`, and `Caddyfile` are changed together.

The 2026-07-21 expired-certificate/404 finding is historical. On 2026-09-23 the public HTTPS
`/api/health` and TLS verification passed. Recheck the certificate, SNI and public health for each
release; the trusted `:8060` ingress is not an acceptable POSM client endpoint.

## Production Stack

`docker-compose.prod.yml` runs:

- `postgres` (`postgres:16-alpine`);
- `redis` (`redis:7-alpine`);
- `minio`;
- `minio-init`;
- `backend`;
- `frontend`;
- `caddy`.

Backend and frontend are not exposed directly. Caddy publishes ports 80/443/443-udp and the trusted
HTTP upstream `8060` for the INKAR external TLS ingress.

MinIO console is bound to `127.0.0.1:${MINIO_CONSOLE_PORT:-9001}` and should be reached through an SSH
tunnel or VPN.

## Caddy

Current `Caddyfile` intentionally uses one site block for `{$ADMIN_DOMAIN}` and handles:

- `/s3/*` -> MinIO with prefix stripped;
- `/api/*` -> backend;
- exact `GET /merch/staff` -> temporary 302 redirect to the fixed CRM `/staff` page,
  preserving only the incoming legacy query; allowlisted old asset/task/media routes -> CRM;
- everything else -> frontend.

For QR migration, the backend's `MERCH_TASKS_STAFF_URL` pins the exact primary CRM staff page.
The old `/merch/staff` path is an exact-match, GET-only redirect to that fixed HTTPS host;
it keeps a legacy query token solely to let an already-issued link reach the CRM. It sets
`Referrer-Policy: no-referrer` and `Cache-Control: no-store`. New QR links put their bearer in a
fragment instead. First deploy and verify this redirect, then deploy the dual-URL backend, and
only then change the CRM-generated public task URL. Verify actual shown ACKs are accepted: a
healthy page alone does not prove the dispatch bridge works.
The primary assignment source and QR issuer is the internal CRM on 10.10.1.80, exposed to staff
as `https://crm.inkar.kz/staff`; the historical fallback is not an active assignment source.
Corporate DNS can resolve that hostname directly to 10.10.1.80, bypassing the public HTTPS
gateway. Therefore a trusted TLS listener on the CRM host itself is a release prerequisite:
verify `crm.inkar.kz:443` from the ePharm host and a pharmacy network with normal certificate
verification before enabling a direct QR redirect or changing the QR origin. The isolated Caddy
vhost and rollback procedure are described in
[`storefront/deploy/inkar-server/README.md`](../storefront/deploy/inkar-server/README.md).
The CRM must filter overdue assignments before returning active tasks. After the CRM URL switch,
scan a newly issued QR in a browser; old cached legacy QR links are not a compatibility guarantee.

The merchandising server credential must use HTTPS when the upstream is outside the private
INKAR network. The public QR portal is a separate browser route with a deny-by-default allowlist;
it must never proxy CRM admin/auth endpoints or carry `X-Pharmapay-Key` from a browser.

Public TLS uses the INKAR-issued wildcard certificate, not ACME: public DNS terminates on the
corporate ingress and Let's Encrypt challenges cannot reach this host reliably. The server keeps
`fullchain.pem` and `private.key` in `${TLS_CERT_DIR:-./tls}`; Compose mounts that directory read-only
at `/etc/caddy/tls`. The private key must be owned by root with mode `0600`, the directory with mode
`0700`, and neither file may be committed. Certificate renewal is an explicit IT/operations task.

Using separate Caddy site blocks while `API_DOMAIN`, `ADMIN_DOMAIN`, and `S3_DOMAIN` point to the same
host makes Caddy fail with `ambiguous site definition`.

If future ops split domains into distinct hosts, restore separate site blocks and update `.env.prod`
and docs together.

## Environment

Template: `.env.prod.example`.

Required non-default secrets:

- `POSTGRES_PASSWORD`;
- `MINIO_ROOT_PASSWORD`;
- `JWT_SECRET`;
- `POSM_UPDATE_PUBLIC_KEY_SPKI` (public, but pinned and release-controlled);
- `POSM_DEVICE_KEY` only during an explicitly enabled legacy enrollment window;
- `ADMIN_BOOTSTRAP_EMAIL`;
- `ADMIN_BOOTSTRAP_PASSWORD`;
- `ACME_EMAIL`;
- public domain variables.

Important current values/policies:

- Production OTP requires `OTP_DEV_MODE=false`, `OTP_PROVIDER=daribar`,
  `DARIBAR_OTP_BASE_URL=https://prod-backoffice.daribar.com` and a finite request timeout.
- `OTP_DEV_MODE=true` exposes the shared fixed code and is permitted only for local/test environments.
- `S3_PUBLIC_URL` must match the external Caddy route. It is `https://epharm.inkar.kz/s3`.
- Site-catalogue sync and read cutover use separate flags. `ESHOP_CATALOG_TOKEN` is a private
  server-to-server secret, never a browser value. The exporter and access policy are described in
  `21-site-catalog-integration.md` and `ops/eshop-catalog-exporter/README.md`.
- Release preparation checks the private exporter with `tools/smoke-eshop-catalog.sh` when site
  sync is enabled; the legacy Medusa smoke applies only before site preload. Post-deploy smoke
  requires a complete PostgreSQL catalogue generation, a non-empty mobile list and exact-name
  search. On the first snapshot release it waits up to `CATALOG_SNAPSHOT_WAIT_SECONDS` (default
  1800); a broken catalogue triggers application rollback.
- Live storefront/PIM/SSH credentials are documented in their existing credential files and must not be
  copied elsewhere.

## Immutable deploy and rollback

The deployable source snapshot may still be copied to a non-git server directory, but application
images must be built once under an immutable git tag and then started without rebuilding. The full
contract is in `20-reliability-and-release.md`.

```bash
git archive --format=tar.gz -o /tmp/epharm-deploy.tar.gz HEAD \
  admin-panel/backend admin-panel/frontend docker-compose.prod.yml Caddyfile tools .env.prod.example

scp /tmp/epharm-deploy.tar.gz adm-quasar@inkpim.inkar.kz:/tmp/
```

After copying the tagged snapshot and creating the same tag in the server checkout/build workspace:

```bash
cd /home/adm-quasar/epharm
tar xzf /tmp/epharm-deploy.tar.gz
./tools/release/prepare.sh v1.0.0
./tools/release/deploy.sh v1.0.0
```

Do not perform frontend-only mutable production builds. Backend and frontend share one release id so
health, Sentry and rollback evidence always identify a coherent deployment. Use
`./tools/release/rollback.sh <previous-tag>` for application rollback.

### Two-image release on an archive-based host

The generic `deploy.sh` requires a Git checkout and assumes that the old backend and frontend have
one common `RELEASE_ID`. Do **not** run it on an archive-based host with independently pinned images
or an out-of-date `.release.env`: it could select a different backend at the next Compose operation.
The archive transaction instead takes the two currently running tags explicitly, verifies their
content-addressed IDs, and writes persistent per-component pins. It never recreates Caddy or writes
`.env.prod`, `Caddyfile`, database volumes, or POSM release files.

After the exact merged commit has passed CI, build both candidate images once from the reviewed
source archive. Verify that each OCI `org.opencontainers.image.version` label is its image tag and
each `org.opencontainers.image.revision` label is the same full merged commit SHA. Stage the reviewed
Compose file and scripts before making the backup; compare its SHA-256 with the merged source. First
complete the encrypted off-site backup and isolated restore drill required by
`20-reliability-and-release.md`. Then create the additional local rollback bundle of the **running**
backend/frontend images and current config with `prepare-backend-only-backup.sh`; despite its name,
that helper already saves both application images and a PostgreSQL dump. Keep the returned absolute
bundle path. Its checksum manifest must be less than 24 hours old and is reverified by the deploy
script; it does not replace the off-site backup.
The archive validator also verifies every compressed layer digest, uncompressed rootfs diff ID and
runtime image setting against each running rollback tag. Docker/containerd can identify a live OCI
*manifest* by one SHA-256 while `docker save`/`load` identifies its config by another. These IDs
are not interchangeable; after emergency reload the transaction accepts a new image ID only when
the archived rootfs and runtime configuration still match the original running image, then verifies
that both recreated containers use the content-equivalent loaded images.

Run `tools/release/deploy-archive-two-image.sh` with, in order: candidate backend tag, candidate
frontend tag, full merged commit SHA, exact candidate backend image ID, exact candidate frontend
image ID, current backend tag, current frontend tag, verified bundle path, and reviewed Compose
SHA-256. An optional final `--dry-run` checks the images, backup, rendered Compose and current smoke
without switching containers. The live transaction performs the same gates, pins both components in
`.release.env`, recreates only backend/frontend without rebuilding, and verifies public backend/UI
release identities plus catalogue/search. A failed switch automatically restores the exact previous
backend/frontend image IDs, normalizes the stale generic release metadata to those known-good pins,
and smokes the restored stack. A `CRITICAL` rollback message is an incident requiring immediate
operator action; do not continue with another release. The saved transaction record is under
`releases/archive-two-image/active-transaction`.

Before approving a release with new Flyway migrations, test the previous backend against a restored
copy of the forward-migrated database. Application rollback does not undo schema or data changes.
`tools/release/tests/archive-two-image-contract.sh` covers metadata drift, wrong artifact IDs,
dry-run, successful two-image switch, failed smoke/automatic rollback, and tampered backup without
touching production.

### Backend-only bridge release on an archive-based host

The normal `deploy.sh` is for coherent two-image releases from a Git checkout. It must **not** be
used for a backend-only CRM/POSM bridge change on a host whose frontend has a different live tag or
whose deployment directory lacks `.git`. `docker-compose.prod.yml` accepts persistent
`BACKEND_RELEASE_ID`/`BACKEND_RELEASE_COMMIT` and `FRONTEND_RELEASE_ID`/
`FRONTEND_RELEASE_COMMIT` overrides; absent overrides, the existing `RELEASE_ID` contract remains
unchanged. Do not merely run `docker compose up backend` with a temporary shell override: a later
Compose operation would silently select the old image.

After PR merge and exact-HEAD CI, stage the merged source archive without touching `.env.prod`,
the running frontend image, database volumes, or POSM release. Build a uniquely tagged backend
image once from that archive with `RELEASE_ID=<backend-tag>` and
`RELEASE_COMMIT=<merged-commit>` build arguments. Record its Docker image ID; compare its OCI
version/revision labels to the merged commit. Install the **same audited** Compose file and release
scripts into the production deployment directory. Update the protected `.env.prod` only after
verifying the private CRM endpoint and public staff page: enable the task bridge to
`10.10.1.80:8080` (or its verified HTTPS origin), set the exact
`MERCH_TASKS_STAFF_URL=https://crm.inkar.kz/staff`, set a bounded concurrency limit, and point
`MERCH_PORTAL_UPSTREAM` away from the obsolete `.90` origin. Before switching the backend, validate
the audited Caddyfile, recreate only Caddy, and verify that `/merch/staff?task=<harmless-probe>`
returns 302 to `https://crm.inkar.kz/staff?task=<harmless-probe>` with `no-referrer`/`no-store`,
while CRM `/staff` HTML and its root `/assets` load over HTTPS. The redirect is essential because
CRM's HTML uses root-absolute assets that do not work under an ePharm `/merch` reverse proxy.
Also verify public `/api/health`, admin UI, and recommendation traffic; restore the previous Caddy
config/container if these checks fail. New QR codes go directly to the CRM fragment URL and must
not depend on the legacy route. Do not assert old QR links remain valid: rescan a refreshed QR.

Before changing the backend, require a recent successful encrypted off-site backup and isolated
restore-test per this document. A local checksum bundle **does not replace** that policy. Create
the additional local rollback bundle _after_ final env/Compose/Caddy edits, so its config hashes
match the files being deployed. The example path is a dedicated root-owned, mode-0700 directory;
the helper refuses a differently owned existing root rather than changing its permissions:

```bash
sudo ./tools/release/prepare-backend-only-backup.sh \
  <running-backend-tag> <running-frontend-tag> /home/adm-quasar/epharm-backend-only-backups
```

The helper creates a mode-0700 directory with a custom-format PostgreSQL dump, protected env and
Compose/Caddy snapshots, exact running backend/frontend Docker image archives and a verified SHA-256
manifest. It validates the dump archive and both gzip files. Keep the returned absolute directory;
the deploy script refuses a stale, altered or wrong-version bundle. Never add these files to Git.

Use the exact reviewed Compose SHA-256 and candidate image ID, not a mutable tag alone:

```bash
sudo ./tools/release/deploy-backend-only.sh \
  <backend-tag> <merged-commit-sha> <backend-image-sha256-id> \
  <running-frontend-tag> <verified-backup-directory> <reviewed-compose-sha256>
```

This transaction checks the merged Compose checksum, candidate image ID/OCI labels, previous image,
unchanged frontend image **and container**, exact CRM bridge configuration, recent backup and rendered
Compose model. It changes only the backend container, persists distinct component identities in
`.release.env`, and smoke-tests `/api/health`, the unchanged frontend `/release.json`, catalogue and
search. Failure during the switch restores the previous backend image and pinned frontend identity;
an unverified rollback emits `CRITICAL` and needs immediate operator action. No task/QR record is
created by the release scripts. Accept merchandising only after a real task's QR opens and `.80`
records a successful shown ACK; also confirm an invalid token is denied and recommendation/fulfilment
latency/error rates remain unchanged.

For a delayed regression after successful deployment, use the saved transaction directory from
`releases/backend-only/active-transaction` with the dedicated helper (not generic `rollback.sh`):

```bash
sudo ./tools/release/rollback-backend-only.sh \
  <saved-transaction-directory> /home/adm-quasar/epharm-backend-only-backups
```

It first makes a fresh backup of the _current_ backend, checks the transaction's previous image and
frontend pin, then atomically restores the previous release env, changes backend only and smoke-tests
both versions. It restores the candidate if that rollback fails. If the previous image tag was
pruned, reload it from the verified version-specific archive before retrying. Flyway migrations are
forward-only; confirm the previous backend remains schema-compatible before any rollback. The
existing full-release scripts remain the path for ordinary two-image releases.

## POSM Fleet Auto-update

Existing POSM v1.0.46+ installations poll `GET /api/posm/app/version` at least every five minutes.
Legacy v1.0.44 installations use the former 30-minute interval, so a fleet rollout must be observed
for at least one full legacy interval before coverage is reported.
Current POSM rejects the old remote HTTP `:8060` route, so a working public HTTPS ingress is a hard
rollout prerequisite. Publish production
archives in the public artifact-only repository `sabirovv17/epharm-posm-releases`; do not make the
private source repository public merely to distribute binaries.

Release gate:

1. Build and test the Windows update/bridge archive.
2. Confirm that it contains no `posm.json`, credentials, keys, pharmacy IDs, or source files.
3. Upload it to a versioned GitHub release and commit the same artifact to the artifact-only
   repository. Pin the distribution URL to that exact commit through jsDelivr; do not use a mutable
   branch URL for production.
4. Download the final CDN URL anonymously with a Range request; verify ZIP integrity, size, and
   SHA-256. Repeat the check against the GitHub release recovery asset.
5. Run `tools/sign-posm-release.sh` with the offline P-256 key. Confirm its public SPKI matches the
   backend environment and the independently provisioned POSM config.
6. Back up `app_releases`, then register the HTTPS URL, exact SHA-256 and manifest signature as the
   current `win-x64` release. Tampered URL/hash/signature must fail acceptance.
7. Monitor backend `POSM update check` logs and Redis presence telemetry until active version-reporting
   devices move to the target version. Offline devices update at their next launch/network session.

The updater deliberately preserves `C:\Epharm\posm.json`, so the pharmacy ID and device configuration
do not come from the shared release and are not overwritten by fleet updates.

## Health Checks

```bash
curl https://epharm.inkar.kz/api/health
curl -I https://epharm.inkar.kz/
curl -I https://epharm.inkar.kz/s3/epharm-receipts/epharm-demo.apk
```

Server-side:

```bash
cd /home/adm-quasar/epharm
docker compose --env-file .env.prod -f docker-compose.prod.yml ps
docker compose --env-file .env.prod -f docker-compose.prod.yml logs -f backend
docker logs epharm-caddy --tail 100
```

## Backups

`tools/backup-all.sh` creates atomic, checksummed PostgreSQL and MinIO artifacts and then copies them
to an encrypted restic repository with independent retention. systemd runs it daily. A weekly
`tools/restore-test.sh` restores into isolated disposable containers and exports success metrics.
Installation, failure semantics and restore acceptance are documented in
`20-reliability-and-release.md`. A backup is not accepted until both local and off-site copies plus a
recent restore-test are visible in monitoring.

## Known Operational Risks

- The historical HTTPS gateway outage is resolved in the 2026-09-23 audit, but public TLS and
  `/api/health` remain release gates. Remote `:8060` HTTP is rejected by current POSM.
- The temporary HTTP metadata fallback is not a final trust boundary: an attacker able to alter both
  release metadata and its SHA-256 could redirect an old client to another HTTPS archive. Repair the
  external HTTPS ingress, remove the HTTP fallback, and add a pinned signing key for update manifests
  before treating the update channel as fully hardened.
- Receipt photos are in a public-readable MinIO bucket. The release checklist tracks private bucket +
  presigned URL work.
- Storefront/PIM/SSH credentials present in existing docs need rotation.
- Daribar is an external production dependency for OTP. Monitor request failures and keep the legacy
  p1sms configuration disabled unless an explicit provider rollback is planned.
- Single backend instance is assumed for payout scheduling unless a distributed lock is added.
- Keep the old Medusa credentials only for the bounded rollback window after site cutover; verify
  that the new backend issues no Medusa requests while site reads are enabled.
