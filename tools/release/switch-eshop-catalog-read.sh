#!/usr/bin/env bash
# Stage 2 of the shop-catalogue cutover: switch one env flag and recreate backend only.
set -euo pipefail
umask 077

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=tools/release/lib.sh
source "$SCRIPT_DIR/lib.sh"

[[ $# -ge 2 && $# -le 4 ]] || {
  echo 'Usage: switch-eshop-catalog-read.sh <running-backend-tag> <running-frontend-tag> [--dry-run | --rollback [--dry-run]]' >&2
  exit 2
}
backend_tag="$1"
frontend_tag="$2"
action="${3:-}"
rollback_dry_run="${4:-}"
assert_release_id "$backend_tag"
assert_release_id "$frontend_tag"
[[ "$action" == '' || "$action" == --dry-run || "$action" == --rollback ]] \
  && [[ -z "$rollback_dry_run" || ( "$action" == --rollback && "$rollback_dry_run" == --dry-run ) ]] || {
  echo 'ERROR: invalid action; use --dry-run or --rollback [--dry-run]' >&2; exit 2;
}
for command in docker flock python3 curl; do require_command "$command"; done
readiness_wait_seconds="${CATALOG_READ_SWITCH_WAIT_SECONDS:-120}"
[[ "$readiness_wait_seconds" =~ ^[0-9]+$ ]] || {
  echo 'ERROR: CATALOG_READ_SWITCH_WAIT_SECONDS must be a nonnegative integer' >&2
  exit 2
}
for config in .env.prod .release.env docker-compose.prod.yml Caddyfile; do
  [[ -r "$RELEASE_ROOT/$config" ]] || { echo "ERROR: missing $config" >&2; exit 1; }
done

mkdir -p "$RELEASE_ROOT/releases/backend-only"
exec 9>"$RELEASE_ROOT/releases/backend-only/.deploy.lock"
flock -n 9 || { echo 'ERROR: another application backup/deploy is active' >&2; exit 1; }

[[ "$(read_release_env_value "$RELEASE_ROOT/.release.env" BACKEND_RELEASE_ID)" == "$backend_tag" \
   && "$(read_release_env_value "$RELEASE_ROOT/.release.env" FRONTEND_RELEASE_ID)" == "$frontend_tag" ]] || {
  echo 'ERROR: component release pins do not match the requested running tags' >&2; exit 1;
}
backend_image="$(docker inspect --format '{{.Image}}' epharm-backend)"
frontend_image="$(docker inspect --format '{{.Image}}' epharm-frontend)"
frontend_container="$(docker inspect --format '{{.Id}}' epharm-frontend)"
caddy_container="$(docker inspect --format '{{.Id}}' epharm-caddy)"
postgres_container="$(docker inspect --format '{{.Id}}' epharm-postgres)"
[[ "$backend_image" == "$(docker image inspect --format '{{.Id}}' "epharm/backend:$backend_tag")" \
   && "$frontend_image" == "$(docker image inspect --format '{{.Id}}' "epharm/frontend:$frontend_tag")" ]] || {
  echo 'ERROR: running application images differ from their pinned tags' >&2; exit 1;
}

env_file="$RELEASE_ROOT/.env.prod"
release_sha="$(sha256_file "$RELEASE_ROOT/.release.env")"
compose_sha="$(sha256_file "$RELEASE_ROOT/docker-compose.prod.yml")"
caddy_sha="$(sha256_file "$RELEASE_ROOT/Caddyfile")"
previous_sha="$(sha256_file "$env_file")"
candidate_tmp=''
transaction_dir=''
switched=false

verify_compose() {
  local input_env="$1" expected_read="$2"
  docker compose --env-file "$input_env" --env-file "$RELEASE_ROOT/.release.env" \
    -f "$RELEASE_ROOT/docker-compose.prod.yml" config --format json \
    | python3 -c 'import json,sys
s=json.load(sys.stdin)["services"]
b=s["backend"]; f=s["frontend"]; env=b["environment"]
want_backend,want_frontend,want_read=sys.argv[1:]
ok=(b["image"]=="epharm/backend:"+want_backend
    and f["image"]=="epharm/frontend:"+want_frontend
    and str(env.get("ESHOP_CATALOG_SYNC_ENABLED", "")).lower()=="true"
    and str(env.get("ESHOP_CATALOG_READ_ENABLED", "")).lower()==want_read)
sys.exit(0 if ok else 1)' "$backend_tag" "$frontend_tag" "$expected_read"
}

check_health() {
  local mode="$1" base_url="${SMOKE_BASE_URL:-https://epharm.inkar.kz}"
  curl --fail --silent --connect-timeout 3 --max-time 10 "$base_url/api/health" \
    | python3 -c 'import datetime,json,sys
try:
    h=json.load(sys.stdin)
    e=h["eshopCatalogSnapshot"]
    c=h["catalogSnapshot"]
    mode,tag=sys.argv[1:]
    good=h.get("status")=="ok" and h.get("releaseId")==tag
    good=good and c.get("ready") is True and c.get("products",0)>0
    if mode in ("preload", "cutover"):
        completed=datetime.datetime.fromisoformat(e["completedAt"].replace("Z", "+00:00"))
        age=(datetime.datetime.now(datetime.timezone.utc)-completed).total_seconds()
        good=good and e.get("syncEnabled") is True and e.get("ready") is True
        good=good and e.get("products",0)>0 and e.get("publishedProducts",0)>0
        good=good and 0 <= age <= 1800 and not e.get("lastError")
    if mode=="preload": good=good and e.get("readEnabled") is False
    elif mode=="cutover":
        good=good and e.get("readEnabled") is True and c.get("products")==e.get("products")
    elif mode=="site":
        good=good and e.get("readEnabled") is True and c.get("products")==e.get("products")
    else: good=good and e.get("readEnabled") is False
except (ValueError,KeyError,TypeError,AttributeError):
    good=False
sys.exit(0 if good else 1)' "$mode" "$backend_tag"
}

wait_health() {
  local mode="$1" deadline=$((SECONDS + readiness_wait_seconds))
  while ! check_health "$mode"; do
    (( SECONDS < deadline )) || return 1
    sleep 2
  done
}

# Rollback must remain verifiable even when the new exporter fails. The general
# release smoke intentionally requires its preloaded snapshot while SYNC=true.
legacy_catalog_smoke() {
  local base_url="${SMOKE_BASE_URL:-https://epharm.inkar.kz}" catalog sample search
  catalog="$(curl --fail --silent --show-error --max-time 15 \
    "$base_url/api/mobile/catalog/products?limit=1&offset=0")" || return 1
  sample="$(python3 - "$catalog" <<'PY'
import json, sys
body = json.loads(sys.argv[1])
items = body.get('items', [])
if not isinstance(items, list) or not items or not isinstance(body.get('total'), int) or body['total'] < 1:
    raise SystemExit(1)
item = items[0]
if not isinstance(item, dict) or not isinstance(item.get('id'), str) or not item['id']:
    raise SystemExit(1)
if not isinstance(item.get('name'), str) or not item['name'].strip():
    raise SystemExit(1)
print(item['name'])
PY
  )" || return 1
  search="$(curl --fail --silent --show-error --max-time 15 --get \
    --data-urlencode "q=$sample" --data-urlencode 'limit=50' --data-urlencode 'offset=0' \
    "$base_url/api/mobile/catalog/products")" || return 1
  python3 - "$catalog" "$search" <<'PY'
import json, sys
catalog, search = (json.loads(value) for value in sys.argv[1:])
sample_id = catalog['items'][0]['id']
raise SystemExit(0 if sample_id in [item.get('id') for item in search.get('items', [])
                                    if isinstance(item, dict)] else 1)
PY
}

verify_unchanged() {
  [[ "$(sha256_file "$RELEASE_ROOT/.release.env")" == "$release_sha" \
     && "$(sha256_file "$RELEASE_ROOT/docker-compose.prod.yml")" == "$compose_sha" \
     && "$(sha256_file "$RELEASE_ROOT/Caddyfile")" == "$caddy_sha" \
     && "$(docker inspect --format '{{.Image}}' epharm-backend)" == "$backend_image" \
     && "$(docker inspect --format '{{.Image}}' epharm-frontend)" == "$frontend_image" \
     && "$(docker inspect --format '{{.Id}}' epharm-frontend)" == "$frontend_container" \
     && "$(docker inspect --format '{{.Id}}' epharm-caddy)" == "$caddy_container" \
     && "$(docker inspect --format '{{.Id}}' epharm-postgres)" == "$postgres_container" ]]
}

transaction_root="$RELEASE_ROOT/releases/catalog-read-switch"
if [[ "$action" == --rollback ]]; then
  # The pointer is written only after a successful cutover. Check its owner,
  # private permissions, direct-child path, manifest, and exact one-line env
  # difference before any rollback mutation.
  transaction_info="$(python3 - "$transaction_root" "$env_file" "$backend_tag" "$frontend_tag" \
    "$release_sha" "$compose_sha" "$caddy_sha" "$backend_image" "$frontend_image" <<'PY'
import hashlib, os, pathlib, stat, sys
root, live = map(pathlib.Path, sys.argv[1:3])
expected = dict(zip(('BACKEND_RELEASE_ID', 'FRONTEND_RELEASE_ID', 'RELEASE_ENV_SHA256',
                     'COMPOSE_SHA256', 'CADDY_SHA256', 'BACKEND_IMAGE', 'FRONTEND_IMAGE'),
                    sys.argv[3:]))
def private(path, kind, mode):
    item = path.lstat()
    valid_type = stat.S_ISDIR(item.st_mode) if kind == 'directory' else stat.S_ISREG(item.st_mode)
    if not valid_type or item.st_uid != os.geteuid() or stat.S_IMODE(item.st_mode) != mode:
        raise ValueError('invalid private rollback artifact')
private(root, 'directory', 0o700)
pointer = root / 'active-transaction'
private(pointer, 'file', 0o600)
transaction = pathlib.Path(pointer.read_text(encoding='utf-8').rstrip('\n'))
if not transaction.is_absolute() or transaction.parent != root:
    raise ValueError('rollback pointer escapes transaction root')
private(transaction, 'directory', 0o700)
manifest_path = transaction / 'manifest'
saved = transaction / 'pre.env.prod'
private(manifest_path, 'file', 0o600)
private(saved, 'file', 0o600)
private(live, 'file', 0o600)
manifest = {}
for line in manifest_path.read_text(encoding='ascii').splitlines():
    key, value = line.split('=', 1)
    if key in manifest:
        raise ValueError('duplicate rollback manifest key')
    manifest[key] = value
if set(manifest) != set(expected) | {'PRE_ENV_SHA256', 'POST_ENV_SHA256'}:
    raise ValueError('rollback manifest fields differ')
if any(manifest[key] != value for key, value in expected.items()):
    raise ValueError('release config differs from rollback manifest')
old, current = saved.read_bytes(), live.read_bytes()
digest = lambda data: hashlib.sha256(data).hexdigest()
if digest(old) != manifest['PRE_ENV_SHA256'] or digest(current) != manifest['POST_ENV_SHA256']:
    raise ValueError('rollback env checksum mismatch')
lines = old.splitlines(keepends=True)
def only(key):
    matches = [i for i, line in enumerate(lines) if line.startswith(key + b'=')]
    if len(matches) != 1:
        raise ValueError('rollback env has duplicate or missing flag')
    return matches[0]
if lines[only(b'ESHOP_CATALOG_SYNC_ENABLED')].rstrip(b'\r\n') != b'ESHOP_CATALOG_SYNC_ENABLED=true':
    raise ValueError('rollback sync flag mismatch')
index = only(b'ESHOP_CATALOG_READ_ENABLED')
old_line = lines[index]
if old_line.rstrip(b'\r\n') != b'ESHOP_CATALOG_READ_ENABLED=false':
    raise ValueError('rollback read flag mismatch')
lines[index] = b'ESHOP_CATALOG_READ_ENABLED=true' + old_line[len(old_line.rstrip(b'\r\n')):]
if b''.join(lines) != current:
    raise ValueError('rollback env differs by more than the read flag')
if '\t' in str(transaction) or '\n' in str(transaction):
    raise ValueError('invalid rollback path')
print(f"{transaction}\t{manifest['PRE_ENV_SHA256']}\t{manifest['POST_ENV_SHA256']}")
PY
  )" || { echo 'ERROR: active cutover rollback artifact failed integrity checks' >&2; exit 1; }
  IFS=$'\t' read -r transaction_dir saved_sha current_sha <<< "$transaction_info"
  verify_compose "$env_file" true || { echo 'ERROR: live Compose no longer renders site reads' >&2; exit 1; }
  verify_compose "$transaction_dir/pre.env.prod" false || {
    echo 'ERROR: saved Compose no longer renders legacy reads' >&2; exit 1;
  }
  verify_unchanged
  if [[ "$rollback_dry_run" == --dry-run ]]; then
    echo 'Shop catalogue rollback dry-run passed; no application container or env changed'
    exit 0
  fi

  rollback_attempt_dir="$(mktemp -d "$transaction_root/rollback-$(date -u +%Y%m%dT%H%M%SZ)-XXXXXXXX")"
  cp -p "$env_file" "$rollback_attempt_dir/current.env.prod"
  chmod 600 "$rollback_attempt_dir/current.env.prod"
  [[ "$(sha256_file "$rollback_attempt_dir/current.env.prod")" == "$current_sha" ]] || {
    echo 'ERROR: current env backup failed checksum verification' >&2; exit 1;
  }
  rollback_started=false
  rollback_tmp=''
  # Invoked indirectly by the EXIT trap after a partial rollback.
  # shellcheck disable=SC2329
  rollback_after_exit() {
    local status=$? recovered=true recovery_tmp=''
    trap - EXIT
    if [[ "$rollback_started" == true && "$status" -ne 0 ]]; then
      echo 'ERROR: post-success rollback failed; restoring the previous site-read state' >&2
      recovery_tmp="$(mktemp "$RELEASE_ROOT/.env.catalog-recovery.XXXXXXXX")" || recovered=false
      if [[ "$recovered" == true ]]; then
        cp -p "$rollback_attempt_dir/current.env.prod" "$recovery_tmp" || recovered=false
        chmod 600 "$recovery_tmp" || recovered=false
        mv -f "$recovery_tmp" "$env_file" || recovered=false
      fi
      if [[ "$recovered" == true ]]; then
        compose_prod up -d --no-build --no-deps --force-recreate backend || recovered=false
        wait_health site || recovered=false
        verify_unchanged || recovered=false
        [[ "$(sha256_file "$env_file")" == "$current_sha" ]] || recovered=false
      fi
      if [[ "$recovered" == true ]]; then
        echo 'Previous site-read state restored and verified' >&2
      else
        echo "CRITICAL: rollback recovery could not be verified; inspect $rollback_attempt_dir" >&2
      fi
    fi
    rm -f -- "$rollback_tmp" "$recovery_tmp"
    exit "$status"
  }
  trap rollback_after_exit EXIT
  trap 'exit 1' INT TERM
  rollback_tmp="$(mktemp "$RELEASE_ROOT/.env.catalog-rollback.XXXXXXXX")"
  cp -p "$transaction_dir/pre.env.prod" "$rollback_tmp"
  chmod 600 "$rollback_tmp"
  [[ "$(sha256_file "$rollback_tmp")" == "$saved_sha" ]] || {
    echo 'ERROR: saved env copy failed checksum verification' >&2; exit 1;
  }
  rollback_started=true
  mv -f "$rollback_tmp" "$env_file"
  compose_prod up -d --no-build --no-deps --force-recreate backend
  wait_health legacy || { echo 'ERROR: legacy backend did not become healthy' >&2; exit 1; }
  legacy_catalog_smoke || { echo 'ERROR: legacy catalogue/search smoke failed' >&2; exit 1; }
  verify_unchanged
  [[ "$(sha256_file "$env_file")" == "$saved_sha" ]] || {
    echo 'ERROR: restored env differs from the verified backup' >&2; exit 1;
  }
  rollback_started=false
  mv -f "$transaction_root/active-transaction" "$rollback_attempt_dir/completed-active-transaction" || {
    echo 'WARNING: legacy reads are restored, but the active-transaction pointer could not be archived' >&2;
  }
  echo "Legacy catalogue reads restored; rollback record saved in $rollback_attempt_dir"
  exit 0
fi

candidate_tmp="$(mktemp "$RELEASE_ROOT/.env.catalog-read.XXXXXXXX")"
# Parse dotenv as bytes: exactly one read flag and one sync flag, preserving every
# other byte (including secrets and comments). The candidate stays mode 0600.
python3 - "$env_file" "$candidate_tmp" <<'PY' || {
import os, pathlib, stat, sys
source, candidate = map(pathlib.Path, sys.argv[1:])
entry = source.lstat()
if not stat.S_ISREG(entry.st_mode) or entry.st_uid != os.geteuid() or stat.S_IMODE(entry.st_mode) != 0o600:
    raise SystemExit(1)
lines = source.read_bytes().splitlines(keepends=True)
def only(key):
    matches = [i for i, line in enumerate(lines) if line.startswith(key + b'=')]
    if len(matches) != 1:
        raise SystemExit(1)
    return matches[0]
sync = only(b'ESHOP_CATALOG_SYNC_ENABLED')
read = only(b'ESHOP_CATALOG_READ_ENABLED')
if lines[sync].rstrip(b'\r\n') != b'ESHOP_CATALOG_SYNC_ENABLED=true':
    raise SystemExit(1)
old = lines[read]
if old.rstrip(b'\r\n') != b'ESHOP_CATALOG_READ_ENABLED=false':
    raise SystemExit(1)
ending = old[len(old.rstrip(b'\r\n')):]
lines[read] = b'ESHOP_CATALOG_READ_ENABLED=true' + ending
with candidate.open('wb') as output:
    output.write(b''.join(lines))
    output.flush()
    os.fsync(output.fileno())
os.chmod(candidate, 0o600)
PY
  echo 'ERROR: .env.prod must be an owner-matched mode-0600 file with SYNC=true and READ=false once each' >&2
  rm -f -- "$candidate_tmp"
  exit 1
}
candidate_sha="$(sha256_file "$candidate_tmp")"

rollback_on_exit() {
  local status=$? rollback_ok=true restore_tmp=''
  trap - EXIT
  if [[ "$switched" == true && "$status" -ne 0 ]]; then
    echo 'ERROR: catalogue read switch failed; restoring the exact previous env and backend' >&2
    restore_tmp="$(mktemp "$RELEASE_ROOT/.env.catalog-rollback.XXXXXXXX")" || rollback_ok=false
    if [[ "$rollback_ok" == true ]]; then
      cp -p "$transaction_dir/pre.env.prod" "$restore_tmp" || rollback_ok=false
      chmod 600 "$restore_tmp" || rollback_ok=false
      mv -f "$restore_tmp" "$env_file" || rollback_ok=false
    fi
    if [[ "$rollback_ok" == true ]]; then
      compose_prod up -d --no-build --no-deps --force-recreate backend || rollback_ok=false
      wait_health legacy || rollback_ok=false
      legacy_catalog_smoke || rollback_ok=false
      verify_unchanged || rollback_ok=false
      [[ "$(sha256_file "$env_file")" == "$previous_sha" ]] || rollback_ok=false
    fi
    if [[ "$rollback_ok" == true ]]; then
      echo 'Catalogue read flag rollback verified' >&2
    else
      echo "CRITICAL: catalogue read rollback could not be verified; inspect $transaction_dir" >&2
    fi
  fi
  rm -f -- "$candidate_tmp" "$restore_tmp"
  exit "$status"
}
trap rollback_on_exit EXIT
trap 'exit 1' INT TERM

verify_compose "$env_file" false || { echo 'ERROR: current Compose does not render preloaded site catalogue' >&2; exit 1; }
verify_compose "$candidate_tmp" true || { echo 'ERROR: candidate Compose does not render site reads' >&2; exit 1; }
check_health preload || { echo 'ERROR: site snapshot is not fresh and ready for cutover' >&2; exit 1; }
"$SCRIPT_DIR/smoke.sh" "$backend_tag" "$frontend_tag"
verify_unchanged
[[ "$(sha256_file "$env_file")" == "$previous_sha" ]] || {
  echo 'ERROR: .env.prod changed during preflight' >&2; exit 1;
}
if [[ "$action" == --dry-run ]]; then
  echo 'Shop catalogue read switch dry-run passed; no application container or env changed'
  exit 0
fi

mkdir -p "$transaction_root"
chmod 700 "$transaction_root"
transaction_dir="$(mktemp -d "$transaction_root/$(date -u +%Y%m%dT%H%M%SZ)-XXXXXXXX")"
cp -p "$env_file" "$transaction_dir/pre.env.prod"
chmod 600 "$transaction_dir/pre.env.prod"
[[ "$(sha256_file "$transaction_dir/pre.env.prod")" == "$previous_sha" ]] || {
  echo 'ERROR: saved env differs from the live configuration' >&2; exit 1;
}
manifest_tmp="$(mktemp "$transaction_dir/.manifest.XXXXXXXX")"
printf 'BACKEND_RELEASE_ID=%s\nFRONTEND_RELEASE_ID=%s\nRELEASE_ENV_SHA256=%s\nCOMPOSE_SHA256=%s\nCADDY_SHA256=%s\nBACKEND_IMAGE=%s\nFRONTEND_IMAGE=%s\nPRE_ENV_SHA256=%s\nPOST_ENV_SHA256=%s\n' \
  "$backend_tag" "$frontend_tag" "$release_sha" "$compose_sha" "$caddy_sha" \
  "$backend_image" "$frontend_image" "$previous_sha" "$candidate_sha" > "$manifest_tmp"
chmod 600 "$manifest_tmp"
mv -f "$manifest_tmp" "$transaction_dir/manifest"

switched=true
mv -f "$candidate_tmp" "$env_file"
[[ "$(sha256_file "$env_file")" == "$candidate_sha" ]] || {
  echo 'ERROR: env changed during atomic cutover' >&2; exit 1;
}
compose_prod up -d --no-build --no-deps --force-recreate backend
wait_health cutover || { echo 'ERROR: backend did not report ready site reads' >&2; exit 1; }
"$SCRIPT_DIR/smoke.sh" "$backend_tag" "$frontend_tag"
verify_unchanged
[[ "$(sha256_file "$env_file")" == "$candidate_sha" ]] || {
  echo 'ERROR: env changed after catalogue cutover' >&2; exit 1;
}
active_tmp="$(mktemp "$transaction_root/.active-transaction.XXXXXXXX")"
printf '%s\n' "$transaction_dir" > "$active_tmp"
chmod 600 "$active_tmp"
mv -f "$active_tmp" "$transaction_root/active-transaction"
echo "Shop catalogue reads enabled; previous env saved in $transaction_dir"
