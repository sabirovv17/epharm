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
require_nonempty MINIO_ROOT_USER
require_nonempty MINIO_ROOT_PASSWORD

BACKUP_ROOT="${BACKUP_ROOT:-$ROOT_DIR/backups}"
METRIC_DIR="${BACKUP_METRIC_DIR:-$BACKUP_ROOT/metrics}"
PG_IMAGE="${POSTGRES_RESTORE_IMAGE:-postgres:16-alpine}"
MC_IMAGE="${MINIO_MC_IMAGE:-minio/mc:RELEASE.2025-08-13T08-35-41Z}"
MINIO_IMAGE="${MINIO_RESTORE_IMAGE:-minio/minio:RELEASE.2025-07-23T15-54-02Z}"
PG_BACKUP="${POSTGRES_BACKUP_FILE:-$(latest_matching_file "$BACKUP_ROOT/postgres" 'epharm-postgres-*.dump.gz')}"
MINIO_BACKUP="${MINIO_BACKUP_FILE:-$(latest_matching_file "$BACKUP_ROOT/minio" 'epharm-minio-*.tar.gz')}"
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$$"
PG_CONTAINER="epharm-restore-pg-$RUN_ID"
MINIO_CONTAINER_TEST="epharm-restore-minio-$RUN_ID"
NETWORK="epharm-restore-$RUN_ID"
STAGE="$BACKUP_ROOT/.restore-$RUN_ID"

[[ -n "$PG_BACKUP" && -f "$PG_BACKUP" ]] || { echo "ERROR: no PostgreSQL backup found" >&2; exit 1; }
[[ -n "$MINIO_BACKUP" && -f "$MINIO_BACKUP" ]] || { echo "ERROR: no MinIO backup found" >&2; exit 1; }

mkdir -p "$STAGE"
acquire_lock "$BACKUP_ROOT/.restore-test.lock"
cleanup() {
  docker_cleanup_container "$PG_CONTAINER"
  docker_cleanup_container "$MINIO_CONTAINER_TEST"
  docker network rm "$NETWORK" >/dev/null 2>&1 || true
  rm -rf "$STAGE"
  release_lock
}
trap cleanup EXIT INT TERM

started="$(date +%s)"
echo "[$(date -u +%FT%TZ)] Isolated restore-test started"

[[ -f "$PG_BACKUP.sha256" ]] || { echo "ERROR: missing checksum: $PG_BACKUP.sha256" >&2; exit 1; }
[[ -f "$MINIO_BACKUP.sha256" ]] || { echo "ERROR: missing checksum: $MINIO_BACKUP.sha256" >&2; exit 1; }
[[ "$(sha256_file "$PG_BACKUP")" == "$(awk '{print $1}' "$PG_BACKUP.sha256")" ]]
[[ "$(sha256_file "$MINIO_BACKUP")" == "$(awk '{print $1}' "$MINIO_BACKUP.sha256")" ]]
gzip -t "$PG_BACKUP"
tar -tzf "$MINIO_BACKUP" >/dev/null

docker network create "$NETWORK" >/dev/null
docker run -d --rm --name "$PG_CONTAINER" --network "$NETWORK" \
  -e POSTGRES_DB=epharm_restore -e POSTGRES_USER=epharm_restore -e POSTGRES_PASSWORD=restore-only \
  "$PG_IMAGE" >/dev/null

for _ in $(seq 1 60); do
  docker exec "$PG_CONTAINER" pg_isready -U epharm_restore -d epharm_restore >/dev/null 2>&1 && break
  sleep 1
done
docker exec "$PG_CONTAINER" pg_isready -U epharm_restore -d epharm_restore >/dev/null
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
      restored=$((restored + 1))
    done
    [ "$restored" -gt 0 ]
  '

finished="$(date +%s)"
write_metric "$METRIC_DIR" "epharm_restore_test.prom" \
  '# HELP epharm_restore_test_last_success_timestamp_seconds Unix timestamp of the last successful isolated restore test.' \
  '# TYPE epharm_restore_test_last_success_timestamp_seconds gauge' \
  "epharm_restore_test_last_success_timestamp_seconds $finished" \
  '# HELP epharm_restore_test_duration_seconds Duration of the last isolated restore test.' \
  '# TYPE epharm_restore_test_duration_seconds gauge' \
  "epharm_restore_test_duration_seconds $((finished - started))"

echo "[$(date -u +%FT%TZ)] Restore-test OK: PostgreSQL tables=$table_count migrations=$migration_count; MinIO buckets restored"
