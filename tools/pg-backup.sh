#!/usr/bin/env bash
# Atomic PostgreSQL logical backup with checksum, validation, retention and metrics.
set -euo pipefail
umask 077

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=tools/ops/lib.sh
source "$SCRIPT_DIR/ops/lib.sh"

ROOT_DIR="$(ops_root_dir)"
ENV_FILE="${EPHARM_ENV_FILE:-$ROOT_DIR/.env.prod}"
load_env_file "$ENV_FILE"

require_command docker
require_command gzip
require_nonempty POSTGRES_PASSWORD

POSTGRES_DB="${POSTGRES_DB:-epharm}"
POSTGRES_USER="${POSTGRES_USER:-epharm}"
POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-epharm-postgres}"
BACKUP_ROOT="${BACKUP_ROOT:-$ROOT_DIR/backups}"
RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-30}"
METRIC_DIR="${BACKUP_METRIC_DIR:-$BACKUP_ROOT/metrics}"
PG_DIR="$BACKUP_ROOT/postgres"
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
FINAL="$PG_DIR/epharm-postgres-$TIMESTAMP.dump.gz"
TMP="$FINAL.partial"

mkdir -p "$PG_DIR"
acquire_lock "$BACKUP_ROOT/.postgres-backup.lock"
trap 'rm -f "$TMP"; release_lock' EXIT INT TERM

started="$(date +%s)"
echo "[$(date -u +%FT%TZ)] PostgreSQL backup started"

# Custom format keeps schema metadata and lets restore-test use strict pg_restore.
docker exec -i "$POSTGRES_CONTAINER" \
  pg_dump --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    --format=custom --compress=0 --no-owner --no-privileges \
  | gzip -9 > "$TMP"

gzip -t "$TMP"
[[ -s "$TMP" ]] || { echo "ERROR: PostgreSQL backup is empty" >&2; exit 1; }
mv "$TMP" "$FINAL"
checksum="$(sha256_file "$FINAL")"
printf '%s  %s\n' "$checksum" "$(basename "$FINAL")" > "$FINAL.sha256"
chmod 600 "$FINAL" "$FINAL.sha256"

find "$PG_DIR" -type f \( -name 'epharm-postgres-*.dump.gz' -o -name 'epharm-postgres-*.dump.gz.sha256' \) \
  -mtime "+$RETENTION_DAYS" -delete

finished="$(date +%s)"
size="$(wc -c < "$FINAL" | tr -d ' ')"
write_metric "$METRIC_DIR" "epharm_postgres_backup.prom" \
  '# HELP epharm_backup_last_success_timestamp_seconds Unix timestamp of the last successful backup.' \
  '# TYPE epharm_backup_last_success_timestamp_seconds gauge' \
  "epharm_backup_last_success_timestamp_seconds{component=\"postgres\"} $finished" \
  '# HELP epharm_backup_size_bytes Size of the last successful backup.' \
  '# TYPE epharm_backup_size_bytes gauge' \
  "epharm_backup_size_bytes{component=\"postgres\"} $size" \
  '# HELP epharm_backup_duration_seconds Duration of the last successful backup.' \
  '# TYPE epharm_backup_duration_seconds gauge' \
  "epharm_backup_duration_seconds{component=\"postgres\"} $((finished - started))"

echo "[$(date -u +%FT%TZ)] PostgreSQL backup OK: $FINAL ($size bytes, sha256=$checksum)"
