#!/usr/bin/env bash
set -euo pipefail

source_root="$(cd "$(dirname "$0")/../../.." && pwd)"
test_root="$(mktemp -d "${TMPDIR:-/tmp}/epharm-smoke-readiness.XXXXXXXX")"
server_pid=''
cleanup() {
  if [[ -n "$server_pid" ]]; then
    kill "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
  fi
  rm -rf -- "$test_root"
}
trap cleanup EXIT

python3 -u "$source_root/tools/release/tests/smoke-readiness-server.py" \
  > "$test_root/port" 2> "$test_root/server.log" &
server_pid=$!
for _attempt in {1..50}; do
  [[ -s "$test_root/port" ]] && break
  kill -0 "$server_pid" 2>/dev/null || { cat "$test_root/server.log" >&2; exit 1; }
  sleep 0.1
done
[[ -s "$test_root/port" ]] || { echo 'Smoke fixture failed to start' >&2; exit 1; }
port="$(head -n 1 "$test_root/port")"

SMOKE_BASE_URL="http://127.0.0.1:$port" RELEASE_READINESS_WAIT_SECONDS=10 \
  "$source_root/tools/release/smoke.sh" v0.1.14

if SMOKE_BASE_URL='http://127.0.0.1:1' RELEASE_READINESS_WAIT_SECONDS=1 \
  "$source_root/tools/release/smoke.sh" v0.1.14 > "$test_root/timeout.log" 2>&1; then
  echo 'Smoke accepted a permanently unavailable release' >&2
  exit 1
fi
grep -Fq 'did not report v0.1.14' "$test_root/timeout.log"

# Backend-only releases keep a distinct frontend tag. With no positional args,
# smoke must derive both effective component IDs from the persisted release env.
kill "$server_pid"
wait "$server_pid" 2>/dev/null || true
server_pid=''
mkdir -p "$test_root/split/tools/release" "$test_root/split/tools/ops"
cp "$source_root/tools/release/smoke.sh" "$source_root/tools/release/lib.sh" "$test_root/split/tools/release/"
cp "$source_root/tools/ops/lib.sh" "$test_root/split/tools/ops/lib.sh"
printf 'RELEASE_ID=v0.1.14\nRELEASE_COMMIT=fixture\nBACKEND_RELEASE_ID=v0.1.14\nFRONTEND_RELEASE_ID=v0.1.22\n' > "$test_root/split/.release.env"
FRONTEND_FIXTURE_RELEASE_ID=v0.1.22 \
  python3 -u "$source_root/tools/release/tests/smoke-readiness-server.py" \
  > "$test_root/split-port" 2> "$test_root/split-server.log" &
server_pid=$!
for _attempt in {1..50}; do
  [[ -s "$test_root/split-port" ]] && break
  kill -0 "$server_pid" 2>/dev/null || { cat "$test_root/split-server.log" >&2; exit 1; }
  sleep 0.1
done
[[ -s "$test_root/split-port" ]] || { echo 'Split smoke fixture failed to start' >&2; exit 1; }
split_port="$(head -n 1 "$test_root/split-port")"
SMOKE_BASE_URL="http://127.0.0.1:$split_port" RELEASE_READINESS_WAIT_SECONDS=10 \
  "$test_root/split/tools/release/smoke.sh"
echo 'Release readiness retry and timeout OK'
