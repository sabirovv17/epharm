#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(CDPATH= cd -- "${SCRIPT_DIR}/../.." && pwd)"
COMPOSE_FILE="${SCRIPT_DIR}/compose.yml"
STATE_ROOT="${INKAR_STATE_ROOT:-/srv/inkar-shop}"
CONFIG_ROOT="${STATE_ROOT}/config"
DATA_ROOT="${STATE_ROOT}/data"
BACKUP_ROOT="${STATE_ROOT}/backups"
IMPORT_ROOT="${STATE_ROOT}/imports"
MANIFEST_ROOT="${STATE_ROOT}/manifests"
STATE_FILE="${STATE_ROOT}/state/release.env"
OPERATION_LOCK_FILE="${STATE_ROOT}/state/operation.lock"
APP_ENV_FILE="${CONFIG_ROOT}/app.env"
POSTGRES_ENV_FILE="${CONFIG_ROOT}/postgres.env"
IMAGE_IMPORT_ENV_FILE="${CONFIG_ROOT}/image-import.env"
CLICKHOUSE_TUNNEL_ENV_FILE="${CONFIG_ROOT}/clickhouse-tunnel.env"
PROJECT_NAME="inkar-shop"

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
note() { printf '[inkar-deploy] %s\n' "$*"; }
need() { command -v "$1" >/dev/null 2>&1 || die "required command is missing: $1"; }

operation_lock_is_inherited() {
  [[ "${INKAR_OPERATION_LOCK_FD:-}" == "9" && -e /proc/$$/fd/9 ]] || return 1
  [[ "$(readlink -f /proc/$$/fd/9 2>/dev/null || true)" == "$(readlink -f "$OPERATION_LOCK_FILE" 2>/dev/null || true)" ]]
}

prepare_operation_lock_file() {
  local lock_directory desired_group current_group current_mode
  lock_directory="$(dirname -- "$OPERATION_LOCK_FILE")"
  mkdir -p "$lock_directory"
  if [[ ! -e "$OPERATION_LOCK_FILE" ]]; then
    (umask 0007; : >"$OPERATION_LOCK_FILE")
  fi
  desired_group="$(stat -c '%g' "$lock_directory")"
  current_group="$(stat -c '%g' "$OPERATION_LOCK_FILE")"
  if [[ "$current_group" != "$desired_group" ]]; then
    chgrp "$desired_group" "$OPERATION_LOCK_FILE" \
      || die "cannot align operation lock group with ${lock_directory}"
  fi
  current_mode="$(stat -c '%a' "$OPERATION_LOCK_FILE")"
  if [[ "$current_mode" != "660" ]]; then
    chmod 0660 "$OPERATION_LOCK_FILE" || die "cannot set operation lock permissions"
  fi
}

acquire_operation_lock() {
  operation_lock_is_inherited && return 0
  need flock
  prepare_operation_lock_file
  exec 9>>"$OPERATION_LOCK_FILE"
  flock -n 9 || die "another deploy/sync/backup/restore operation owns ${OPERATION_LOCK_FILE}"
  export INKAR_OPERATION_LOCK_FD=9
}
file_value() {
  local file="$1" key="$2"
  awk -F= -v wanted="$key" '$0 !~ /^[[:space:]]*#/ && $1 == wanted { sub(/^[^=]*=/, ""); print; exit }' "$file"
}

replace_token() {
  local file="$1" token="$2" value="$3" temporary
  temporary="$(mktemp "${file}.tmp.XXXXXX")"
  awk -v token="$token" -v value="$value" '{ gsub(token, value); print }' "$file" >"$temporary"
  chmod --reference="$file" "$temporary"
  mv -f -- "$temporary" "$file"
}

state_value() { file_value "$STATE_FILE" "$1"; }

set_state() {
  local key="$1" value="$2" temporary
  [[ "$key" =~ ^[A-Z0-9_]+$ ]] || die "invalid state key: $key"
  [[ "$value" != *$'\n'* ]] || die "state values must be one line"
  temporary="$(mktemp "${STATE_FILE}.tmp.XXXXXX")"
  awk -F= -v wanted="$key" -v replacement="$value" '
    BEGIN { found = 0 }
    $1 == wanted { print wanted "=" replacement; found = 1; next }
    { print }
    END { if (!found) print wanted "=" replacement }
  ' "$STATE_FILE" >"$temporary"
  chmod 600 "$temporary"
  mv -f -- "$temporary" "$STATE_FILE"
}

commit_active_state() {
  local slot="$1" upstream="$2" image="$3" temporary
  [[ "$slot" == "blue" || "$slot" == "green" ]] || return 1
  [[ "$upstream" == "web-blue:3000" || "$upstream" == "web-green:3000" ]] || return 1
  [[ -n "$image" && "$image" != *$'\n'* ]] || return 1
  temporary="$(mktemp "${STATE_FILE}.tmp.XXXXXX")"
  if ! awk -F= -v slot="$slot" -v upstream="$upstream" -v image="$image" '
    BEGIN { active = 0; route = 0; deploy = 0 }
    $1 == "ACTIVE_SLOT" { print "ACTIVE_SLOT=" slot; active = 1; next }
    $1 == "ACTIVE_WEB_UPSTREAM" { print "ACTIVE_WEB_UPSTREAM=" upstream; route = 1; next }
    $1 == "DEPLOY_IMAGE" { print "DEPLOY_IMAGE=" image; deploy = 1; next }
    { print }
    END {
      if (!active) print "ACTIVE_SLOT=" slot
      if (!route) print "ACTIVE_WEB_UPSTREAM=" upstream
      if (!deploy) print "DEPLOY_IMAGE=" image
    }
  ' "$STATE_FILE" >"$temporary"; then
    rm -f -- "$temporary"
    return 1
  fi
  chmod 600 "$temporary"
  mv -f -- "$temporary" "$STATE_FILE"
}

prepare() {
  need openssl
  umask 077
  mkdir -p "$CONFIG_ROOT" "${STATE_ROOT}/state" "$BACKUP_ROOT" \
    "${IMPORT_ROOT}/sku-images" "$MANIFEST_ROOT" \
    "${DATA_ROOT}/postgres" "${DATA_ROOT}/uploads" \
    "${DATA_ROOT}/medusa-cache" "${DATA_ROOT}/proxy-cache" \
    "${DATA_ROOT}/runtime" "${DATA_ROOT}/caddy-data" "${DATA_ROOT}/caddy-config"

  [[ -f "$STATE_FILE" ]] || cp -- "${SCRIPT_DIR}/release.env.example" "$STATE_FILE"
  if [[ ! -f "$APP_ENV_FILE" || ! -f "$POSTGRES_ENV_FILE" ]]; then
    [[ ! -e "$APP_ENV_FILE" && ! -e "$POSTGRES_ENV_FILE" ]] \
      || die "app.env and postgres.env must either both exist or both be absent"
    local database_password
    database_password="$(openssl rand -hex 32)"
    cp -- "${SCRIPT_DIR}/app.env.example" "$APP_ENV_FILE"
    cp -- "${SCRIPT_DIR}/postgres.env.example" "$POSTGRES_ENV_FILE"
    replace_token "$APP_ENV_FILE" "__DATABASE_PASSWORD__" "$database_password"
    replace_token "$POSTGRES_ENV_FILE" "__DATABASE_PASSWORD__" "$database_password"
    replace_token "$APP_ENV_FILE" "__ADMIN_TOKEN__" "$(openssl rand -hex 32)"
    replace_token "$APP_ENV_FILE" "__CUSTOMER_AUTH_SECRET__" "$(openssl rand -hex 32)"
    replace_token "$APP_ENV_FILE" "__CHECKOUT_QUOTE_SECRET__" "$(openssl rand -hex 32)"
    replace_token "$APP_ENV_FILE" "__CDP_HASH_SECRET__" "$(openssl rand -hex 32)"
    note "generated local secrets in ${CONFIG_ROOT}; no secret was printed"
  fi
  [[ -f "$IMAGE_IMPORT_ENV_FILE" ]] || cp -- "${SCRIPT_DIR}/image-import.env.example" "$IMAGE_IMPORT_ENV_FILE"
  [[ -f "$CLICKHOUSE_TUNNEL_ENV_FILE" ]] \
    || cp -- "${SCRIPT_DIR}/clickhouse-tunnel.env.example" "$CLICKHOUSE_TUNNEL_ENV_FILE"
  chmod 600 "$STATE_FILE" "$APP_ENV_FILE" "$POSTGRES_ENV_FILE" \
    "$IMAGE_IMPORT_ENV_FILE" "$CLICKHOUSE_TUNNEL_ENV_FILE"
  note "prepared ${STATE_ROOT}; edit ${APP_ENV_FILE} before preflight"
}

require_state() {
  [[ -f "$STATE_FILE" ]] || die "missing ${STATE_FILE}; run prepare"
  [[ -f "$APP_ENV_FILE" ]] || die "missing ${APP_ENV_FILE}; run prepare"
  [[ -f "$POSTGRES_ENV_FILE" ]] || die "missing ${POSTGRES_ENV_FILE}; run prepare"
}

require_non_placeholder() {
  local key="$1" minimum="${2:-1}" value
  value="$(file_value "$APP_ENV_FILE" "$key")"
  [[ ${#value} -ge "$minimum" ]] || die "${key} is missing or shorter than ${minimum} characters"
  [[ ! "$value" =~ (replace|example|change_me|xxxxxxxx) ]] || die "${key} still contains a placeholder"
}

validate_environment() {
  require_state
  local domain server_ip auth_mode sms_provider database_url medusa_url medusa_enabled postgres_password mode
  local offer_source clickhouse_url tunnel_key tunnel_known_hosts tunnel_remote
  local epharm_sync_enabled epharm_base_url epharm_start_at
  domain="$(state_value SITE_DOMAIN)"
  server_ip="$(state_value SERVER_IP)"
  auth_mode="$(file_value "$APP_ENV_FILE" CUSTOMER_AUTH_MODE)"
  sms_provider="$(file_value "$APP_ENV_FILE" SMS_PROVIDER)"
  database_url="$(file_value "$APP_ENV_FILE" DATABASE_URL)"
  medusa_url="$(file_value "$APP_ENV_FILE" MEDUSA_URL)"
  medusa_enabled="$(file_value "$APP_ENV_FILE" MEDUSA_ENABLED)"
  offer_source="$(file_value "$APP_ENV_FILE" CATALOG_OFFER_SOURCE)"
  clickhouse_url="$(file_value "$APP_ENV_FILE" CLICKHOUSE_URL)"
  postgres_password="$(file_value "$POSTGRES_ENV_FILE" POSTGRES_PASSWORD)"
  [[ "$domain" =~ ^[A-Za-z0-9.-]+$ ]] || die "SITE_DOMAIN is invalid"
  [[ "$server_ip" =~ ^[0-9a-fA-F:.]+$ ]] || die "SERVER_IP is invalid"
  [[ "$auth_mode" == "demo" || "$auth_mode" == "sms" ]] \
    || die "CUSTOMER_AUTH_MODE must be demo or sms"
  if [[ "$auth_mode" == "demo" ]]; then
    [[ -z "$(file_value "$APP_ENV_FILE" P1SMS_API_KEY)" ]] \
      || die "P1SMS_API_KEY must be empty in demo mode"
  else
    [[ "$sms_provider" == "smsc" || "$sms_provider" == "p1sms" ]] \
      || die "SMS_PROVIDER must be smsc or p1sms in sms mode"
    if [[ "$sms_provider" == "smsc" ]]; then
      require_non_placeholder SMSC_SENDER 2
      if [[ -z "$(file_value "$APP_ENV_FILE" SMSC_API_KEY)" ]]; then
        require_non_placeholder SMSC_LOGIN 2
        require_non_placeholder SMSC_PASSWORD 8
      fi
    else
      require_non_placeholder P1SMS_API_KEY 8
    fi
  fi
  [[ "$database_url" == postgresql://inkar:*@postgres:5432/inkar_cms ]] \
    || die "DATABASE_URL must point to the private compose Postgres service"
  [[ ${#postgres_password} -ge 32 ]] || die "POSTGRES_PASSWORD must be at least 32 characters"
  [[ "$medusa_enabled" == "true" || "$medusa_enabled" == "false" ]] \
    || die "MEDUSA_ENABLED must be true or false"
  if [[ "$medusa_enabled" == "true" ]]; then
    [[ "$medusa_url" =~ ^https:// ]] || {
      [[ "$medusa_url" == "http://78.140.246.238:9000" \
        && "$(file_value "$APP_ENV_FILE" MEDUSA_ALLOW_INSECURE_LEGACY_HTTP)" == "true" ]] \
        || die "MEDUSA_URL must use HTTPS (legacy HTTP requires its exact compatibility opt-in)"
    }
    require_non_placeholder MEDUSA_PUBLISHABLE_KEY 12
    require_non_placeholder MEDUSA_SALES_CHANNEL 8
    require_non_placeholder MEDUSA_REGION 8
  fi
  [[ "$offer_source" == "clickhouse" ]] \
    || die "CATALOG_OFFER_SOURCE must be clickhouse for the Inkar-server release"
  [[ "$clickhouse_url" =~ ^https:// ]] || {
    [[ "$clickhouse_url" == "http://host.docker.internal:18123" \
      && "$(file_value "$APP_ENV_FILE" CLICKHOUSE_ALLOW_INSECURE_PRIVATE_HTTP)" == "true" ]] \
      || die "CLICKHOUSE_URL must use HTTPS or the exact private SSH-tunnel endpoint"
  }
  if [[ "$clickhouse_url" == "http://host.docker.internal:18123" ]]; then
    [[ -f "$CLICKHOUSE_TUNNEL_ENV_FILE" && ! -L "$CLICKHOUSE_TUNNEL_ENV_FILE" ]] \
      || die "missing regular ${CLICKHOUSE_TUNNEL_ENV_FILE}"
    tunnel_remote="$(file_value "$CLICKHOUSE_TUNNEL_ENV_FILE" REMOTE_CLICKHOUSE_HOST)"
    tunnel_key="$(file_value "$CLICKHOUSE_TUNNEL_ENV_FILE" SSH_KEY_PATH)"
    tunnel_known_hosts="$(file_value "$CLICKHOUSE_TUNNEL_ENV_FILE" SSH_KNOWN_HOSTS_PATH)"
    [[ -n "$tunnel_remote" && "$tunnel_remote" != replace_* ]] \
      || die "REMOTE_CLICKHOUSE_HOST is not configured in ${CLICKHOUSE_TUNNEL_ENV_FILE}"
    [[ -f "$tunnel_key" && ! -L "$tunnel_key" ]] \
      || die "SSH_KEY_PATH must point to a regular file"
    [[ -f "$tunnel_known_hosts" && ! -L "$tunnel_known_hosts" ]] \
      || die "SSH_KNOWN_HOSTS_PATH must point to a verified regular file"
  fi
  require_non_placeholder CLICKHOUSE_USER 1
  require_non_placeholder CLICKHOUSE_PASSWORD 1
  require_non_placeholder ADMIN_TOKEN 32
  require_non_placeholder CUSTOMER_AUTH_SECRET 32
  require_non_placeholder CHECKOUT_QUOTE_SECRET 32
  require_non_placeholder CDP_HASH_SECRET 32
  epharm_sync_enabled="$(file_value "$APP_ENV_FILE" EPHARM_ORDER_SYNC_ENABLED)"
  [[ "$epharm_sync_enabled" == "true" || "$epharm_sync_enabled" == "false" ]] \
    || die "EPHARM_ORDER_SYNC_ENABLED must be true or false"
  if [[ "$epharm_sync_enabled" == "true" ]]; then
    require_non_placeholder EPHARM_FULFILLMENT_SHARED_SECRET 32
    epharm_base_url="$(file_value "$APP_ENV_FILE" EPHARM_BASE_URL)"
    [[ "$epharm_base_url" =~ ^https://[A-Za-z0-9.-]+(:[0-9]+)?$ ]] \
      || die "EPHARM_BASE_URL must be an HTTPS origin without a path"
    epharm_start_at="$(file_value "$APP_ENV_FILE" EPHARM_ORDER_START_AT)"
    [[ "$epharm_start_at" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.([0-9]+))?Z$ ]] \
      || die "EPHARM_ORDER_START_AT must be an explicit UTC ISO-8601 timestamp"
  fi
  mode="$(stat -c '%a' "$APP_ENV_FILE" 2>/dev/null || true)"
  [[ -z "$mode" || "$mode" =~ ^[46]00$ ]] \
    || note "warning: tighten ${APP_ENV_FILE} permissions to 600 (current ${mode})"
}

select_caddyfile() {
  case "$(state_value TLS_MODE)" in
    http) CADDYFILE_PATH="${SCRIPT_DIR}/Caddyfile.http" ;;
    tls) CADDYFILE_PATH="${SCRIPT_DIR}/Caddyfile.tls" ;;
    *) die "TLS_MODE must be http or tls" ;;
  esac
  export CADDYFILE_PATH
}

compose() {
  require_state
  select_caddyfile
  export APP_ENV_FILE POSTGRES_ENV_FILE DATA_ROOT BACKUP_ROOT IMPORT_ROOT MANIFEST_ROOT
  docker compose --project-name "$PROJECT_NAME" --env-file "$STATE_FILE" --file "$COMPOSE_FILE" "$@"
}

preflight() {
  need docker; need curl
  validate_environment
  compose config --quiet
  note "compose and environment preflight passed"
}

release_id() {
  local revision
  revision="$(git -C "$REPO_ROOT" rev-parse --short=12 HEAD 2>/dev/null || printf 'source')"
  printf '%s-%s' "$(date -u +%Y%m%dT%H%M%SZ)" "$revision"
}

build_image() {
  local image="$1" maps_key
  maps_key="$(file_value "$APP_ENV_FILE" NEXT_PUBLIC_YANDEX_MAPS_KEY)"
  DOCKER_BUILDKIT=1 docker build \
    --build-arg "NEXT_PUBLIC_YANDEX_MAPS_KEY=${maps_key}" \
    --label "kz.inkar.release=${image#inkar-shop:}" --tag "$image" "$REPO_ROOT"
}

ensure_vapid_keys() {
  local image="$1" public_key private_key
  if [[ "$(file_value "$APP_ENV_FILE" VAPID_PUBLIC_KEY)" != "__VAPID_PUBLIC_KEY__" \
    && "$(file_value "$APP_ENV_FILE" VAPID_PRIVATE_KEY)" != "__VAPID_PRIVATE_KEY__" ]]; then return; fi
  read -r public_key private_key < <(
    docker run --rm "$image" node -e \
      "const w=require('web-push');const k=w.generateVAPIDKeys();console.log(k.publicKey+' '+k.privateKey)"
  )
  [[ -n "$public_key" && -n "$private_key" ]] || die "failed to generate VAPID keys"
  replace_token "$APP_ENV_FILE" "__VAPID_PUBLIC_KEY__" "$public_key"
  replace_token "$APP_ENV_FILE" "__VAPID_PRIVATE_KEY__" "$private_key"
  note "generated and persisted VAPID keys without printing them"
}

wait_healthy() {
  local service="$1" attempts="${2:-40}" container status
  container="$(compose ps -q "$service")"
  [[ -n "$container" ]] || die "${service} container is not running"
  while (( attempts > 0 )); do
    status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container")"
    case "$status" in healthy|running) return 0 ;; unhealthy|exited|dead) die "${service} became ${status}" ;; esac
    sleep 3; attempts=$((attempts - 1))
  done
  die "${service} did not become healthy in time"
}

start_epharm_order_worker() {
  compose up -d --no-deps --force-recreate epharm-order-worker
  wait_healthy epharm-order-worker 40
}

image_has_epharm_order_worker() {
  docker run --rm --entrypoint /bin/sh "$1" -c 'test -f /app/scripts/sync-epharm-orders.mjs' \
    >/dev/null 2>&1
}

restore_epharm_order_worker() {
  local image="$1"
  set_state DEPLOY_IMAGE "$image"
  if image_has_epharm_order_worker "$image"; then
    start_epharm_order_worker
  else
    compose stop --timeout 30 epharm-order-worker >/dev/null 2>&1 || true
    note "restored image has no ePharm order worker; synchronization remains stopped"
  fi
}

run_operation() { compose --profile operations run --rm --no-deps operation "$@"; }
prepare_data_permissions() { compose --profile operations run --rm --no-deps permissions; }
migrate() { run_operation node scripts/db-migrate.mjs; }
deep_check() {
  compose exec -T "$1" node -e \
    "fetch('http://127.0.0.1:3000/api/health?deep=1').then(async r=>{const b=await r.text();if(!r.ok){console.error(b);process.exit(1)}}).catch(e=>{console.error(e);process.exit(1)})"
}
database_count() {
  [[ "$1" =~ ^[a-z_]+$ ]] || die "invalid table name"
  compose exec -T postgres psql -U inkar -d inkar_cms -Atqc "SELECT count(*) FROM $1;"
}
backup() { "${SCRIPT_DIR}/backup.sh"; }
sync_catalog() {
  if [[ "$(file_value "$APP_ENV_FILE" MEDUSA_ENABLED)" == "false" ]]; then
    note "Medusa is disabled; preserving the authoritative local PostgreSQL catalogue"
    return 0
  fi
  run_operation node scripts/sync-medusa-catalog.mjs --apply
}
sync_catalog_full() {
  [[ "$(file_value "$APP_ENV_FILE" MEDUSA_ENABLED)" != "false" ]] \
    || die "full Medusa catalog backfill is disabled; restore an approved local PostgreSQL seed"
  [[ "${ALLOW_FULL_BACKFILL:-0}" == "1" ]] || die "full catalog backfill requires ALLOW_FULL_BACKFILL=1"
  run_operation node scripts/sync-medusa-catalog.mjs --apply --full --mark-missing-inactive
}
sync_offers() { run_operation node scripts/sync-medusa-pharmacy-offers.mjs --apply; }
sync_offers_for_release() {
  local status
  if sync_offers; then
    return 0
  else
    status=$?
  fi
  if [[ "$status" == "2" ]]; then
    note "offer sync left retryable products on last-known-good data; continuing the application release"
    return 0
  fi
  return "$status"
}
sync_offers_full() {
  [[ "${ALLOW_FULL_BACKFILL:-0}" == "1" ]] || die "full offer backfill requires ALLOW_FULL_BACKFILL=1"
  run_operation node scripts/sync-medusa-pharmacy-offers.mjs --apply --full \
    --concurrency 2 --timeout-ms 10000 --attempts 2 --batch-delay-ms 250
}
slot_service() { case "$1" in blue) printf web-blue ;; green) printf web-green ;; *) die "invalid slot: $1" ;; esac; }
other_slot() { case "$1" in blue) printf green ;; green) printf blue ;; *) die "invalid slot: $1" ;; esac; }
set_slot_image() { case "$1" in blue) set_state WEB_BLUE_IMAGE "$2" ;; green) set_state WEB_GREEN_IMAGE "$2" ;; *) die "invalid slot: $1" ;; esac; }

switch_router_candidate() {
  local upstream="$1"
  (
    export ACTIVE_WEB_UPSTREAM="$upstream"
    compose up -d --no-deps --force-recreate router || return 1
    wait_healthy router
  )
}

activate_slot() {
  local slot="$1" previous="${2:-}" service image
  local old_slot old_upstream old_image
  service="$(slot_service "$slot")"
  case "$slot" in blue) image="$(state_value WEB_BLUE_IMAGE)" ;; green) image="$(state_value WEB_GREEN_IMAGE)" ;; esac
  old_slot="$(state_value ACTIVE_SLOT)"
  old_upstream="$(state_value ACTIVE_WEB_UPSTREAM)"
  case "$old_slot" in
    blue) old_image="$(state_value WEB_BLUE_IMAGE)" ;;
    green) old_image="$(state_value WEB_GREEN_IMAGE)" ;;
    *) die "invalid active slot in state: ${old_slot}" ;;
  esac

  if ! switch_router_candidate "${service}:3000"; then
    note "candidate router failed; restoring ${old_upstream}"
    switch_router_candidate "$old_upstream" || die "candidate and router rollback both failed"
    commit_active_state "$old_slot" "$old_upstream" "$old_image" \
      || die "router rollback succeeded but old active state could not be restored"
    die "candidate router did not become healthy; active slot was not changed"
  fi
  if ! (compose up -d --no-deps edge && wait_healthy edge); then
    note "edge verification failed; restoring ${old_upstream}"
    switch_router_candidate "$old_upstream" || die "edge check and router rollback both failed"
    commit_active_state "$old_slot" "$old_upstream" "$old_image" \
      || die "router rollback succeeded but old active state could not be restored"
    die "edge did not become healthy; active slot was not changed"
  fi
  if ! commit_active_state "$slot" "${service}:3000" "$image"; then
    note "active-state commit failed; restoring ${old_upstream}"
    switch_router_candidate "$old_upstream" || die "state commit and router rollback both failed"
    commit_active_state "$old_slot" "$old_upstream" "$old_image" \
      || die "router rollback succeeded but old active state could not be restored"
    die "candidate state could not be committed"
  fi
  [[ -z "$previous" || "$previous" == "$slot" ]] || compose stop --timeout 30 "$(slot_service "$previous")"
}

bootstrap() {
  preflight
  local image="inkar-shop:$(release_id)" products offers
  note "building ${image}"; build_image "$image"; ensure_vapid_keys "$image"
  set_state DEPLOY_IMAGE "$image"; set_slot_image blue "$image"; set_slot_image green "$image"
  prepare_data_permissions
  compose up -d postgres; wait_healthy postgres 60; migrate
  products="$(database_count catalog_products)"
  if [[ "$products" == 0 ]]; then
    [[ "$(file_value "$APP_ENV_FILE" MEDUSA_ENABLED)" != "false" ]] \
      || die "empty local catalogue: bootstrap from an approved PostgreSQL seed before starting the site"
    note "empty catalogue: starting explicit initial full backfill"; ALLOW_FULL_BACKFILL=1 sync_catalog_full
  else note "existing catalogue has ${products} products: incremental sync only"; sync_catalog; fi
  offers="$(database_count catalog_pharmacy_offers)"
  if [[ "$offers" == 0 ]]; then
    note "empty offers table: starting resumable initial full backfill"; ALLOW_FULL_BACKFILL=1 sync_offers_full
  else note "existing offers table has ${offers} rows: bounded sync only"; sync_offers; fi
  compose up -d --no-deps web-blue; wait_healthy web-blue; deep_check web-blue
  set_state ACTIVE_SLOT blue; set_state ACTIVE_WEB_UPSTREAM web-blue:3000
  compose up -d --no-deps router; wait_healthy router
  compose up -d --no-deps edge; wait_healthy edge
  start_epharm_order_worker
  note "bootstrap completed in HTTP mode; enable TLS only after DNS and ACME reachability are verified"
}

bootstrap_seed() {
  preflight
  [[ "${ALLOW_INITIAL_SEED:-0}" == "1" ]] \
    || die "initial seed restore requires ALLOW_INITIAL_SEED=1"
  local seed="${INITIAL_SEED_DUMP:-}" seed_real backup_real existing_tables
  local image="inkar-shop:$(release_id)" products offers
  [[ -n "$seed" ]] || die "INITIAL_SEED_DUMP is required"
  [[ -f "$seed" && ! -L "$seed" ]] || die "initial seed must be a regular, non-symlink file"
  seed_real="$(realpath -e -- "$seed")"
  backup_real="$(realpath -e -- "$BACKUP_ROOT")"
  [[ "$seed_real" == "$backup_real"/inkar-postgres-*.dump ]] \
    || die "initial seed must be named inkar-postgres-*.dump inside ${BACKUP_ROOT}"

  note "building ${image}"; build_image "$image"; ensure_vapid_keys "$image"
  set_state DEPLOY_IMAGE "$image"; set_slot_image blue "$image"; set_slot_image green "$image"
  prepare_data_permissions
  compose up -d postgres; wait_healthy postgres 60
  existing_tables="$(
    compose exec -T postgres psql -U inkar -d inkar_cms -Atqc \
      "SELECT count(*) FROM pg_tables WHERE schemaname = 'public';"
  )"
  [[ "$existing_tables" == "0" ]] \
    || die "initial seed refused: public schema already has ${existing_tables} tables"
  note "restoring guarded initial seed from ${seed_real}"
  compose exec -T postgres pg_restore --clean --if-exists --no-owner --no-privileges \
    -U inkar -d inkar_cms <"$seed_real"
  migrate
  products="$(database_count catalog_products)"
  [[ "$products" =~ ^[0-9]+$ && "$products" -gt 0 ]] \
    || die "initial seed produced an empty catalogue"
  note "seeded catalogue has ${products} products: running bounded incremental sync"
  sync_catalog
  offers="$(database_count catalog_pharmacy_offers)"
  if [[ "$offers" == "0" ]]; then
    note "seed has no offers: starting resumable initial full backfill"; ALLOW_FULL_BACKFILL=1 sync_offers_full
  else
    note "seeded offers table has ${offers} rows: running bounded sync"; sync_offers
  fi
  compose up -d --no-deps web-blue; wait_healthy web-blue; deep_check web-blue
  set_state ACTIVE_SLOT blue; set_state ACTIVE_WEB_UPSTREAM web-blue:3000
  compose up -d --no-deps router; wait_healthy router
  compose up -d --no-deps edge; wait_healthy edge
  start_epharm_order_worker
  note "seed bootstrap completed in HTTP mode; enable TLS only after DNS and ACME reachability are verified"
}

release() {
  preflight
  local active inactive service image old_upstream old_active_image old_inactive_image
  active="$(state_value ACTIVE_SLOT)"; inactive="$(other_slot "$active")"; service="$(slot_service "$inactive")"
  old_upstream="$(state_value ACTIVE_WEB_UPSTREAM)"
  case "$active" in blue) old_active_image="$(state_value WEB_BLUE_IMAGE)" ;; green) old_active_image="$(state_value WEB_GREEN_IMAGE)" ;; esac
  case "$inactive" in blue) old_inactive_image="$(state_value WEB_BLUE_IMAGE)" ;; green) old_inactive_image="$(state_value WEB_GREEN_IMAGE)" ;; esac
  image="inkar-shop:$(release_id)"
  note "creating pre-release database backup"; backup
  note "building ${image} for inactive ${inactive}"; build_image "$image"; ensure_vapid_keys "$image"
  set_state DEPLOY_IMAGE "$image"; set_slot_image "$inactive" "$image"
  if ! (
    prepare_data_permissions &&
    compose up -d postgres &&
    wait_healthy postgres 60 &&
    migrate &&
    sync_catalog &&
    sync_offers_for_release &&
    compose up -d --no-deps "$service" &&
    wait_healthy "$service" &&
    deep_check "$service" &&
    start_epharm_order_worker &&
    activate_slot "$inactive" "$active"
  ); then
    note "candidate release failed; restoring previous router and release state"
    switch_router_candidate "$old_upstream" \
      || die "candidate release failed and previous router could not be restored"
    set_slot_image "$inactive" "$old_inactive_image"
    commit_active_state "$active" "$old_upstream" "$old_active_image" \
      || die "previous router is active but release state could not be restored"
    compose stop --timeout 30 "$service" >/dev/null 2>&1 || true
    restore_epharm_order_worker "$old_active_image"
    die "candidate release failed; previous slot remains active"
  fi
  note "release ${image} is active in ${inactive}; ${active} is stopped but retained for rollback"
}

rollback() {
  preflight
  local active target service image
  active="$(state_value ACTIVE_SLOT)"; target="$(other_slot "$active")"; service="$(slot_service "$target")"
  case "$target" in blue) image="$(state_value WEB_BLUE_IMAGE)" ;; green) image="$(state_value WEB_GREEN_IMAGE)" ;; esac
  [[ "$image" != inkar-shop:bootstrap ]] || die "no previous image is recorded for ${target}"
  compose up -d --no-deps "$service"; wait_healthy "$service"; deep_check "$service"
  activate_slot "$target" "$active"
  restore_epharm_order_worker "$image"
  note "traffic and compatible order synchronization rolled back to ${target}; database migrations were not reversed"
}

enable_tls() {
  preflight
  local domain attempt ready=0
  domain="$(state_value SITE_DOMAIN)"; set_state TLS_MODE tls
  if compose up -d --no-deps --force-recreate edge; then
    for attempt in $(seq 1 12); do
      if curl --fail --silent --show-error --max-time 10 "https://${domain}/api/health" >/dev/null; then ready=1; break; fi
      sleep 10
    done
  fi
  if [[ "$ready" != 1 ]]; then
    note "TLS validation failed; restoring HTTP edge"; set_state TLS_MODE http
    compose up -d --no-deps --force-recreate edge
    die "TLS was not activated; verify DNS A/AAAA, NAT/firewall 80+443 and outbound ACME"
  fi
  note "TLS is active for ${domain}"
}

images_dry_run() {
  require_state
  docker run --rm --user "$(id -u):$(id -g)" --env-file "$IMAGE_IMPORT_ENV_FILE" \
    --volume "${IMPORT_ROOT}/sku-images:/imports/sku-images:ro" \
    --volume "${MANIFEST_ROOT}:/manifests" "$(state_value DEPLOY_IMAGE)" \
    node scripts/import-sku-images.mjs --images-dir /imports/sku-images \
    --manifest /manifests/sku-images-dry-run.jsonl
}
images_canary() {
  [[ "${ALLOW_IMAGE_WRITE:-0}" == 1 ]] || die "image writes require ALLOW_IMAGE_WRITE=1 after reviewing dry-run"
  docker run --rm --user "$(id -u):$(id -g)" --env-file "$IMAGE_IMPORT_ENV_FILE" \
    --volume "${IMPORT_ROOT}/sku-images:/imports/sku-images:ro" \
    --volume "${MANIFEST_ROOT}:/manifests" "$(state_value DEPLOY_IMAGE)" \
    node scripts/import-sku-images.mjs --images-dir /imports/sku-images --apply --limit 5 \
    --manifest /manifests/sku-images-canary.jsonl
}
images_apply() {
  [[ "${ALLOW_IMAGE_WRITE:-0}" == 1 ]] \
    || die "image writes require ALLOW_IMAGE_WRITE=1 after reviewing dry-run"
  [[ "${ALLOW_FULL_IMAGE_IMPORT:-0}" == 1 ]] \
    || die "full image import requires ALLOW_FULL_IMAGE_IMPORT=1 after a successful canary"
  docker run --rm --user "$(id -u):$(id -g)" --env-file "$IMAGE_IMPORT_ENV_FILE" \
    --volume "${IMPORT_ROOT}/sku-images:/imports/sku-images:ro" \
    --volume "${MANIFEST_ROOT}:/manifests" "$(state_value DEPLOY_IMAGE)" \
    node scripts/import-sku-images.mjs --images-dir /imports/sku-images --apply \
    --manifest "/manifests/sku-images-apply-$(date -u +%Y%m%dT%H%M%SZ).jsonl"
}

usage() {
  cat <<'EOF'
Usage: deploy.sh COMMAND
  prepare | preflight | bootstrap | bootstrap-seed | release | rollback | enable-tls | backup
  sync-catalog | sync-catalog-full | sync-offers | sync-offers-full
  images-dry-run | images-canary | images-apply
EOF
}

if [[ "${INKAR_DEPLOY_LIBRARY_ONLY:-0}" == "1" ]]; then
  return 0 2>/dev/null || exit 0
fi

command="${1:-}"
case "$command" in
  prepare) prepare ;; preflight) preflight ;;
  bootstrap) acquire_operation_lock; bootstrap ;;
  bootstrap-seed) acquire_operation_lock; bootstrap_seed ;;
  release) acquire_operation_lock; release ;;
  rollback) acquire_operation_lock; rollback ;; enable-tls) acquire_operation_lock; enable_tls ;; backup) backup ;;
  sync-catalog) acquire_operation_lock; preflight; sync_catalog ;;
  sync-catalog-full) acquire_operation_lock; preflight; sync_catalog_full ;;
  sync-offers) acquire_operation_lock; preflight; sync_offers ;;
  sync-offers-full) acquire_operation_lock; preflight; sync_offers_full ;;
  images-dry-run) images_dry_run ;; images-canary) acquire_operation_lock; images_canary ;;
  images-apply) acquire_operation_lock; images_apply ;;
  -h|--help|help) usage ;; *) usage; [[ -z "$command" ]] || die "unknown command: $command" ;;
esac
