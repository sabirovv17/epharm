#!/usr/bin/env bash
# Run real backup orchestration/restore selection offline, with inert producers and prune calls.
set -euo pipefail
umask 077
source_root="$(cd "$(dirname "$0")/../../.." && pwd)"
test_root="$(mktemp -d "${TMPDIR:-/tmp}/epharm-backup-chain.XXXXXXXX")"
test_root="$(cd "$test_root" && pwd -P)"
trap 'rm -rf -- "$test_root"' EXIT
command -v flock >/dev/null || { echo 'This contract requires Linux flock' >&2; exit 1; }
export CHAIN_REAL_PYTHON CHAIN_REAL_FLOCK CHAIN_REAL_FIND
CHAIN_REAL_PYTHON="$(command -v python3)"
CHAIN_REAL_FLOCK="$(command -v flock)"
CHAIN_REAL_FIND="$(command -v find)"
mkdir -p "$test_root/tools/ops" "$test_root/bin" "$test_root/deploy/tools/release"
cp "$source_root/tools/backup-all.sh" "$source_root/tools/restore-test.sh" "$test_root/tools/"
cp "$source_root/tools/ops/lib.sh" "$test_root/tools/ops/lib.sh"
export CHAIN_EVENTS="$test_root/events" BACKUP_ROOT="$test_root/backups"
export BACKUP_RELEASE_ROOT="$test_root/epharm-backend-only-backups" BACKUP_RELEASE_DEPLOY_DIRS="$test_root/deploy"
export BACKUP_KEEP_COUNT=7 BACKUP_RELEASE_KEEP_COUNT=3 BACKUP_RETENTION_DRY_RUN=false
export EPHARM_ENV_FILE="$test_root/fixture.env"
printf 'MINIO_ROOT_USER=fixture\nMINIO_ROOT_PASSWORD=fixture-only\n' > "$EPHARM_ENV_FILE"
mkdir -p "$BACKUP_ROOT/postgres" "$BACKUP_ROOT/minio" "$BACKUP_RELEASE_ROOT"
cat > "$test_root/bin/producer" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
case "${0##*/}" in pg-backup.sh) stage=pg ;; minio-backup.sh) stage=minio ;; offsite-backup.sh) stage=offsite ;; *) exit 99 ;; esac
printf '%s\n' "$stage" >> "$CHAIN_EVENTS"
[[ "${CHAIN_FAIL:-}" != "$stage" ]]
SH
for producer in pg-backup.sh minio-backup.sh offsite-backup.sh; do cp "$test_root/bin/producer" "$test_root/tools/$producer"; done
cat > "$test_root/bin/python3" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
case "${1##*/}" in
  prune-backups.py|prune-release-backups.py)
    script="${1##*/}"; shift
    component=release; mode=preflight
    while (( $# )); do
      case "$1" in
        --kind) component="$2"; shift ;;
        --apply) mode=apply ;;
        --deploy-root)
          if "$CHAIN_REAL_FLOCK" -n "$2/releases/backend-only/.deploy.lock" -c true; then
            echo 'Release retention ran without its deployment lock' >&2; exit 84
          fi
          shift ;;
      esac
      shift
    done
    [[ "$script" != prune-backups.py || "$component" != release ]]
    printf '%s:%s\n' "$mode" "$component" >> "$CHAIN_EVENTS"
    [[ "${CHAIN_FAIL:-}" != "$mode:$component" ]]
    ;;
  *) exec "$CHAIN_REAL_PYTHON" "$@" ;;
esac
SH
cat > "$test_root/bin/find" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf 'select:%s\n' "${1##*/}" >> "$CHAIN_EVENTS"
if "$CHAIN_REAL_FLOCK" -n "$BACKUP_ROOT/.backup-suite.lock" -c true; then
  echo 'Restore selected backups without holding the suite lock' >&2; exit 85
fi
exec "$CHAIN_REAL_FIND" "$@"
SH
cat > "$test_root/bin/docker" <<'SH'
#!/usr/bin/env bash
echo 'Unexpected Docker operation in offline backup-chain contract' >&2
exit 86
SH
chmod +x "$test_root/bin/"* "$test_root/tools/"*.sh
export PATH="$test_root/bin:$PATH"
expect_events() {
  printf '%s\n' "$@" > "$test_root/expected"
  diff -u "$test_root/expected" "$CHAIN_EVENTS"
}
reject_chain() {
  : > "$CHAIN_EVENTS"
  if env "$@" "$test_root/tools/backup-all.sh" > "$test_root/rejected.log" 2>&1; then
    echo "Backup chain accepted the rejected case: $*" >&2; exit 1
  fi
  if grep -q '^apply:' "$CHAIN_EVENTS"; then echo 'A failed backup chain applied retention' >&2; exit 1; fi
}
for value in 0 1 -1 invalid; do
  reject_chain "BACKUP_KEEP_COUNT=$value"
  [[ ! -s "$CHAIN_EVENTS" ]]
  reject_chain "BACKUP_RELEASE_KEEP_COUNT=$value"
  [[ ! -s "$CHAIN_EVENTS" ]]
done
reject_chain BACKUP_RETENTION_DRY_RUN=invalid
[[ ! -s "$CHAIN_EVENTS" ]]
for stage in pg minio offsite; do
  reject_chain "CHAIN_FAIL=$stage"
  case "$stage" in pg) expect_events pg ;; minio) expect_events pg minio ;; offsite) expect_events pg minio offsite ;; esac
done
for component in postgres minio release; do
  reject_chain "CHAIN_FAIL=preflight:$component"
  if grep -q '^apply:' "$CHAIN_EVENTS"; then echo 'Preflight failure permitted an apply' >&2; exit 1; fi
done
: > "$CHAIN_EVENTS"
BACKUP_RETENTION_DRY_RUN=true "$test_root/tools/backup-all.sh" >/dev/null
expect_events pg minio offsite preflight:postgres preflight:minio preflight:release
: > "$CHAIN_EVENTS"
"$test_root/tools/backup-all.sh" >/dev/null
expect_events pg minio offsite preflight:postgres preflight:minio preflight:release apply:postgres apply:minio apply:release
: > "$CHAIN_EVENTS"
BACKUP_RELEASE_ROOT='' "$test_root/tools/backup-all.sh" >/dev/null
expect_events pg minio offsite preflight:postgres preflight:minio apply:postgres apply:minio

# Real kernel lock contention must stop the chain and restore before any producer/selection.
exec 7>"$BACKUP_ROOT/.backup-suite.lock"
"$CHAIN_REAL_FLOCK" -n 7
reject_chain
[[ ! -s "$CHAIN_EVENTS" ]]
if "$test_root/tools/restore-test.sh" > "$test_root/restore-locked.log" 2>&1; then
  echo 'Restore ignored an exclusive backup suite lock' >&2; exit 1
fi
[[ ! -s "$CHAIN_EVENTS" ]]
"$CHAIN_REAL_FLOCK" -u 7
exec 7>&-

# No backups are present: restore reaches selection under its shared lock, then exits before Docker.
if "$test_root/tools/restore-test.sh" > "$test_root/restore-empty.log" 2>&1; then
  echo 'Empty restore unexpectedly succeeded' >&2; exit 1
fi
grep -Fq 'no PostgreSQL backup found' "$test_root/restore-empty.log"
expect_events select:postgres select:minio
"$CHAIN_REAL_FLOCK" -n "$BACKUP_ROOT/.backup-suite.lock" -c true
echo 'Backup full-chain, preflight/apply ordering, dry-run and restore suite-lock contract OK'
