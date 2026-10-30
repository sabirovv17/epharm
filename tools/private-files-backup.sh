#!/usr/bin/env bash
# Additive snapshots of native private files and a separate credential-key recovery artifact.
set -euo pipefail
umask 077
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=tools/ops/lib.sh
source "$SCRIPT_DIR/ops/lib.sh"
required="${BACKUP_PRIVATE_FILES_REQUIRED:-false}"
[[ "$required" == true || "$required" == false ]] || { echo 'ERROR: invalid private backup requirement' >&2; exit 2; }
[[ "$required" == true ]] || { echo 'Private-file backup not enabled for this installation.'; exit 0; }
require_command python3
require_nonempty MERCH_EVIDENCE_HOST_ROOT
require_nonempty MERCH_LEGACY_FILES_HOST_ROOT
require_nonempty LEARNER_CREDENTIALS_HOST_KEY_DIRECTORY
BACKUP_ROOT="${BACKUP_ROOT:-$(ops_root_dir)/backups}"
mkdir -p "$BACKUP_ROOT"
acquire_lock "$BACKUP_ROOT/.native-files-backup.lock"
python3 "$SCRIPT_DIR/ops/private_backup.py" create --kind native-files --directory "$BACKUP_ROOT/native-files" \
  --root "merch=$MERCH_EVIDENCE_HOST_ROOT" --root "merch-legacy=$MERCH_LEGACY_FILES_HOST_ROOT"
python3 "$SCRIPT_DIR/ops/private_backup.py" create --kind credential-keys --directory "$BACKUP_ROOT/credential-keys" \
  --root "learner-credentials=$LEARNER_CREDENTIALS_HOST_KEY_DIRECTORY"
write_metric "${BACKUP_METRIC_DIR:-$BACKUP_ROOT/metrics}" epharm_native_files_backup.prom \
  '# TYPE epharm_backup_last_success_timestamp_seconds gauge' \
  "epharm_backup_last_success_timestamp_seconds{component=\"native-files\"} $(date +%s)"
