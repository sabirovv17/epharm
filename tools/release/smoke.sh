#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=tools/release/lib.sh
source "$SCRIPT_DIR/lib.sh"

release_id="${1:-$(read_release_env_value "$RELEASE_ROOT/.release.env" RELEASE_ID || true)}"
[[ -n "$release_id" ]] || { echo "ERROR: release id is missing" >&2; exit 1; }
base_url="${SMOKE_BASE_URL:-https://epharm.inkar.kz}"

health="$(curl --fail --silent --show-error --max-time 15 "$base_url/api/health")"
frontend="$(curl --fail --silent --show-error --max-time 15 "$base_url/release.json")"

python3 - "$release_id" "$health" "$frontend" <<'PY'
import json
import sys

expected = sys.argv[1]
health = json.loads(sys.argv[2])
frontend = json.loads(sys.argv[3])
if health.get("status") != "ok":
    raise SystemExit(f"backend health is not ok: {health}")
if health.get("releaseId") != expected:
    raise SystemExit(f"backend release mismatch: expected {expected}, got {health.get('releaseId')}")
if frontend.get("releaseId") != expected:
    raise SystemExit(f"frontend release mismatch: expected {expected}, got {frontend.get('releaseId')}")
print(f"Smoke OK: backend and frontend report {expected}")
PY
