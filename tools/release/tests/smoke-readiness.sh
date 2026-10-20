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
  SMOKE_BACKEND_COMMIT=backend-fixture SMOKE_FRONTEND_COMMIT=frontend-fixture \
  "$source_root/tools/release/smoke.sh" v0.1.14

for coordinate in SMOKE_BACKEND_COMMIT SMOKE_FRONTEND_COMMIT; do
  if env "$coordinate=wrong-commit" SMOKE_BASE_URL="http://127.0.0.1:$port" \
      RELEASE_READINESS_WAIT_SECONDS=0 "$source_root/tools/release/smoke.sh" v0.1.14 \
      > "$test_root/wrong-commit.log" 2>&1; then
    echo "Smoke accepted the wrong $coordinate" >&2
    exit 1
  fi
  grep -Fq 'did not report v0.1.14' "$test_root/wrong-commit.log"
done

if SMOKE_BASE_URL='http://127.0.0.1:1' RELEASE_READINESS_WAIT_SECONDS=1 \
  "$source_root/tools/release/smoke.sh" v0.1.14 > "$test_root/timeout.log" 2>&1; then
  echo 'Smoke accepted a permanently unavailable release' >&2
  exit 1
fi
grep -Fq 'did not report v0.1.14' "$test_root/timeout.log"

# Exercise the source-aware gate with the real HTTP checks, not a smoke stub.
start_source_fixture() {
  kill "$server_pid"
  wait "$server_pid" 2>/dev/null || true
  server_pid=''
  : > "$test_root/source-port"
  env CATALOG_FIXTURE_TRANSIENT_REQUESTS=0 "$@" \
    python3 -u "$source_root/tools/release/tests/smoke-readiness-server.py" \
    > "$test_root/source-port" 2> "$test_root/source-server.log" &
  server_pid=$!
  for _attempt in {1..50}; do
    [[ -s "$test_root/source-port" ]] && break
    kill -0 "$server_pid" 2>/dev/null || { cat "$test_root/source-server.log" >&2; return 1; }
    sleep 0.1
  done
  [[ -s "$test_root/source-port" ]] || return 1
  source_port="$(head -n 1 "$test_root/source-port")"
}
source_smoke() {
  SMOKE_BASE_URL="http://127.0.0.1:$source_port" RELEASE_READINESS_WAIT_SECONDS=0 \
    CATALOG_SNAPSHOT_WAIT_SECONDS=0 "$source_root/tools/release/smoke.sh" v0.1.14
}

start_source_fixture CATALOG_FIXTURE_SOURCE=website CATALOG_FIXTURE_SNAPSHOT_READY=false
source_smoke
for response in empty unavailable search-missing; do
  start_source_fixture CATALOG_FIXTURE_SOURCE=website CATALOG_FIXTURE_SNAPSHOT_READY=false \
    "CATALOG_FIXTURE_RESPONSE=$response"
  if source_smoke > "$test_root/site-catalogue.log" 2>&1; then
    echo "Smoke accepted an unusable website catalogue: $response" >&2; exit 1
  fi
  case "$response" in
    empty) grep -Fq 'Catalogue sample has no searchable name' "$test_root/site-catalogue.log" ;;
    unavailable) grep -Fq '503' "$test_root/site-catalogue.log" ;;
    search-missing) grep -Fq 'Catalogue search did not return its exact sample product' "$test_root/site-catalogue.log" ;;
  esac
done

# Only explicit website selection bypasses historical readiness. Old health with an unready
# snapshot must still fail, whereas old rollback health without that field keeps working.
for source in unknown ''; do
  start_source_fixture "CATALOG_FIXTURE_SOURCE=$source" CATALOG_FIXTURE_SNAPSHOT_READY=false
  if source_smoke > "$test_root/snapshot-not-ready.log" 2>&1; then
    echo "Smoke bypassed historical readiness for non-website source: $source" >&2; exit 1
  fi
  grep -Fq 'historical catalogue snapshot was not ready' "$test_root/snapshot-not-ready.log"
done
start_source_fixture CATALOG_FIXTURE_SNAPSHOT_READY=absent
source_smoke

# Backend-only releases keep a distinct frontend tag. With no positional args,
# smoke must derive both effective component IDs from the persisted release env.
kill "$server_pid"
wait "$server_pid" 2>/dev/null || true
server_pid=''
mkdir -p "$test_root/split/tools/release" "$test_root/split/tools/ops"
cp "$source_root/tools/release/smoke.sh" "$source_root/tools/release/lib.sh" "$test_root/split/tools/release/"
cp "$source_root/tools/release/tests/fixtures/fake-admin-artifact.py" "$test_root/split/tools/release/verify-admin-artifact.py"
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
  SMOKE_ADMIN_IMAGE_ID="sha256:$(printf '%064d' 7)" \
  "$test_root/split/tools/release/smoke.sh"
if TEST_BAD_ADMIN_ARTIFACT=true SMOKE_BASE_URL="http://127.0.0.1:$split_port" \
    SMOKE_ADMIN_IMAGE_ID="sha256:$(printf '%064d' 7)" \
    "$test_root/split/tools/release/smoke.sh" > "$test_root/bad-admin.log" 2>&1; then
  echo 'Smoke accepted an obsolete public admin artifact' >&2
  exit 1
fi
grep -Fq 'Obsolete admin artifact fixture' "$test_root/bad-admin.log"
echo 'Release readiness retry and timeout OK'
