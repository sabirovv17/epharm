#!/usr/bin/env bash
set -euo pipefail
umask 077

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=tools/ops/lib.sh
source "$SCRIPT_DIR/ops/lib.sh"
BACKUP_ROOT="${BACKUP_ROOT:-$(ops_root_dir)/backups}"
keep="${BACKUP_KEEP_COUNT:-7}"
dry_run="${BACKUP_RETENTION_DRY_RUN:-false}"
[[ "${BACKUP_PRIVATE_FILES_REQUIRED:-false}" == true || "${BACKUP_PRIVATE_FILES_REQUIRED:-false}" == false ]] || {
  echo 'ERROR: BACKUP_PRIVATE_FILES_REQUIRED must be true/false' >&2; exit 2;
}
[[ "$keep" =~ ^[0-9]+$ && "$keep" -ge 2 ]] || {
  echo 'ERROR: BACKUP_KEEP_COUNT must be at least 2' >&2; exit 2;
}
[[ "$dry_run" == true || "$dry_run" == false ]] || {
  echo 'ERROR: BACKUP_RETENTION_DRY_RUN must be true/false' >&2; exit 2;
}
if [[ -n "${BACKUP_RELEASE_ROOT:-}" ]]; then
  release_keep="${BACKUP_RELEASE_KEEP_COUNT:-3}"
  [[ "$release_keep" =~ ^[0-9]+$ && "$release_keep" -ge 2 && -n "${BACKUP_RELEASE_DEPLOY_DIRS:-}" ]] || {
    echo 'ERROR: release retention needs a keep count >= 2 and deployment roots' >&2; exit 2;
  }
fi
require_command python3
lock_backup_suite "$BACKUP_ROOT"
"$SCRIPT_DIR/pg-backup.sh"
"$SCRIPT_DIR/minio-backup.sh"
"$SCRIPT_DIR/private-files-backup.sh"
"$SCRIPT_DIR/offsite-backup.sh"

# Standalone producers are additive. Only a completed full chain may evict copies.
component_locks=()
release_retention_locks() {
  local owned_lock
  for owned_lock in "${component_locks[@]}"; do rmdir "$owned_lock"; done
}
trap release_retention_locks EXIT
trap 'exit 1' INT TERM
components=(postgres minio)
if [[ "${BACKUP_PRIVATE_FILES_REQUIRED:-false}" == true ]]; then components+=(native-files); fi
for component in "${components[@]}"; do
  component_lock="$BACKUP_ROOT/.$component-backup.lock"
  mkdir "$component_lock" || {
    echo "ERROR: standalone $component backup is running; retention deferred" >&2; exit 1;
  }
  component_locks+=("$component_lock")
done
for component in "${components[@]}"; do
  python3 "$SCRIPT_DIR/ops/prune-backups.py" --kind "$component" \
    --directory "$BACKUP_ROOT/$component" --keep "$keep"
done
release_args=()
if [[ -n "${BACKUP_RELEASE_ROOT:-}" ]]; then
  release_args=(--directory "$BACKUP_RELEASE_ROOT" --keep "$release_keep")
  IFS=: read -r -a configured_roots <<< "$BACKUP_RELEASE_DEPLOY_DIRS"
  canonical_roots=()
  for deploy_root in "${configured_roots[@]}"; do
    [[ "$deploy_root" == /* && -d "$deploy_root/tools/release" ]] || {
      echo 'ERROR: release retention needs existing absolute deployment roots' >&2; exit 2;
    }
    canonical_roots+=("$(cd "$deploy_root" && pwd -P)")
  done
  # A symlink and its real directory must not acquire the same lock twice.
  mapfile -t canonical_roots < <(printf '%s\n' "${canonical_roots[@]}" | LC_ALL=C sort -u)
  for deploy_root in "${canonical_roots[@]}"; do
    mkdir -p "$deploy_root/releases/backend-only"
    exec {release_lock_fd}>"$deploy_root/releases/backend-only/.deploy.lock"
    flock -n "$release_lock_fd" || {
      echo 'ERROR: release operation is running; retention deferred' >&2; exit 1;
    }
    release_args+=(--deploy-root "$deploy_root")
  done
  python3 "$SCRIPT_DIR/ops/prune-release-backups.py" "${release_args[@]}"
fi
if [[ "$dry_run" == true ]]; then
  echo 'Backup chain completed; retention dry-run only'
  exit 0
fi
for component in "${components[@]}"; do
  python3 "$SCRIPT_DIR/ops/prune-backups.py" --kind "$component" \
    --directory "$BACKUP_ROOT/$component" --keep "$keep" --apply
done
if [[ -n "${BACKUP_RELEASE_ROOT:-}" ]]; then
  python3 "$SCRIPT_DIR/ops/prune-release-backups.py" "${release_args[@]}" --apply
fi
