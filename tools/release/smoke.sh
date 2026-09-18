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

# The first release with the durable Medusa read model needs one complete crawl
# before catalogue/search can be accepted. Subsequent releases reuse the persisted
# snapshot and pass immediately. The pre-deploy Medusa smoke prevents waiting on a
# known-dead origin.
snapshot_wait_seconds="${CATALOG_SNAPSHOT_WAIT_SECONDS:-1800}"
snapshot_deadline=$((SECONDS + snapshot_wait_seconds))
while ! python3 - "$health" <<'PY'
import json
import sys
snapshot = json.loads(sys.argv[1]).get("catalogSnapshot", {})
raise SystemExit(0 if snapshot.get("ready") is True and snapshot.get("products", 0) > 0 else 1)
PY
do
  (( SECONDS < snapshot_deadline )) || {
    echo "ERROR: Medusa catalogue snapshot was not ready after ${snapshot_wait_seconds}s" >&2
    exit 1
  }
  sleep 10
  health="$(curl --fail --silent --show-error --max-time 15 "$base_url/api/health")"
done

catalog="$(curl --fail --silent --show-error --max-time 30 \
  "$base_url/api/mobile/catalog/products?limit=1&offset=0")"
sample_name="$(python3 - "$catalog" <<'PY'
import json
import sys
items = json.loads(sys.argv[1]).get("items", [])
name = items[0].get("name", "") if items else ""
if not isinstance(name, str) or not name.strip():
    raise SystemExit(f"Medusa catalogue sample has no searchable name: {items[:1]}")
print(name)
PY
)"
search="$(curl --fail --silent --show-error --max-time 15 --get \
  --data-urlencode "q=$sample_name" --data-urlencode 'limit=50' --data-urlencode 'offset=0' \
  "$base_url/api/mobile/catalog/products")"

python3 - "$release_id" "$health" "$frontend" "$catalog" "$search" <<'PY'
import json
import sys

expected = sys.argv[1]
health = json.loads(sys.argv[2])
frontend = json.loads(sys.argv[3])
catalog = json.loads(sys.argv[4])
search = json.loads(sys.argv[5])
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
search_ids = [item.get("id") for item in search.get("items", []) if isinstance(item, dict)]
if sample["id"] not in search_ids:
    raise SystemExit(f"Catalogue search did not return its exact sample product: {search}")
print(
    f"Smoke OK: backend/frontend report {expected}; "
    f"catalogue snapshot total={total} sample={sample['id']}; search=ok"
)
PY
