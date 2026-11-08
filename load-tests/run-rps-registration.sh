#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
: "${BASE_URL:?Set an explicit local disposable backend origin}"
: "${RPS_FIXTURE_FILE:?Set the private generated fixture path}"
[[ "${ISOLATED_RPS_LOAD_TEST:-}" == DISPOSABLE_DATABASE_NO_SMS ]] || {
  echo 'ERROR: set ISOLATED_RPS_LOAD_TEST=DISPOSABLE_DATABASE_NO_SMS for an isolated DB with all outbound SMS/integrations disabled' >&2
  exit 1
}
python3 - "$RPS_FIXTURE_FILE" <<'PY'
import os, pathlib, stat, sys
p = pathlib.Path(sys.argv[1]); s = p.lstat()
assert stat.S_ISREG(s.st_mode) and not p.is_symlink() and s.st_uid == os.getuid() and s.st_mode & 0o077 == 0, 'Fixture must be an owner-only regular file'
PY

MODE="${MODE:-register}"
[[ "$MODE" == register || "$MODE" == login || "$MODE" == sessions ]] || { echo 'ERROR: invalid MODE' >&2; exit 1; }
RESULT_DIR="${K6_RESULT_DIR:-$ROOT_DIR/load-tests/results}"
mkdir -p "$RESULT_DIR"
RUN_SUFFIX="$(date -u +%Y%m%d%H%M%S)-$$"
network_args=()
if [[ -n "${RPS_DOCKER_NETWORK:-}" ]]; then
  [[ "$RPS_DOCKER_NETWORK" =~ ^(container:)?[A-Za-z0-9_.-]+$ && "$RPS_DOCKER_NETWORK" != host ]] || {
    echo 'ERROR: RPS_DOCKER_NETWORK must name a disposable network/container, never the host network' >&2; exit 1
  }
  network_args+=(--network "$RPS_DOCKER_NETWORK")
fi
docker run --rm --read-only --tmpfs /tmp --user "$(id -u):$(id -g)" "${network_args[@]}" \
  -v "$ROOT_DIR/load-tests/k6:/scripts:ro" \
  -v "$RPS_FIXTURE_FILE:/run/rps-fixture.private.json:ro" \
  -v "$RESULT_DIR:/results" \
  -e BASE_URL="$BASE_URL" \
  -e K6_NO_USAGE_REPORT=true \
  -e ISOLATED_RPS_LOAD_TEST=DISPOSABLE_DATABASE_NO_SMS \
  -e RPS_FIXTURE_FILE=/run/rps-fixture.private.json \
  -e RPS_SUMMARY_FILE="/results/rps-${MODE}-${RUN_SUFFIX}.json" \
  -e MODE="$MODE" -e SCHEDULE="${SCHEDULE:-minute}" -e VUS="${VUS:-25}" -e LOGIN_ROUNDS="${LOGIN_ROUNDS:-1}" \
  -e DURATION="${DURATION:-2m}" \
  "${K6_IMAGE:-grafana/k6:0.54.0}" run /scripts/rps-registration.js
