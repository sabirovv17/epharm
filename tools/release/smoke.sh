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
catalog="$(curl --fail --silent --show-error --max-time 30 \
  "$base_url/api/mobile/catalog/products?limit=1&offset=0")"

python3 - "$release_id" "$health" "$frontend" "$catalog" <<'PY'
import json
import sys

expected = sys.argv[1]
health = json.loads(sys.argv[2])
frontend = json.loads(sys.argv[3])
catalog = json.loads(sys.argv[4])
if health.get("status") != "ok":
    raise SystemExit(f"backend health is not ok: {health}")
if health.get("releaseId") != expected:
    raise SystemExit(f"backend release mismatch: expected {expected}, got {health.get('releaseId')}")
if frontend.get("releaseId") != expected:
    raise SystemExit(f"frontend release mismatch: expected {expected}, got {frontend.get('releaseId')}")
items = catalog.get("items")
total = catalog.get("total")
sample = items[0] if isinstance(items, list) and items else None
if not isinstance(sample, dict) or not isinstance(sample.get("id"), str) or not sample["id"]:
    raise SystemExit(f"Medusa catalogue returned no sample product: {catalog}")
if not isinstance(total, int) or total <= 0:
    raise SystemExit(f"Medusa catalogue returned invalid total: {total!r}")
print(f"Smoke OK: backend/frontend report {expected}; Medusa total={total} sample={sample['id']}")
PY
