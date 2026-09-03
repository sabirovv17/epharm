#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
STATE_ROOT="${INKAR_STATE_ROOT:-/srv/inkar-shop}"
STATE_FILE="${STATE_ROOT}/state/release.env"
OPERATION_LOCK_FILE="${STATE_ROOT}/state/operation.lock"
APP_ENV_FILE="${STATE_ROOT}/config/app.env"
POSTGRES_ENV_FILE="${STATE_ROOT}/config/postgres.env"
DATA_ROOT="${STATE_ROOT}/data"
BACKUP_ROOT="${STATE_ROOT}/backups"
COMPOSE_FILE="${SCRIPT_DIR}/compose.yml"

if [[ "${1:-}" != --yes || -z "${2:-}" ]]; then
  printf 'Usage: restore.sh --yes /srv/inkar-shop/backups/inkar-postgres-....dump\n' >&2
  exit 1
fi
backup="$(realpath -- "$2")"
backup_root_real="$(realpath -- "$BACKUP_ROOT")"
[[ -f "$backup" && ! -L "$backup" ]] || { printf 'backup must be a regular file\n' >&2; exit 1; }
case "$backup" in
  "${backup_root_real}"/inkar-postgres-*.dump) ;;
  *) printf 'backup is outside the approved directory\n' >&2; exit 1 ;;
esac
case "$(awk -F= '$1=="TLS_MODE"{print $2; exit}' "$STATE_FILE")" in
  http) CADDYFILE_PATH="${SCRIPT_DIR}/Caddyfile.http" ;;
  tls) CADDYFILE_PATH="${SCRIPT_DIR}/Caddyfile.tls" ;;
  *) printf 'invalid TLS_MODE\n' >&2; exit 1 ;;
esac
export APP_ENV_FILE POSTGRES_ENV_FILE DATA_ROOT BACKUP_ROOT CADDYFILE_PATH

compose() {
  docker compose --project-name inkar-shop --env-file "$STATE_FILE" --file "$COMPOSE_FILE" "$@"
}

operation_lock_is_inherited() {
  [[ "${INKAR_OPERATION_LOCK_FD:-}" == "9" && -e /proc/$$/fd/9 ]] || return 1
  [[ "$(readlink -f /proc/$$/fd/9 2>/dev/null || true)" == "$(readlink -f "$OPERATION_LOCK_FILE" 2>/dev/null || true)" ]]
}

acquire_operation_lock() {
  operation_lock_is_inherited && return 0
  command -v flock >/dev/null 2>&1 || { printf 'flock is required\n' >&2; return 1; }
  local lock_directory desired_group current_group current_mode
  lock_directory="$(dirname -- "$OPERATION_LOCK_FILE")"
  mkdir -p "$lock_directory"
  if [[ ! -e "$OPERATION_LOCK_FILE" ]]; then (umask 0007; : >"$OPERATION_LOCK_FILE"); fi
  desired_group="$(stat -c '%g' "$lock_directory")"
  current_group="$(stat -c '%g' "$OPERATION_LOCK_FILE")"
  if [[ "$current_group" != "$desired_group" ]]; then chgrp "$desired_group" "$OPERATION_LOCK_FILE"; fi
  current_mode="$(stat -c '%a' "$OPERATION_LOCK_FILE")"
  if [[ "$current_mode" != "660" ]]; then chmod 0660 "$OPERATION_LOCK_FILE"; fi
  exec 9>>"$OPERATION_LOCK_FILE"
  flock -n 9 || {
    printf 'another deploy/sync/backup/restore operation owns %s\n' "$OPERATION_LOCK_FILE" >&2
    return 1
  }
  export INKAR_OPERATION_LOCK_FD=9
}

TIMER_UNITS=(
  inkar-shop-catalog-sync.timer
  inkar-shop-offer-sync.timer
  inkar-shop-backup.timer
)
SERVICE_UNITS=(
  inkar-shop-catalog-sync.service
  inkar-shop-offer-sync.service
  inkar-shop-backup.service
)
INSTALLED_TIMERS=()
INSTALLED_SERVICES=()
PREVIOUSLY_ACTIVE_TIMERS=()
SCHEDULERS_TOUCHED=0
TIMERS_RESUMED=0

systemd_unit_exists() {
  [[ "$(systemctl show --property LoadState --value "$1" 2>/dev/null || true)" == "loaded" ]]
}

quiesce_schedulers() {
  command -v systemctl >/dev/null 2>&1 || {
    printf 'systemctl is required to quiesce catalog/offer/backup jobs\n' >&2
    return 1
  }
  local unit
  for unit in "${TIMER_UNITS[@]}"; do
    if systemd_unit_exists "$unit"; then
      INSTALLED_TIMERS+=("$unit")
      if systemctl is-active --quiet "$unit"; then PREVIOUSLY_ACTIVE_TIMERS+=("$unit"); fi
    fi
  done
  for unit in "${SERVICE_UNITS[@]}"; do
    if systemd_unit_exists "$unit"; then INSTALLED_SERVICES+=("$unit"); fi
  done
  if (( ${#INSTALLED_TIMERS[@]} == 0 && ${#INSTALLED_SERVICES[@]} == 0 )); then return 0; fi
  SCHEDULERS_TOUCHED=1
  if (( ${#INSTALLED_TIMERS[@]} > 0 )); then
    systemctl stop "${INSTALLED_TIMERS[@]}" || {
      printf 'cannot stop Inkar timers; rerun restore with sufficient systemctl privileges\n' >&2
      return 1
    }
  fi
  if (( ${#INSTALLED_SERVICES[@]} > 0 )); then
    systemctl stop "${INSTALLED_SERVICES[@]}" || {
      printf 'cannot stop Inkar jobs; keep timers stopped and rerun with sufficient privileges\n' >&2
      return 1
    }
  fi
  for unit in "${INSTALLED_SERVICES[@]}"; do
    if systemctl is-active --quiet "$unit"; then
      printf 'job is still active after stop: %s\n' "$unit" >&2
      return 1
    fi
  done
}

resume_previously_active_timers() {
  (( ${#PREVIOUSLY_ACTIVE_TIMERS[@]} > 0 )) || return 0
  TIMERS_RESUMED=1
  systemctl start "${PREVIOUSLY_ACTIVE_TIMERS[@]}"
}

wait_healthy() {
  local service="$1" attempts="${2:-40}" container status
  container="$(compose ps -q "$service")"
  [[ -n "$container" ]] || { printf '%s container is not running\n' "$service" >&2; return 1; }
  while (( attempts > 0 )); do
    status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container")"
    case "$status" in healthy|running) return 0 ;; unhealthy|exited|dead) return 1 ;; esac
    sleep 3
    attempts=$((attempts - 1))
  done
  return 1
}

deep_check() {
  compose exec -T "$1" node -e \
    "fetch('http://127.0.0.1:3000/api/health?deep=1').then(async r=>{const b=await r.text();if(!r.ok){console.error(b);process.exit(1)}}).catch(e=>{console.error(e);process.exit(1)})"
}

traffic_stopped=0
restore_failed() {
  trap - ERR
  if [[ "$traffic_stopped" == 1 ]]; then
    compose stop --timeout 30 edge router web-blue web-green >/dev/null 2>&1 || true
  fi
  if [[ "$TIMERS_RESUMED" == 1 && ${#PREVIOUSLY_ACTIVE_TIMERS[@]} -gt 0 ]]; then
    systemctl stop "${PREVIOUSLY_ACTIVE_TIMERS[@]}" >/dev/null 2>&1 || true
  fi
  if [[ "$SCHEDULERS_TOUCHED" == 1 ]]; then
    printf 'Restore failed; Inkar timers remain stopped for operator review.\n' >&2
  fi
  if [[ "$traffic_stopped" == 1 ]]; then
    printf 'Restore validation failed; web traffic remains stopped.\n' >&2
  fi
}
trap restore_failed ERR

acquire_operation_lock
quiesce_schedulers
printf 'Stopping traffic before destructive database restore...\n'
traffic_stopped=1
compose stop --timeout 30 edge router web-blue web-green
compose up -d postgres
wait_healthy postgres 60
compose exec -T postgres pg_restore --clean --if-exists --no-owner --no-privileges \
  --username inkar --dbname inkar_cms <"$backup"
compose --profile operations run --rm --no-deps operation node scripts/db-migrate.mjs
active="$(awk -F= '$1=="ACTIVE_SLOT"{print $2; exit}' "$STATE_FILE")"
case "$active" in
  blue) service=web-blue ;;
  green) service=web-green ;;
  *) printf 'invalid ACTIVE_SLOT\n' >&2; false ;;
esac
compose up -d --no-deps "$service"
wait_healthy "$service"
deep_check "$service"
compose up -d --no-deps router
wait_healthy router
compose up -d --no-deps edge
wait_healthy edge
resume_previously_active_timers
traffic_stopped=0
printf 'Restore completed after readiness checks; only previously active timers were restarted.\n'