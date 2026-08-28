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

Current external-gateway state (verified 2026-07-21): TLS presents the expired `*.inteq.kz`
certificate (expired 2026-05-23) and HTTPS `/api/health` returns `404`, while the
trusted HTTP ingress `:8060/api/health` returns `200`. This proves that the application upstream is
reachable but Host/SNI routing on `2.133.92.203:443` remains incomplete. IT must fix the external
listener; application deployment alone cannot repair that gateway.

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
- everything else -> frontend.

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
- `POSM_DEVICE_KEY`;
- `ADMIN_BOOTSTRAP_EMAIL`;
- `ADMIN_BOOTSTRAP_PASSWORD`;
- `ACME_EMAIL`;
- public domain variables.

Important current values/policies:

- Production OTP requires `OTP_DEV_MODE=false`, `OTP_PROVIDER=daribar`,
  `DARIBAR_OTP_BASE_URL=https://prod-backoffice.daribar.com` and a finite request timeout.
- `OTP_DEV_MODE=true` exposes the shared fixed code and is permitted only for local/test environments.
- `S3_PUBLIC_URL` must match the external Caddy route. It is `https://epharm.inkar.kz/s3`.
- Medusa defaults in compose are publishable storefront ids, not admin/root secrets.
- Live storefront/PIM/SSH credentials are documented in their existing credential files and must not be
  copied elsewhere.

## Deploy From Git

The server deploy dir is not necessarily a git checkout. The reproducible deploy pattern is:

```bash
git archive --format=tar.gz -o /tmp/epharm-deploy.tar.gz HEAD \
  admin-panel/backend admin-panel/frontend docker-compose.prod.yml Caddyfile tools .env.prod.example

scp /tmp/epharm-deploy.tar.gz adm-quasar@inkpim.inkar.kz:/tmp/
```

On the server:

```bash
cd /home/adm-quasar/epharm
tar xzf /tmp/epharm-deploy.tar.gz admin-panel/backend admin-panel/frontend Caddyfile
bash tools/pg-backup.sh
docker compose --env-file .env.prod -f docker-compose.prod.yml up -d --build backend frontend caddy
```

When deploying only frontend, prefer:

```bash
docker compose --env-file .env.prod -f docker-compose.prod.yml up -d --build --no-deps frontend
```

without `--no-deps`, Compose may recreate backend because frontend depends on it.

## POSM Fleet Auto-update

Existing POSM v1.0.46+ installations poll `GET /api/posm/app/version` at least every five minutes.
Until the external INKAR HTTPS ingress is repaired, API metadata can arrive through
`http://epharm.inkar.kz:8060`, but the executable archive itself must use HTTPS. Publish production
archives in the public artifact-only repository `sabirovv17/epharm-posm-releases`; do not make the
private source repository public merely to distribute binaries.

Release gate:

1. Build and test the Windows update/bridge archive.
2. Confirm that it contains no `posm.json`, credentials, keys, pharmacy IDs, or source files.
3. Upload it to a versioned GitHub release in the artifact repository.
4. Download the release anonymously with redirects and a Range request; verify ZIP integrity, size,
   and SHA-256.
5. Back up the `app_releases` table, then register the HTTPS URL and exact SHA-256 as the current
   `win-x64` release.
6. Monitor backend `POSM update check` logs and Redis presence telemetry until active version-reporting
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

`tools/pg-backup.sh` creates compressed Postgres dumps. Before migrations/deploys, run it manually.
Production should also have a cron/off-site backup and a tested restore procedure.

Example cron:

```cron
0 3 * * * /home/adm-quasar/epharm/tools/pg-backup.sh >> /home/adm-quasar/epharm/backups/backup.log 2>&1
```

## Known Operational Risks

- External `epharm.inkar.kz:443` currently has the wrong expired certificate and returns gateway
  `404`; POSM can use `:8060` only from
  pharmacy networks that permit that outbound port.
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
- Medusa still uses HTTP on raw IP; backend/browser image proxy mitigates mixed content for images, not
  the broader TLS/allowlist concern.
