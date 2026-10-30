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
MINIO_MC_IMAGE="${MINIO_MC_IMAGE:-quay.io/minio/mc:RELEASE.2025-08-13T08-35-41Z}"
MINIO_BACKUP_BUCKETS="${MINIO_BACKUP_BUCKETS:-epharm-receipts,pim-product-images}"
IFS=, read -r -a bucket_names <<< "$MINIO_BACKUP_BUCKETS"
for bucket in "${bucket_names[@]}"; do
  bucket="${bucket//[[:space:]]/}"
  [[ "$bucket" =~ ^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$ ]] || {
    echo 'ERROR: invalid MinIO backup bucket name' >&2; exit 2;
  }
done
BACKUP_ROOT="${BACKUP_ROOT:-$ROOT_DIR/backups}"
METRIC_DIR="${BACKUP_METRIC_DIR:-$BACKUP_ROOT/metrics}"
MINIO_DIR="$BACKUP_ROOT/minio"
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
STAGE="$MINIO_DIR/.stage-$TIMESTAMP-$$"
FINAL="$MINIO_DIR/epharm-minio-$TIMESTAMP.tar.gz"
TMP="$FINAL.partial"

mkdir -p "$MINIO_DIR" "$STAGE/data" "$STAGE/policies"
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
  -v "$STAGE/policies:/policies" \
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
      # An empty JSON object is the exact source state for a private bucket.
      mc anonymous get-json "source/$bucket" > "/policies/$bucket.json"
    done
    IFS=$old_ifs
  '

printf 'created_at=%s\nbuckets=%s\nminio_policy_format=1\nminio_metadata_format=1\n' "$(date -u +%FT%TZ)" "$MINIO_BACKUP_BUCKETS" > "$STAGE/manifest.txt"
require_command python3
metadata_worker_args=()
if [[ -n "${MINIO_METADATA_PYTHON_IMAGE:-}" ]]; then
  metadata_worker_args=(--python-image "$MINIO_METADATA_PYTHON_IMAGE")
fi
MINIO_METADATA_ACCESS_KEY="$MINIO_ROOT_USER" MINIO_METADATA_SECRET_KEY="$MINIO_ROOT_PASSWORD" \
  python3 "$SCRIPT_DIR/ops/minio_metadata.py" capture --stage "$STAGE" --container "$MINIO_CONTAINER" "${metadata_worker_args[@]}"
python3 "$SCRIPT_DIR/ops/minio_policy.py" --stage "$STAGE" >/dev/null
tar -C "$STAGE" -czf "$TMP" manifest.txt data policies object-metadata.json
tar -tzf "$TMP" >/dev/null
python3 "$SCRIPT_DIR/ops/minio_policy.py" --archive "$TMP"
[[ -s "$TMP" ]] || { echo "ERROR: MinIO backup is empty" >&2; exit 1; }
mv "$TMP" "$FINAL"
checksum="$(sha256_file "$FINAL")"
printf '%s  %s\n' "$checksum" "$(basename "$FINAL")" > "$FINAL.sha256"
chmod 600 "$FINAL" "$FINAL.sha256"

# Pruning belongs to backup-all after the whole backup/offsite chain succeeds.

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
