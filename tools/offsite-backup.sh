#!/usr/bin/env bash
# Optional encrypted off-site copy and independent retention using restic.
set -euo pipefail
umask 077

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=tools/ops/lib.sh
source "$SCRIPT_DIR/ops/lib.sh"

ROOT_DIR="$(ops_root_dir)"
BACKUP_ROOT="${BACKUP_ROOT:-$ROOT_DIR/backups}"
METRIC_DIR="${BACKUP_METRIC_DIR:-$BACKUP_ROOT/metrics}"

if [[ -z "${RESTIC_REPOSITORY:-}" ]]; then
  if [[ "${BACKUP_REQUIRE_OFFSITE:-false}" == true ]]; then
    echo 'ERROR: off-site backup is required but RESTIC_REPOSITORY is empty' >&2
    exit 1
  fi
  echo 'Off-site backup is not configured; local backup completed only.'
  exit 0
fi

require_command restic
require_nonempty RESTIC_PASSWORD
[[ -d "$BACKUP_ROOT/postgres" && -d "$BACKUP_ROOT/minio" ]] || {
  echo "ERROR: local backups are missing below $BACKUP_ROOT" >&2
  exit 1
}

started="$(date +%s)"
restic snapshots --json >/dev/null 2>&1 || restic init
restic backup "$BACKUP_ROOT/postgres" "$BACKUP_ROOT/minio" --tag epharm-production
restic forget --prune \
  --tag epharm-production \
  --keep-daily "${RESTIC_KEEP_DAILY:-30}" \
  --keep-weekly "${RESTIC_KEEP_WEEKLY:-8}" \
  --keep-monthly "${RESTIC_KEEP_MONTHLY:-12}"
restic check --read-data-subset="${RESTIC_CHECK_SUBSET:-1/20}"

finished="$(date +%s)"
write_metric "$METRIC_DIR" "epharm_offsite_backup.prom" \
  '# HELP epharm_backup_last_success_timestamp_seconds Unix timestamp of the last successful backup.' \
  '# TYPE epharm_backup_last_success_timestamp_seconds gauge' \
  "epharm_backup_last_success_timestamp_seconds{component=\"offsite\"} $finished" \
  '# HELP epharm_backup_duration_seconds Duration of the last successful backup.' \
  '# TYPE epharm_backup_duration_seconds gauge' \
  "epharm_backup_duration_seconds{component=\"offsite\"} $((finished - started))"

echo "[$(date -u +%FT%TZ)] Encrypted off-site backup and retention OK"
