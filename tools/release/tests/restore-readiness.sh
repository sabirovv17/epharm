#!/usr/bin/env bash
set -euo pipefail

source_root="$(cd "$(dirname "$0")/../../.." && pwd)"
source "$source_root/tools/ops/lib.sh"
test_root="$(mktemp -d "${TMPDIR:-/tmp}/epharm-restore-readiness.XXXXXXXX")"
trap 'rm -rf -- "$test_root"' EXIT

mkdir -p "$test_root/bin" "$test_root/backups/postgres" \
  "$test_root/backups/minio" "$test_root/minio-data/data/test-bucket"
cp "$source_root/tools/release/tests/fixtures/fake-docker-restore.sh" "$test_root/bin/docker"
chmod +x "$test_root/bin/docker"
printf 'fixture' > "$test_root/minio-data/data/test-bucket/file"
printf 'fixture' | gzip > "$test_root/backups/postgres/epharm-postgres-fixture.dump.gz"
tar -C "$test_root/minio-data" -czf "$test_root/backups/minio/epharm-minio-fixture.tar.gz" data
for backup in "$test_root/backups/postgres/"*.gz "$test_root/backups/minio/"*.gz; do
  sha256_file "$backup" > "$backup.sha256"
done
printf 'MINIO_ROOT_USER=fixture\nMINIO_ROOT_PASSWORD=fixture\nBACKUP_ROOT=%s\n' \
  "$test_root/backups" > "$test_root/.env.prod"

if ! PATH="$test_root/bin:$PATH" \
  EPHARM_ENV_FILE="$test_root/.env.prod" \
  FAKE_DOCKER_STATE="$test_root/docker-state" \
  "$source_root/tools/restore-test.sh" > "$test_root/restore.log" 2>&1; then
  tail -n 80 "$test_root/restore.log" >&2
  exit 1
fi

[[ "$(grep -c '^tcp-probe$' "$test_root/docker-state")" -eq 4 ]]
[[ "$(grep -c '^db-probe$' "$test_root/docker-state")" -eq 2 ]]
[[ "$(grep -c '^restore$' "$test_root/docker-state")" -eq 1 ]]
[[ "$(grep -c '^cleanup-volumes$' "$test_root/docker-state")" -eq 2 ]]
grep -Fq 'Restore-test OK' "$test_root/restore.log"
echo 'Restore readiness and disposable anonymous-volume cleanup OK'
