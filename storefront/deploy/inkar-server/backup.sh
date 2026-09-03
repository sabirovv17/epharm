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
RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-14}"
operation_lock_is_inherited() {
  [[ "${INKAR_OPERATION_LOCK_FD:-}" == "9" && -e /proc/$$/fd/9 ]] || return 1
  [[ "$(readlink -f /proc/$$/fd/9 2>/dev/null || true)" == "$(readlink -f "$OPERATION_LOCK_FILE" 2>/dev/null || true)" ]]
}
acquire_operation_lock() {
  operation_lock_is_inherited && return 0
  command -v flock >/dev/null 2>&1 || { printf 'flock is required\n' >&2; exit 1; }
  local lock_directory desired_group current_group current_mode
  lock_directory="$(dirname -- "$OPERATION_LOCK_FILE")"; mkdir -p "$lock_directory"
  if [[ ! -e "$OPERATION_LOCK_FILE" ]]; then (umask 0007; : >"$OPERATION_LOCK_FILE"); fi
  desired_group="$(stat -c '%g' "$lock_directory")"; current_group="$(stat -c '%g' "$OPERATION_LOCK_FILE")"
  if [[ "$current_group" != "$desired_group" ]]; then chgrp "$desired_group" "$OPERATION_LOCK_FILE"; fi
  current_mode="$(stat -c '%a' "$OPERATION_LOCK_FILE")"
  if [[ "$current_mode" != "660" ]]; then chmod 0660 "$OPERATION_LOCK_FILE"; fi
  exec 9>>"$OPERATION_LOCK_FILE"
  flock -n 9 || { printf 'another deploy/sync/backup/restore operation owns %s\n' "$OPERATION_LOCK_FILE" >&2; exit 1; }
  export INKAR_OPERATION_LOCK_FD=9
}
[[ -f "$STATE_FILE" && -f "$APP_ENV_FILE" && -f "$POSTGRES_ENV_FILE" ]] || { printf 'deployment state is missing\n' >&2; exit 1; }
acquire_operation_lock
[[ "$RETENTION_DAYS" =~ ^[0-9]+$ ]] || { printf 'invalid retention\n' >&2; exit 1; }
case "$(awk -F= '$1=="TLS_MODE"{print $2; exit}' "$STATE_FILE")" in
  http) CADDYFILE_PATH="${SCRIPT_DIR}/Caddyfile.http" ;; tls) CADDYFILE_PATH="${SCRIPT_DIR}/Caddyfile.tls" ;;
  *) printf 'invalid TLS_MODE\n' >&2; exit 1 ;;
esac
export APP_ENV_FILE POSTGRES_ENV_FILE DATA_ROOT BACKUP_ROOT CADDYFILE_PATH
compose() { docker compose --project-name inkar-shop --env-file "$STATE_FILE" --file "$COMPOSE_FILE" "$@"; }
mkdir -p "$BACKUP_ROOT"
backup_root_real="$(realpath "$BACKUP_ROOT")"
target="${backup_root_real}/inkar-postgres-$(date -u +%Y%m%dT%H%M%SZ).dump"
temporary="${target}.partial"
trap 'rm -f -- "$temporary"' EXIT
compose exec -T postgres pg_dump --username inkar --dbname inkar_cms \
  --format custom --compress 6 --no-owner --no-privileges >"$temporary"
[[ -s "$temporary" ]] || { printf 'empty PostgreSQL backup\n' >&2; exit 1; }
mv -f -- "$temporary" "$target"; trap - EXIT; chmod 600 "$target"; printf '%s\n' "$target"
if (( RETENTION_DAYS > 0 )); then
  find "$backup_root_real" -maxdepth 1 -type f -name 'inkar-postgres-*.dump' -mtime "+${RETENTION_DAYS}" -delete
fi