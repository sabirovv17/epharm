#!/usr/bin/env bash
# Restores the newest PostgreSQL and MinIO backups into disposable containers.
# Production containers and volumes are never mounted or modified.
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
require_command tar
require_command python3
require_nonempty MINIO_ROOT_USER
require_nonempty MINIO_ROOT_PASSWORD

BACKUP_ROOT="${BACKUP_ROOT:-$ROOT_DIR/backups}"
lock_backup_suite "$BACKUP_ROOT" shared
METRIC_DIR="${BACKUP_METRIC_DIR:-$BACKUP_ROOT/metrics}"
PG_IMAGE="${POSTGRES_RESTORE_IMAGE:-postgres:16-alpine}"
MC_IMAGE="${MINIO_MC_IMAGE:-quay.io/minio/mc:RELEASE.2025-08-13T08-35-41Z}"
MINIO_IMAGE="${MINIO_RESTORE_IMAGE:-quay.io/minio/minio:RELEASE.2025-07-23T15-54-02Z}"
PG_BACKUP="${POSTGRES_BACKUP_FILE:-$(latest_matching_file "$BACKUP_ROOT/postgres" 'epharm-postgres-*.dump.gz')}"
MINIO_BACKUP="${MINIO_BACKUP_FILE:-$(latest_matching_file "$BACKUP_ROOT/minio" 'epharm-minio-*.tar.gz')}"
private_required="${BACKUP_PRIVATE_FILES_REQUIRED:-false}"
[[ "$private_required" == true || "$private_required" == false ]] || { echo 'ERROR: invalid private backup requirement' >&2; exit 2; }
if [[ "$private_required" == true ]]; then
  require_command python3
  NATIVE_BACKUP="${NATIVE_BACKUP_FILE:-$(latest_matching_file "$BACKUP_ROOT/native-files" 'epharm-native-files-*.tar.gz')}"
  KEY_BACKUP="${CREDENTIAL_KEY_BACKUP_FILE:-$(latest_matching_file "$BACKUP_ROOT/credential-keys" 'epharm-credential-keys-*.tar.gz')}"
  [[ -n "$NATIVE_BACKUP" && -n "$KEY_BACKUP" ]] || { echo 'ERROR: required private restore snapshots missing' >&2; exit 1; }
fi
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$$"
PG_CONTAINER="epharm-restore-pg-$RUN_ID"
MINIO_CONTAINER_TEST="epharm-restore-minio-$RUN_ID"
NETWORK="epharm-restore-$RUN_ID"

[[ -n "$PG_BACKUP" && -f "$PG_BACKUP" ]] || { echo "ERROR: no PostgreSQL backup found" >&2; exit 1; }
[[ -n "$MINIO_BACKUP" && -f "$MINIO_BACKUP" ]] || { echo "ERROR: no MinIO backup found" >&2; exit 1; }

STAGE="$(mktemp -d "$BACKUP_ROOT/.restore-$RUN_ID.XXXXXXXX")"
STAGE_IDENTITY="$(python3 - "$STAGE" <<'PY'
import os, pathlib, stat, sys
path = pathlib.Path(sys.argv[1]); info = path.lstat()
if (path.resolve(strict=True) != path or not stat.S_ISDIR(info.st_mode)
        or info.st_uid != os.geteuid() or stat.S_IMODE(info.st_mode) != 0o700):
    raise SystemExit('ERROR: restore stage is not a fresh physical private directory')
print(f'{info.st_dev}:{info.st_ino}')
PY
)"
cleanup() {
  docker_cleanup_container "$PG_CONTAINER"
  docker_cleanup_container "$MINIO_CONTAINER_TEST"
  docker network rm "$NETWORK" >/dev/null 2>&1 || true
  python3 - "$STAGE" "$STAGE_IDENTITY" <<'PY' || echo 'WARNING: private restore stage preserved; cleanup ownership not proven' >&2
import os, pathlib, shutil, stat, sys
path = pathlib.Path(sys.argv[1])
try:
    info = path.lstat()
except FileNotFoundError:
    raise SystemExit(0)
if (not stat.S_ISDIR(info.st_mode) or f'{info.st_dev}:{info.st_ino}' != sys.argv[2]
        or info.st_uid != os.geteuid() or stat.S_IMODE(info.st_mode) != 0o700
        or path.resolve(strict=True) != path or not shutil.rmtree.avoids_symlink_attacks):
    raise SystemExit(1)
shutil.rmtree(path)
PY
  release_lock
}
trap cleanup EXIT INT TERM
acquire_lock "$BACKUP_ROOT/.restore-test.lock"
# acquire_lock installs its own trap; the restore owns both stage and component lock.
trap cleanup EXIT INT TERM

started="$(date +%s)"
echo "[$(date -u +%FT%TZ)] Isolated restore-test started"

[[ -f "$PG_BACKUP.sha256" ]] || { echo "ERROR: missing checksum: $PG_BACKUP.sha256" >&2; exit 1; }
[[ -f "$MINIO_BACKUP.sha256" ]] || { echo "ERROR: missing checksum: $MINIO_BACKUP.sha256" >&2; exit 1; }
[[ "$(sha256_file "$PG_BACKUP")" == "$(awk '{print $1}' "$PG_BACKUP.sha256")" ]]
[[ "$(sha256_file "$MINIO_BACKUP")" == "$(awk '{print $1}' "$MINIO_BACKUP.sha256")" ]]
gzip -t "$PG_BACKUP"
tar -tzf "$MINIO_BACKUP" >/dev/null
policy_legacy_args=()
if [[ "$private_required" == false ]]; then policy_legacy_args=(--allow-legacy); fi
python3 "$SCRIPT_DIR/ops/minio_policy.py" --archive "$MINIO_BACKUP" "${policy_legacy_args[@]}"
if [[ "$private_required" == true ]]; then
  python3 "$SCRIPT_DIR/ops/private_backup.py" restore --kind native-files --archive "$NATIVE_BACKUP" --destination "$STAGE/native-files"
  python3 "$SCRIPT_DIR/ops/private_backup.py" restore --kind credential-keys --archive "$KEY_BACKUP" --destination "$STAGE/credential-keys"
fi

docker network create --internal "$NETWORK" >/dev/null
docker run -d --rm --name "$PG_CONTAINER" --network "$NETWORK" \
  -e POSTGRES_DB=epharm_restore -e POSTGRES_USER=epharm_restore -e POSTGRES_PASSWORD=restore-only \
  "$PG_IMAGE" >/dev/null

database_ready=false
for _ in $(seq 1 60); do
  # The image starts a Unix-socket-only temporary server during initialization.
  # Wait for the final TCP server, then verify that POSTGRES_DB really exists.
  if docker exec "$PG_CONTAINER" pg_isready -h 127.0.0.1 -U epharm_restore \
    -d epharm_restore >/dev/null 2>&1 && \
    docker exec "$PG_CONTAINER" psql -At -U epharm_restore -d epharm_restore \
      -c 'SELECT 1' >/dev/null 2>&1; then
    database_ready=true
    break
  fi
  sleep 1
done
[[ "$database_ready" == true ]] || {
  echo 'ERROR: isolated PostgreSQL database did not become ready' >&2
  exit 1
}
gzip -dc "$PG_BACKUP" | docker exec -i "$PG_CONTAINER" \
  pg_restore --username epharm_restore --dbname epharm_restore \
    --exit-on-error --no-owner --no-privileges
table_count="$(docker exec "$PG_CONTAINER" psql -At -U epharm_restore -d epharm_restore \
  -c "select count(*) from information_schema.tables where table_schema='public';")"
migration_count="$(docker exec "$PG_CONTAINER" psql -At -U epharm_restore -d epharm_restore \
  -c "select count(*) from flyway_schema_history where success = true;")"
[[ "$table_count" -gt 0 && "$migration_count" -gt 0 ]] || {
  echo "ERROR: restored database failed semantic checks" >&2
  exit 1
}

tar -C "$STAGE" -xzf "$MINIO_BACKUP"
python3 "$SCRIPT_DIR/ops/minio_policy.py" --stage "$STAGE" "${policy_legacy_args[@]}"
mkdir -p "$STAGE/policies" "$STAGE/restored-policies"
docker run -d --rm --name "$MINIO_CONTAINER_TEST" --network "$NETWORK" \
  -e MINIO_ROOT_USER=restore -e MINIO_ROOT_PASSWORD=restore-only-password \
  "$MINIO_IMAGE" server /data >/dev/null
for _ in $(seq 1 60); do
  docker run --rm --network "$NETWORK" "$MC_IMAGE" \
    alias set probe "http://$MINIO_CONTAINER_TEST:9000" restore restore-only-password >/dev/null 2>&1 && break
  sleep 1
done

docker run --rm --network "$NETWORK" \
  -v "$STAGE/data:/backup:ro" \
  -v "$STAGE/policies:/policies:ro" \
  -v "$STAGE/restored-policies:/restored-policies" \
  -e POLICY_REQUIRED="$private_required" \
  -e TEST_MINIO_HOST="$MINIO_CONTAINER_TEST" \
  --entrypoint /bin/sh "$MC_IMAGE" -eu -c '
    mc alias set target "http://$TEST_MINIO_HOST:9000" restore restore-only-password >/dev/null
    restored=0
    for path in /backup/*; do
      [ -d "$path" ] || continue
      bucket=${path##*/}
      mc mb --ignore-existing "target/$bucket" >/dev/null
      mc mirror "$path" "target/$bucket"
      mc stat "target/$bucket" >/dev/null
      policy="/policies/$bucket.json"
      if [ -f "$policy" ]; then
        if [ "$(tr -d "[:space:]" < "$policy")" = "{}" ]; then
          mc anonymous set private "target/$bucket" >/dev/null
        else
          mc anonymous set-json "$policy" "target/$bucket" >/dev/null
        fi
        mc anonymous get-json "target/$bucket" > "/restored-policies/$bucket.json"
      else
        [ "$POLICY_REQUIRED" = false ]
      fi
      restored=$((restored + 1))
    done
    [ "$restored" -gt 0 ]
  '
python3 "$SCRIPT_DIR/ops/minio_policy.py" --stage "$STAGE" --actual "$STAGE/restored-policies" "${policy_legacy_args[@]}"

metadata_worker_args=()
if [[ -n "${MINIO_METADATA_PYTHON_IMAGE:-}" ]]; then
  metadata_worker_args=(--python-image "$MINIO_METADATA_PYTHON_IMAGE")
fi
if [[ -f "$STAGE/object-metadata.json" ]]; then
  # Exact newly created identity; source credentials never reach this helper.
  minio_target_id="$(docker inspect --format '{{.Id}}' "$MINIO_CONTAINER_TEST")"
  MINIO_METADATA_ACCESS_KEY=restore MINIO_METADATA_SECRET_KEY=restore-only-password \
    python3 "$SCRIPT_DIR/ops/minio_metadata.py" restore --stage "$STAGE" \
    --container "$MINIO_CONTAINER_TEST" --target-id "$minio_target_id" \
    "${metadata_worker_args[@]}" "${policy_legacy_args[@]}"
else
  python3 "$SCRIPT_DIR/ops/minio_metadata.py" validate --stage "$STAGE" "${policy_legacy_args[@]}"
fi

finished="$(date +%s)"
write_metric "$METRIC_DIR" "epharm_restore_test.prom" \
  '# HELP epharm_restore_test_last_success_timestamp_seconds Unix timestamp of the last successful isolated restore test.' \
  '# TYPE epharm_restore_test_last_success_timestamp_seconds gauge' \
  "epharm_restore_test_last_success_timestamp_seconds $finished" \
  '# HELP epharm_restore_test_duration_seconds Duration of the last isolated restore test.' \
  '# TYPE epharm_restore_test_duration_seconds gauge' \
  "epharm_restore_test_duration_seconds $((finished - started))"

echo "[$(date -u +%FT%TZ)] Restore-test OK: PostgreSQL tables=$table_count migrations=$migration_count; MinIO buckets restored"
