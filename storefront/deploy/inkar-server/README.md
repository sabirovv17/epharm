# Inkar server staged deployment

This isolated bundle targets `inkeshopapteka.inkar.kz` (`10.10.1.80`, 4 CPU,
6 GB RAM, 250 GB disk). It does not modify the existing production Compose or
systemd configuration and never connects to a remote host by itself.

At authoring time `10.10.1.80:22` was unreachable from both the deployment
workstation and PIM host. Execution must wait for an Inkar VPN, bastion/NAT
route and working internal DNS.

## Stack and persistence

- PostgreSQL 16 with all checksum-guarded migrations, including catalog,
  orders, push state and `002_cdp_mvp.sql`;
- blue/green Next.js slots with shallow liveness and deep dependency readiness;
- Nginx internal media cache/router and Caddy HTTP/TLS edge;
- pharmacy prices and stock read from ClickHouse on the PIM host through a
  dedicated SSH tunnel (no ClickHouse host port);
- persistent data below `/srv/inkar-shop`: PostgreSQL, uploads, runtime data,
  media/proxy caches, backups, import manifests and Caddy certificate state;
- bounded Docker JSON logs (five 10 MB files per container);
- demo auth by default (`CUSTOMER_AUTH_MODE=demo`) with SMS provider secrets empty;
- an authoritative local PostgreSQL catalogue plus bounded ClickHouse
  price/stock synchronization; Medusa is not required in the active path.

PostgreSQL and web slots have no host ports. Only Caddy binds TCP 80/443.
The SSH tunnel binds only Docker's private host-gateway address.
Limits are sized so a brief two-slot canary fits on a 6 GB host; the old slot is
stopped after a successful switch but its immutable image remains for rollback.

## Network and ACME gates

Do not bootstrap until:

1. `ssh adm-quasar@10.10.1.80` works through an approved route.
2. The server reaches `10.10.1.76:22` and the container registry.
3. Docker Engine + Compose v2, `curl`, `openssl`, `git` and 20 GB free disk are
   available.
4. `/opt/inkar-shop/current` points to reviewed release source.
5. TCP 22 is VPN/bastion restricted; 80/443 follow the intended exposure.

Public ACME works only if public DNS A/AAAA reaches this edge (directly or by
NAT), inbound 80/443 and outbound HTTPS work. A public CA cannot validate the
private RFC1918 address. For internal-only DNS, keep HTTP behind the approved
private load balancer or replace Caddy TLS with Inkar's internal PKI policy.
When public DNS contains a NAT address instead of `10.10.1.80`, run the host
gate with `PREFLIGHT_EXPECTED_DNS_IP=<public-ip>`.

Run the non-mutating host gate on the new server before `prepare`:

```bash
./deploy/inkar-server/preflight-host.sh
```

It only reads Ubuntu/CPU/RAM/disk, sudo, Docker/Compose, listeners on 80/443,
DNS and kernel/catalog-source routes. It installs and starts nothing and exits non-zero
while a required gate is missing.

## First install

```bash
sudo install -d -o adm-quasar -g adm-quasar /opt/inkar-shop /srv/inkar-shop
cd /opt/inkar-shop/current
chmod +x deploy/inkar-server/*.sh
./deploy/inkar-server/deploy.sh prepare
```

`prepare` generates stable DB/application secrets directly in
`/srv/inkar-shop/config` with mode `0600` and prints none. It never generates
Medusa credentials. Edit:

- `/srv/inkar-shop/config/app.env`: keep `MEDUSA_ENABLED=false`, configure
  ClickHouse user/password and the optional Yandex browser key;
- `/srv/inkar-shop/state/release.env`: domain/IP only if final networking differs;
- `/srv/inkar-shop/config/clickhouse-tunnel.env`: PIM SSH endpoint and the
  ClickHouse container address reachable from the PIM host;
- `/srv/inkar-shop/config/image-import.env`: only for reviewed SKU image import.

For the one explicitly supported legacy Medusa HTTP endpoint, use that exact URL
with `MEDUSA_ALLOW_INSECURE_LEGACY_HTTP=true`; never enable this for another host
and remove it as soon as Medusa receives TLS.

### Private ClickHouse tunnel

The Compose operation container resolves `host.docker.internal` to Docker's
host gateway. The tunnel binds `18123` only on that gateway, not on
`0.0.0.0`. Use a dedicated key; never store an SSH password in a unit or env
file.

1. Have PIM operations install the dedicated public key for `adm-quasar` on
   `10.10.1.76`. Prefer an `authorized_keys` restriction such as
   `restrict,permitopen="<clickhouse-container-ip>:8123"`.
2. Put the private key at the `SSH_KEY_PATH` from
   `/srv/inkar-shop/config/clickhouse-tunnel.env` with mode `0600`.
3. Obtain the SSH host-key fingerprint from PIM operations. A locally scanned
   key must be compared out of band before it is installed as
   `/srv/inkar-shop/config/clickhouse-known-hosts`; do not accept it blindly.
4. Set `REMOTE_CLICKHOUSE_HOST` to the ClickHouse container IP (or to a
   loopback-only port explicitly published by PIM operations), then start:

```bash
sudo cp deploy/inkar-server/systemd/inkar-shop-clickhouse-tunnel.service \
  /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now inkar-shop-clickhouse-tunnel.service
systemctl --no-pager --full status inkar-shop-clickhouse-tunnel.service
gateway="$(docker network inspect bridge \
  --format '{{with (index .IPAM.Config 0)}}{{.Gateway}}{{end}}')"
timeout 5 bash -c 'exec 3<>/dev/tcp/"$1"/18123' _ "$gateway"
```

If the ClickHouse container is recreated with a different address, PIM
operations must provide a stable loopback publication or the tunnel config
must be updated and restarted. A small read-only API with TLS is the preferred
long-term replacement for this temporary forwarding layer.

```bash
./deploy/inkar-server/deploy.sh preflight
./deploy/inkar-server/deploy.sh bootstrap
```

Bootstrap builds an immutable image, generates stable VAPID keys, starts
PostgreSQL, applies all migrations and checks row counts. An empty DB triggers
one full catalog reconcile and the resumable full offer backfill. A seeded DB
gets only incremental syncs. Traffic is not exposed until
`/api/health?deep=1` passes. A full offer pass can take hours; do not start a
second pass. Fix upstream and rerun the same bootstrap so checkpoints resume.

## TLS cutover

First verify HTTP without relying on DNS:

```bash
curl -fsS -H 'Host: inkeshopapteka.inkar.kz' http://127.0.0.1/api/health
```

After DNS/NAT and 80/443 are correct:

```bash
./deploy/inkar-server/deploy.sh enable-tls
curl -fsS 'https://inkeshopapteka.inkar.kz/api/health?deep=1'
```

The command restores the HTTP Caddyfile if the HTTPS probe fails. Certificate
state persists under `/srv/inkar-shop/data/caddy-*`.

## Release, rollback, backup

```bash
./deploy/inkar-server/deploy.sh release
./deploy/inkar-server/deploy.sh rollback
./deploy/inkar-server/backup.sh
```

Release order is preflight → DB backup → image build → migrations → bounded
sync → inactive-slot liveness/deep readiness → router switch → old-slot stop.
Rollback starts and checks the retained previous image before switching.
Database migrations are not reversed and must stay backward-compatible.
Database backups do not contain `/srv/inkar-shop/config`, uploads or Caddy
state; back those paths up encrypted and off-host before cutover. Never put
their contents in the source archive.

All mutating lifecycle commands share the non-blocking advisory lock
`/srv/inkar-shop/state/operation.lock` (group inherited from the state
directory, mode `0660`). Bootstrap, release, rollback, TLS cutover, backup,
restore, syncs and the image write canary fail before mutation when another
operation owns the lock.

Restore is deliberately destructive, validates an ordinary backup file inside
the approved directory, and needs enough privilege to stop installed systemd
jobs. It records which timers were active, stops all installed Inkar timers and
services before traffic/database changes, and restarts only the previously
active timers after deep health succeeds:

```bash
sudo ./deploy/inkar-server/restore.sh --yes \
  /srv/inkar-shop/backups/inkar-postgres-YYYYMMDDTHHMMSSZ.dump
```

On restore failure both storefront traffic and the Inkar timers stay stopped
for operator review. Fix the cause and rerun the restore; do not manually
restart timers against an unvalidated database. The lock file remains group
writable for the `adm-quasar` systemd jobs.

Copy and enable schedules only after initial backfill and deep health pass.
The offer timer requires the ClickHouse tunnel unit:

```bash
sudo cp deploy/inkar-server/systemd/*.{service,timer} /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now inkar-shop-backup.timer \
  inkar-shop-clickhouse-tunnel.service \
  inkar-shop-offer-sync.timer
systemctl list-timers 'inkar-shop-*'
```

The offer timer calls the bounded ClickHouse synchronization. Do not enable
`inkar-shop-catalog-sync.timer` while `MEDUSA_ENABLED=false`; the local
PostgreSQL catalogue is restored from the approved seed. Full offer
reconciliation remains explicit:

```bash
ALLOW_FULL_BACKFILL=1 ./deploy/inkar-server/deploy.sh sync-offers-full
```

## SKU and image transfer

Copy extracted source images without placing admin credentials in source or the
storefront environment:

```bash
rsync -a --checksum --info=progress2 ./sku_images/ \
  adm-quasar@10.10.1.80:/srv/inkar-shop/imports/sku-images/
./deploy/inkar-server/deploy.sh images-dry-run
```

Review `/srv/inkar-shop/manifests/sku-images-dry-run.jsonl` for quarantined and
ambiguous mappings. Only then run a five-item write canary:

```bash
ALLOW_IMAGE_WRITE=1 ./deploy/inkar-server/deploy.sh images-canary
```

After checking the five changed products in both Medusa and the storefront,
run the explicitly gated full import:

```bash
ALLOW_IMAGE_WRITE=1 ALLOW_FULL_IMAGE_IMPORT=1 \
  ./deploy/inkar-server/deploy.sh images-apply
```

The timestamped apply manifest is retained under
`/srv/inkar-shop/manifests`; do not rerun blindly after a partial failure.
The supplied archive covers only part of the catalog. Missing source photos
must not be fabricated or mapped to unrelated products.

## Validation and acceptance

Without starting containers:

```bash
./deploy/inkar-server/validate-package.sh

APP_ENV_FILE="$PWD/deploy/inkar-server/app.env.example" \
POSTGRES_ENV_FILE="$PWD/deploy/inkar-server/postgres.env.example" \
DATA_ROOT=/tmp/inkar-data \
CADDYFILE_PATH="$PWD/deploy/inkar-server/Caddyfile.http" \
docker compose --env-file deploy/inkar-server/release.env.example \
  --file deploy/inkar-server/compose.yml config --quiet
```

After deploy, TLS, initial catalog/offers backfill and a known PDP are healthy,
run the bounded acceptance soak in the foreground or a supervised terminal:

```bash
SOAK_PRODUCT_SLUG=vezilyut-peptid-bio-200mg-30-kaps \
  ./deploy/inkar-server/soak.sh
```

The default run is capped at six hours, 73 samples (t=0 through t=6h) and one sample every five
minutes. It performs GET-only checks for shallow/deep health, catalog API and
filtering, catalog HTML, the known product API and PDP. It never creates a
cart, checkout, order or customer session. Per-request status/latency and the
final threshold summary are written to `/srv/inkar-shop/manifests/soak-*.tsv`
and `soak-*.summary.txt`. Three consecutive shallow/deep critical-cycle
failures abort early; the command exits non-zero when availability or p95
limits fail.

For an HTTP-only localhost gate before DNS/TLS, preserve virtual-host routing:

```bash
SOAK_BASE_URL=http://127.0.0.1 \
SOAK_HOST_HEADER=inkeshopapteka.inkar.kz \
SOAK_PRODUCT_SLUG=vezilyut-peptid-bio-200mg-30-kaps \
  ./deploy/inkar-server/soak.sh
```

Before release also run tests, lint, type-check and build. After deploy verify
shallow/deep health, catalog pagination and filters, PDP/media, cart, demo
checkout/order persistence, admin reads, CDP event ingestion, mobile breakpoints
and both sync timer journals. Keep timers disabled during a six-hour soak if an
unexpected Medusa load spike appears; the advisory lock prevents overlap but
cannot make a slow upstream faster.

The `/admin` UI is part of the same Next.js image and is deployed with the
storefront. Flutter is a client build, not a server daemon: build Android/iOS
with `https://inkeshopapteka.inkar.kz` as every API/content/media origin, then
distribute the APK or TestFlight build separately after this HTTPS endpoint
passes acceptance.
