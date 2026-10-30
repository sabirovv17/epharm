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
[[ "${BACKUP_PRIVATE_FILES_REQUIRED:-false}" == true || "${BACKUP_PRIVATE_FILES_REQUIRED:-false}" == false ]] || {
  echo 'ERROR: BACKUP_PRIVATE_FILES_REQUIRED must be true/false' >&2; exit 2;
}

if [[ -z "${RESTIC_REPOSITORY:-}" ]]; then
  if [[ "${BACKUP_REQUIRE_OFFSITE:-false}" == true ]]; then
    echo 'ERROR: off-site backup is required but RESTIC_REPOSITORY is empty' >&2
    exit 1
  fi
  echo 'Off-site backup is not configured; local backup completed only.'
  exit 0
fi

require_command restic
if [[ -n "${RESTIC_PASSWORD_FILE:-}" ]]; then
  [[ -s "$RESTIC_PASSWORD_FILE" && -r "$RESTIC_PASSWORD_FILE" ]] || {
    echo 'ERROR: restic password file is missing or unreadable' >&2; exit 1;
  }
else
  require_nonempty RESTIC_PASSWORD
fi
[[ -d "$BACKUP_ROOT/postgres" && -d "$BACKUP_ROOT/minio" ]] || {
  echo "ERROR: local backups are missing below $BACKUP_ROOT" >&2
  exit 1
}

started="$(date +%s)"
paths=("$BACKUP_ROOT/postgres" "$BACKUP_ROOT/minio")
if [[ "${BACKUP_PRIVATE_FILES_REQUIRED:-false}" == true ]]; then
  for component in native-files credential-keys; do
    archive="$(latest_matching_file "$BACKUP_ROOT/$component" "epharm-$component-*.tar.gz")"
    [[ -n "$archive" ]] || { echo 'ERROR: required private snapshot is missing' >&2; exit 1; }
    python3 "$SCRIPT_DIR/ops/private_backup.py" verify --kind "$component" --archive "$archive" >/dev/null
    paths+=("$BACKUP_ROOT/$component")
  done
fi
# RESTIC_SFTP_COMMAND is our ops setting, not a native restic environment variable.
# Pass it explicitly using restic's documented transport option, preserving one argv.
restic_options=()
if [[ -n "${RESTIC_SFTP_COMMAND:-}" ]]; then
  restic_options+=(-o "sftp.command=$RESTIC_SFTP_COMMAND")
fi
restic "${restic_options[@]}" snapshots --json >/dev/null 2>&1 || restic "${restic_options[@]}" init
restic "${restic_options[@]}" backup "${paths[@]}" --tag epharm-production
restic "${restic_options[@]}" forget --prune \
  --tag epharm-production \
  --keep-daily "${RESTIC_KEEP_DAILY:-30}" \
  --keep-weekly "${RESTIC_KEEP_WEEKLY:-8}" \
  --keep-monthly "${RESTIC_KEEP_MONTHLY:-12}"
restic "${restic_options[@]}" check --read-data-subset="${RESTIC_CHECK_SUBSET:-1/20}"

finished="$(date +%s)"
write_metric "$METRIC_DIR" "epharm_offsite_backup.prom" \
  '# HELP epharm_backup_last_success_timestamp_seconds Unix timestamp of the last successful backup.' \
  '# TYPE epharm_backup_last_success_timestamp_seconds gauge' \
  "epharm_backup_last_success_timestamp_seconds{component=\"offsite\"} $finished" \
  '# HELP epharm_backup_duration_seconds Duration of the last successful backup.' \
  '# TYPE epharm_backup_duration_seconds gauge' \
  "epharm_backup_duration_seconds{component=\"offsite\"} $((finished - started))"

echo "[$(date -u +%FT%TZ)] Encrypted off-site backup and retention OK"
