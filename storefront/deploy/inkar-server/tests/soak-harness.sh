#!/usr/bin/env bash
set -Eeuo pipefail

TEST_DIR="$(mktemp -d)"
trap 'rm -rf -- "$TEST_DIR"' EXIT
TEST_SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
BUNDLE_DIR="$(CDPATH= cd -- "${TEST_SCRIPT_DIR}/.." && pwd)"
mkdir -p "${TEST_DIR}/state" "${TEST_DIR}/manifests" "${TEST_DIR}/bin"
cp -- "${BUNDLE_DIR}/release.env.example" "${TEST_DIR}/state/release.env"

cat >"${TEST_DIR}/bin/curl" <<'MOCK'
#!/usr/bin/env bash
set -Eeuo pipefail
output=
url=
method=GET
while (( $# > 0 )); do
  case "$1" in
    --output|-o) output="$2"; shift 2 ;;
    --write-out|-w|--connect-timeout|--max-time|--proto|-H) shift 2 ;;
    --request|-X) method="$2"; shift 2 ;;
    --silent|--show-error) shift ;;
    http://*|https://*) url="$1"; shift ;;
    *) shift ;;
  esac
done
[[ -n "$output" && -n "$url" ]]
printf '%s\t%s\n' "$method" "$url" >>"$CURL_LOG"
case "$url" in
  *'/api/health'*) printf '{"ok":true,"ready":true}' >"$output" ;;
  *'/api/catalog'*) printf '{"products":[{"id":"fixture"}],"pagination":{"page":1}}' >"$output" ;;
  *'/api/product/'*) printf '{"product":{"id":"fixture"}}' >"$output" ;;
  *'/catalog'*|*'/product/'*) printf '<!doctype html><html><body>fixture</body></html>' >"$output" ;;
  *) exit 22 ;;
esac
printf '200\t0.100\t64'
MOCK
chmod +x "${TEST_DIR}/bin/curl"

env -u SOAK_DURATION_SECONDS -u SOAK_INTERVAL_SECONDS -u SOAK_MAX_ITERATIONS \
  -u SOAK_CURL_MAX_TIME -u SOAK_CRITICAL_FAILURE_LIMIT -u SOAK_NO_SLEEP \
  SOAK_PLAN_ONLY=1 bash "${BUNDLE_DIR}/soak.sh" >"${TEST_DIR}/plan.out"
grep -q '^duration_seconds=21600$' "${TEST_DIR}/plan.out"
grep -q '^interval_seconds=300$' "${TEST_DIR}/plan.out"
grep -q '^max_iterations=73$' "${TEST_DIR}/plan.out"
grep -q '^scheduled_span_seconds=21600$' "${TEST_DIR}/plan.out"
grep -q '^covers_duration=true$' "${TEST_DIR}/plan.out"

export PATH="${TEST_DIR}/bin:${PATH}"
export CURL_LOG="${TEST_DIR}/curl.log"
export INKAR_STATE_ROOT="$TEST_DIR"
export SOAK_BASE_URL="https://store.test"
export SOAK_DURATION_SECONDS=60
export SOAK_INTERVAL_SECONDS=1
export SOAK_MAX_ITERATIONS=2
export SOAK_NO_SLEEP=1
: >"$CURL_LOG"

bash "${BUNDLE_DIR}/soak.sh" >"${TEST_DIR}/soak.out"
summary="$(find "${TEST_DIR}/manifests" -maxdepth 1 -type f -name 'soak-*.summary.txt' -print -quit)"
log="$(find "${TEST_DIR}/manifests" -maxdepth 1 -type f -name 'soak-*.tsv' -print -quit)"
[[ -f "$summary" && -f "$log" ]]
grep -q '^overall=pass$' "$summary"
awk -F '\t' '$1 == "shallow_health" { found = ($2 == 2 && $3 == 2 && $5 == 100 && $8 == "pass") } END { exit !found }' "$summary"
[[ "$(wc -l <"$CURL_LOG")" -eq 14 ]]
if grep -qv $'^GET\thttps://store\\.test/' "$CURL_LOG"; then
  printf 'soak attempted a non-GET or unexpected origin\n' >&2
  exit 1
fi
if grep -Eqi '/(checkout|cart|orders|admin|api/customer|api/cdp)' "$CURL_LOG"; then
  printf 'soak attempted a mutating or private customer path\n' >&2
  exit 1
fi

printf 'soak harness passed\n'
