#!/usr/bin/env bash
# Consistent object-level MinIO backup (all configured buckets), retention and metrics.
set -euo pipefail
umask 077

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=tools/ops/lib.sh
source "$SCRIPT_DIR/ops/lib.sh"

ROOT_DIR="$(ops_root_dir)"
ENV_FILE="${EPHARM_ENV_FILE:-$ROOT_DIR/.env.prod}"
load_env_file "$ENV_FILE"

require_command docker
require_command tar
require_nonempty MINIO_ROOT_USER
require_nonempty MINIO_ROOT_PASSWORD

MINIO_CONTAINER="${MINIO_CONTAINER:-epharm-minio}"
MINIO_MC_IMAGE="${MINIO_MC_IMAGE:-minio/mc:RELEASE.2025-08-13T08-35-41Z}"
MINIO_BACKUP_BUCKETS="${MINIO_BACKUP_BUCKETS:-epharm-receipts}"
BACKUP_ROOT="${BACKUP_ROOT:-$ROOT_DIR/backups}"
RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-30}"
METRIC_DIR="${BACKUP_METRIC_DIR:-$BACKUP_ROOT/metrics}"
MINIO_DIR="$BACKUP_ROOT/minio"
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
STAGE="$MINIO_DIR/.stage-$TIMESTAMP-$$"
FINAL="$MINIO_DIR/epharm-minio-$TIMESTAMP.tar.gz"
TMP="$FINAL.partial"

mkdir -p "$MINIO_DIR" "$STAGE/data"
acquire_lock "$BACKUP_ROOT/.minio-backup.lock"
trap 'rm -rf "$STAGE"; rm -f "$TMP"; release_lock' EXIT INT TERM

started="$(date +%s)"
echo "[$(date -u +%FT%TZ)] MinIO backup started (buckets=$MINIO_BACKUP_BUCKETS)"

# `--network container:` reaches MinIO without publishing its API. Credentials live
# only in the short-lived mc container environment and are not written to the backup.
docker run --rm \
  --network "container:$MINIO_CONTAINER" \
  -e MC_USER="$MINIO_ROOT_USER" \
  -e MC_PASSWORD="$MINIO_ROOT_PASSWORD" \
  -e MC_BUCKETS="$MINIO_BACKUP_BUCKETS" \
  -v "$STAGE/data:/backup" \
  --entrypoint /bin/sh \
  "$MINIO_MC_IMAGE" -eu -c '
    mc alias set source http://127.0.0.1:9000 "$MC_USER" "$MC_PASSWORD" >/dev/null
    old_ifs=$IFS
    IFS=,
    for bucket in $MC_BUCKETS; do
      bucket=$(printf "%s" "$bucket" | tr -d " ")
      [ -n "$bucket" ] || continue
      mc stat "source/$bucket" >/dev/null
      mkdir -p "/backup/$bucket"
      mc mirror --overwrite --preserve "source/$bucket" "/backup/$bucket"
    done
    IFS=$old_ifs
  '

printf 'created_at=%s\nbuckets=%s\n' "$(date -u +%FT%TZ)" "$MINIO_BACKUP_BUCKETS" > "$STAGE/manifest.txt"
tar -C "$STAGE" -czf "$TMP" manifest.txt data
tar -tzf "$TMP" >/dev/null
[[ -s "$TMP" ]] || { echo "ERROR: MinIO backup is empty" >&2; exit 1; }
mv "$TMP" "$FINAL"
checksum="$(sha256_file "$FINAL")"
printf '%s  %s\n' "$checksum" "$(basename "$FINAL")" > "$FINAL.sha256"
chmod 600 "$FINAL" "$FINAL.sha256"

find "$MINIO_DIR" -type f \( -name 'epharm-minio-*.tar.gz' -o -name 'epharm-minio-*.tar.gz.sha256' \) \
  -mtime "+$RETENTION_DAYS" -delete

finished="$(date +%s)"
size="$(wc -c < "$FINAL" | tr -d ' ')"
write_metric "$METRIC_DIR" "epharm_minio_backup.prom" \
  '# HELP epharm_backup_last_success_timestamp_seconds Unix timestamp of the last successful backup.' \
  '# TYPE epharm_backup_last_success_timestamp_seconds gauge' \
  "epharm_backup_last_success_timestamp_seconds{component=\"minio\"} $finished" \
  '# HELP epharm_backup_size_bytes Size of the last successful backup.' \
  '# TYPE epharm_backup_size_bytes gauge' \
  "epharm_backup_size_bytes{component=\"minio\"} $size" \
  '# HELP epharm_backup_duration_seconds Duration of the last successful backup.' \
  '# TYPE epharm_backup_duration_seconds gauge' \
  "epharm_backup_duration_seconds{component=\"minio\"} $((finished - started))"

echo "[$(date -u +%FT%TZ)] MinIO backup OK: $FINAL ($size bytes, sha256=$checksum)"
