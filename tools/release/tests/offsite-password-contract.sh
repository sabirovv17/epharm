#!/usr/bin/env bash
set -euo pipefail
source_root="$(cd "$(dirname "$0")/../../.." && pwd)"
test_root="$(mktemp -d "${TMPDIR:-/tmp}/epharm-offsite-contract.XXXXXXXX")"
trap 'rm -rf -- "$test_root"' EXIT
mkdir -p "$test_root/bin" "$test_root/backups/postgres" "$test_root/backups/minio"
printf 'fixture-only-password\n' > "$test_root/password"
cp "$source_root/tools/release/tests/fixtures/fake-restic.sh" "$test_root/bin/restic"
chmod +x "$test_root/bin/restic"
export PATH="$test_root/bin:$PATH" RESTIC_REPOSITORY=fixture-only-repository
export RESTIC_PASSWORD_FILE="$test_root/password" BACKUP_ROOT="$test_root/backups"
unset RESTIC_PASSWORD
bash "$source_root/tools/offsite-backup.sh"
test -s "$BACKUP_ROOT/metrics/epharm_offsite_backup.prom"
if RESTIC_PASSWORD_FILE="$test_root/missing" bash "$source_root/tools/offsite-backup.sh" >/dev/null 2>&1; then
  echo 'Missing restic password file accepted' >&2; exit 1
fi
echo 'Offsite password-file contract OK'
