#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=tools/release/lib.sh
source "$SCRIPT_DIR/lib.sh"

release_id="${1:-$(read_release_env_value "$RELEASE_ROOT/.release.env" RELEASE_ID || true)}"
[[ -n "$release_id" ]] || { echo "ERROR: release id is missing" >&2; exit 1; }
base_url="${SMOKE_BASE_URL:-https://epharm.inkar.kz}"

# Compose can report the containers as started before the frontend listener and
# Caddy route are ready. A transient 502 at this point must not roll back a
# healthy release. Wait for both routes to serve the *candidate* release; a
# persistent error or stale version still fails the deployment within a bound.
readiness_wait_seconds="${RELEASE_READINESS_WAIT_SECONDS:-120}"
[[ "$readiness_wait_seconds" =~ ^[0-9]+$ ]] || {
  echo "ERROR: RELEASE_READINESS_WAIT_SECONDS must be a nonnegative integer" >&2
  exit 2
}
readiness_deadline=$((SECONDS + readiness_wait_seconds))
while true; do
  health="$(curl --fail --silent --connect-timeout 3 --max-time 10 "$base_url/api/health" || true)"
  frontend="$(curl --fail --silent --connect-timeout 3 --max-time 10 "$base_url/release.json" || true)"
  if python3 - "$release_id" "$health" "$frontend" <<'PY'
import json
import sys

expected = sys.argv[1]
try:
    health = json.loads(sys.argv[2])
    frontend = json.loads(sys.argv[3])
except (ValueError, TypeError):
    raise SystemExit(1)
if not isinstance(health, dict) or not isinstance(frontend, dict):
    raise SystemExit(1)
raise SystemExit(0 if health.get("status") == "ok"
    and health.get("releaseId") == expected
    and frontend.get("releaseId") == expected else 1)
PY
  then
    break
  fi
  if (( SECONDS >= readiness_deadline )); then
    echo "ERROR: backend and frontend did not report $release_id within ${readiness_wait_seconds}s" >&2
    exit 1
  fi
  sleep 2
done

# The first release with the durable Medusa read model needs one complete crawl
# before catalogue/search can be accepted. Subsequent releases reuse the persisted
# snapshot and pass immediately. Older rollback targets do not expose
# catalogSnapshot at all; their live catalogue/search checks below remain the
# compatibility gate instead of making an explicit rollback wait for 30 minutes.
# The pre-deploy Medusa smoke prevents waiting on a known-dead origin.
snapshot_wait_seconds="${CATALOG_SNAPSHOT_WAIT_SECONDS:-1800}"
snapshot_deadline=$((SECONDS + snapshot_wait_seconds))
while ! python3 - "$health" <<'PY'
import json
import sys
payload = json.loads(sys.argv[1])
if "catalogSnapshot" not in payload:
    raise SystemExit(0)
snapshot = payload.get("catalogSnapshot", {})
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
