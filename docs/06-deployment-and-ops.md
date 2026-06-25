# Deployment and Operations

## Current Server

Current shared environment:

| Item       | Value                                    |
| ---------- | ---------------------------------------- |
| Host       | `medusa-test`                            |
| Public URL | `https://epharm.78-140-246-238.sslip.io` |
| Deploy dir | `/root/epharm`                           |
| Stack      | Docker Compose + Caddy                   |

The active public setup is one host with path routing. Do not assume `api.epharm.kz`,
`admin.epharm.kz`, or `s3.epharm.kz` are active unless DNS and `.env.prod` have been changed and
`Caddyfile` has been changed with them.

## Production Stack

`docker-compose.prod.yml` runs:

- `postgres` (`postgres:16-alpine`);
- `redis` (`redis:7-alpine`);
- `minio`;
- `minio-init`;
- `backend`;
- `frontend`;
- `caddy`.

Backend and frontend are not exposed directly. Caddy publishes ports 80/443/443-udp.

MinIO console is bound to `127.0.0.1:${MINIO_CONSOLE_PORT:-9001}` and should be reached through an SSH
tunnel or VPN.

## Caddy

Current `Caddyfile` intentionally uses one site block for `{$ADMIN_DOMAIN}` and handles:

- `/s3/*` -> MinIO with prefix stripped;
- `/api/*` -> backend;
- everything else -> frontend.

This was changed after the 2026-06-22 incident: setting `API_DOMAIN`, `ADMIN_DOMAIN`, and `S3_DOMAIN`
to the same sslip host while keeping three site blocks makes Caddy fail with `ambiguous site definition`.

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

- `OTP_DEV_MODE=false` is the intended production setting, but pilots may still use dev OTP by decision.
- `S3_PUBLIC_URL` must match the external Caddy route. For one-host sslip it should include `/s3`.
- Medusa defaults in compose are publishable storefront ids, not admin/root secrets.
- Live storefront/PIM/SSH credentials are documented in their existing credential files and must not be
  copied elsewhere.

## Deploy From Git

The server deploy dir is not necessarily a git checkout. The reproducible deploy pattern is:

```bash
git archive --format=tar.gz -o /tmp/epharm-deploy.tar.gz HEAD \
  admin-panel/backend admin-panel/frontend docker-compose.prod.yml Caddyfile tools .env.prod.example

scp -i ~/.ssh/epharm_deploy /tmp/epharm-deploy.tar.gz root@78.140.246.238:/tmp/
```

On the server:

```bash
cd /root/epharm
tar xzf /tmp/epharm-deploy.tar.gz admin-panel/backend admin-panel/frontend Caddyfile
bash tools/pg-backup.sh
docker compose --env-file .env.prod -f docker-compose.prod.yml up -d --build backend frontend caddy
```

When deploying only frontend, prefer:

```bash
docker compose --env-file .env.prod -f docker-compose.prod.yml up -d --build --no-deps frontend
```

without `--no-deps`, Compose may recreate backend because frontend depends on it.

## Health Checks

```bash
curl https://epharm.78-140-246-238.sslip.io/api/health
curl -I https://epharm.78-140-246-238.sslip.io/
curl -I https://epharm.78-140-246-238.sslip.io/s3/epharm-receipts/epharm-demo.apk
```

Server-side:

```bash
cd /root/epharm
docker compose --env-file .env.prod -f docker-compose.prod.yml ps
docker compose --env-file .env.prod -f docker-compose.prod.yml logs -f backend
docker logs epharm-caddy --tail 100
```

## Backups

`tools/pg-backup.sh` creates compressed Postgres dumps. Before migrations/deploys, run it manually.
Production should also have a cron/off-site backup and a tested restore procedure.

Example cron:

```cron
0 3 * * * /root/epharm/tools/pg-backup.sh >> /root/epharm/backups/backup.log 2>&1
```

## Known Operational Risks

- Receipt photos are in a public-readable MinIO bucket. The release checklist tracks private bucket +
  presigned URL work.
- Storefront/PIM/SSH credentials present in existing docs need rotation.
- Real SMS provider is not connected.
- Single backend instance is assumed for payout scheduling unless a distributed lock is added.
- Medusa still uses HTTP on raw IP; backend/browser image proxy mitigates mixed content for images, not
  the broader TLS/allowlist concern.
