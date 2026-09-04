#!/usr/bin/env bash
set -Eeuo pipefail

TEST_DIR="$(mktemp -d)"
trap 'rm -rf -- "$TEST_DIR"' EXIT
TEST_SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
BUNDLE_DIR="$(CDPATH= cd -- "${TEST_SCRIPT_DIR}/.." && pwd)"
mkdir -p "${TEST_DIR}/state" "${TEST_DIR}/config" "${TEST_DIR}/backups" "${TEST_DIR}/bin"
cp -- "${BUNDLE_DIR}/release.env.example" "${TEST_DIR}/state/release.env"
: >"${TEST_DIR}/config/app.env"
: >"${TEST_DIR}/config/postgres.env"
BACKUP="${TEST_DIR}/backups/inkar-postgres-test.dump"
printf fixture >"$BACKUP"

cat >"${TEST_DIR}/bin/docker" <<'MOCK'
#!/usr/bin/env bash
set -u
printf '%s\n' "$*" >>"$DOCKER_LOG"
if [[ "${1:-}" == "inspect" ]]; then
  printf healthy
  exit 0
fi
if [[ "$*" == *" ps -q "* ]]; then
  printf 'fake-container\n'
  exit 0
fi
if [[ "$*" == *"api/health?deep=1"* && "${FAIL_DEEP:-0}" == "1" ]]; then
  exit 1
fi
exit 0
MOCK
chmod +x "${TEST_DIR}/bin/docker"
cat >"${TEST_DIR}/bin/flock" <<'MOCK'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"$FLOCK_LOG"
if [[ "${FAIL_FLOCK:-0}" == "1" ]]; then
  exit 1
fi
exit 0
MOCK
cat >"${TEST_DIR}/bin/systemctl" <<'MOCK'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"$SYSTEMCTL_LOG"
case "${1:-}" in
  show) printf 'loaded\n'; exit 0 ;;
  is-active)
    case "$*" in
      *inkar-shop-catalog-sync.timer|*inkar-shop-offer-sync.timer) exit 0 ;;
      *) exit 3 ;;
    esac
    ;;
  stop|start) exit 0 ;;
esac
exit 0
MOCK
cat >"${TEST_DIR}/bin/stat" <<'MOCK'
#!/usr/bin/env bash
case "${2:-}" in
  %a) printf '660\n' ;;
  %g) printf '20\n' ;;
  *) exit 1 ;;
esac
MOCK
chmod +x "${TEST_DIR}/bin/flock" "${TEST_DIR}/bin/systemctl" "${TEST_DIR}/bin/stat"
line_of() {
  local pattern="$1" line
  line="$(grep -nF -- "$pattern" "$DOCKER_LOG" | head -n 1 | cut -d: -f1)"
  [[ -n "$line" ]] || { printf 'missing docker call: %s\n' "$pattern" >&2; return 1; }
  printf '%s' "$line"
}

assert_ordered() {
  local previous=0 current pattern
  for pattern in "$@"; do
    current="$(line_of "$pattern")"
    (( current > previous )) || { printf 'out-of-order docker call: %s\n' "$pattern" >&2; return 1; }
    previous="$current"
  done
}

export INKAR_STATE_ROOT="$TEST_DIR"
export PATH="${TEST_DIR}/bin:${PATH}"
export DOCKER_LOG="${TEST_DIR}/docker.log"
export FLOCK_LOG="${TEST_DIR}/flock.log"
export SYSTEMCTL_LOG="${TEST_DIR}/systemctl.log"
export FAIL_DEEP=0
export FAIL_FLOCK=0
: >"$DOCKER_LOG"
: >"$FLOCK_LOG"
: >"$SYSTEMCTL_LOG"
bash "${BUNDLE_DIR}/restore.sh" --yes "$BACKUP" >/dev/null
assert_ordered \
  'stop --timeout 30 edge router web-blue web-green' \
  'up -d postgres' \
  'pg_restore --clean' \
  'operation node scripts/db-migrate.mjs' \
  'up -d --no-deps web-blue' \
  'api/health?deep=1' \
  'up -d --no-deps router' \
  'up -d --no-deps edge'
grep -q '^-n 9$' "$FLOCK_LOG"
grep -q 'stop inkar-shop-catalog-sync.timer inkar-shop-offer-sync.timer inkar-shop-backup.timer' "$SYSTEMCTL_LOG"
grep -q 'stop inkar-shop-catalog-sync.service inkar-shop-offer-sync.service inkar-shop-backup.service' "$SYSTEMCTL_LOG"
grep -q 'start inkar-shop-catalog-sync.timer inkar-shop-offer-sync.timer' "$SYSTEMCTL_LOG"
if grep -q 'start .*inkar-shop-backup.timer' "$SYSTEMCTL_LOG"; then
  printf 'inactive backup timer was unexpectedly restarted\n' >&2
  exit 1
fi

export FAIL_DEEP=1
: >"$DOCKER_LOG"
: >"$FLOCK_LOG"
: >"$SYSTEMCTL_LOG"
if bash "${BUNDLE_DIR}/restore.sh" --yes "$BACKUP" >"${TEST_DIR}/restore.out" 2>"${TEST_DIR}/restore.err"; then
  printf 'deep-check failure unexpectedly restored traffic\n' >&2
  exit 1
fi
grep -q 'traffic remains stopped' "${TEST_DIR}/restore.err"
if grep -qF 'up -d --no-deps edge' "$DOCKER_LOG"; then
  printf 'edge restarted after failed deep readiness\n' >&2
  exit 1
fi
if grep -q '^start ' "$SYSTEMCTL_LOG"; then
  printf 'timer restarted after failed restore\n' >&2
  exit 1
fi

export FAIL_DEEP=0
export FAIL_FLOCK=1
: >"$DOCKER_LOG"
: >"$FLOCK_LOG"
: >"$SYSTEMCTL_LOG"
if bash "${BUNDLE_DIR}/restore.sh" --yes "$BACKUP" >"${TEST_DIR}/lock.out" 2>"${TEST_DIR}/lock.err"; then
  printf 'lock contention unexpectedly allowed restore\n' >&2
  exit 1
fi
grep -q 'another deploy/sync/backup/restore operation owns' "${TEST_DIR}/lock.err"
if [[ -s "$DOCKER_LOG" || -s "$SYSTEMCTL_LOG" ]]; then
  printf 'restore touched services despite lock contention\n' >&2
  exit 1
fi

printf 'restore order harness passed\n'
