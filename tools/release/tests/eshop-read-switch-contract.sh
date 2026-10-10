#!/usr/bin/env bash
# The real stage-2 transaction against fake Docker/HTTP; never contacts production.
set -euo pipefail
umask 077

source_root="$(cd "$(dirname "$0")/../../.." && pwd)"
test_root="$(mktemp -d "${TMPDIR:-/tmp}/epharm-eshop-read-switch.XXXXXXXX")"
trap 'rm -rf -- "$test_root"' EXIT
mkdir -p "$test_root/tools/release" "$test_root/tools/ops" "$test_root/bin"
cp "$source_root/tools/release/switch-eshop-catalog-read.sh" \
  "$source_root/tools/release/lib.sh" "$test_root/tools/release/"
cp "$source_root/tools/ops/lib.sh" "$test_root/tools/ops/lib.sh"
printf '# reviewed compose\n' > "$test_root/docker-compose.prod.yml"
printf '# reviewed Caddy\n' > "$test_root/Caddyfile"
printf 'SECRET=keep every byte # including spaces\nESHOP_CATALOG_SYNC_ENABLED=true\nESHOP_CATALOG_READ_ENABLED=false\n' \
  > "$test_root/.env.prod"
printf 'BACKEND_RELEASE_ID=v0.1.25\nFRONTEND_RELEASE_ID=v0.1.25\n' > "$test_root/.release.env"
chmod 600 "$test_root/.env.prod" "$test_root/.release.env"
cp -p "$test_root/.env.prod" "$test_root/original.env"

cat > "$test_root/bin/docker" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
backend_id="sha256:$(printf '%064d' 1)"
frontend_id="sha256:$(printf '%064d' 2)"
case "${1:-}:${2:-}" in
  inspect:--format)
    case "${3:-}:${4:-}" in
      '{{.Image}}:epharm-backend') echo "$backend_id" ;;
      '{{.Image}}:epharm-frontend') echo "$frontend_id" ;;
      '{{.Id}}:epharm-frontend') echo frontend-container-unchanged ;;
      '{{.Id}}:epharm-caddy') echo caddy-container-unchanged ;;
      '{{.Id}}:epharm-postgres') echo postgres-container-unchanged ;;
      *) exit 1 ;;
    esac ;;
  image:inspect)
    [[ "${4:-}" == '{{.Id}}' ]]
    case "${5:-}" in
      epharm/backend:v0.1.25) echo "$backend_id" ;;
      epharm/frontend:v0.1.25) echo "$frontend_id" ;;
      *) exit 1 ;;
    esac ;;
  compose:*)
    action='' env_file='' release_file='' previous=''
    for arg in "$@"; do
      if [[ "$previous" == env ]]; then
        if [[ -z "$env_file" ]]; then env_file="$arg"; else release_file="$arg"; fi
        previous=''
        continue
      fi
      case "$arg" in
        --env-file) previous=env ;;
        config|up) action="$arg" ;;
      esac
    done
    [[ -n "$env_file" && -n "$release_file" ]]
    case "$action" in
      config)
        python3 - "$env_file" "$release_file" <<'PY'
import json,sys
env=dict(line.rstrip('\n').split('=',1) for line in open(sys.argv[1]) if '=' in line)
pins=dict(line.rstrip('\n').split('=',1) for line in open(sys.argv[2]) if '=' in line)
if __import__('os').environ.get('TEST_BAD_RENDER') == 'true':
    env['ESHOP_CATALOG_READ_ENABLED']='broken'
print(json.dumps({'services': {
    'backend': {'image': 'epharm/backend:'+pins['BACKEND_RELEASE_ID'],
                'environment': {'ESHOP_CATALOG_SYNC_ENABLED': env['ESHOP_CATALOG_SYNC_ENABLED'],
                                'ESHOP_CATALOG_READ_ENABLED': env['ESHOP_CATALOG_READ_ENABLED']}},
    'frontend': {'image': 'epharm/frontend:'+pins['FRONTEND_RELEASE_ID']}}}))
PY
        ;;
      up)
        [[ " $* " == *' --no-build --no-deps --force-recreate backend '* ]]
        [[ " $* " != *' frontend '* && " $* " != *' caddy '* && " $* " != *' postgres '* ]]
        read_flag="$(sed -n 's/^ESHOP_CATALOG_READ_ENABLED=//p' "$env_file")"
        printf 'up:%s:backend\n' "$read_flag" >> "$TEST_EVENTS"
        if [[ "$read_flag" == true && "${TEST_FAIL_UP:-false}" == true ]]; then exit 1; fi
        ;;
      *) exit 1 ;;
    esac ;;
  *) echo "Unexpected Docker call: $*" >&2; exit 1 ;;
esac
STUB
cat > "$test_root/bin/flock" <<'STUB'
#!/usr/bin/env bash
[[ "$1" == -n && "$2" == 9 && "${TEST_LOCKED:-false}" != true ]]
STUB
cat > "$test_root/bin/curl" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
case " $* " in
  *'/api/health'*)
    python3 - "$TEST_WORKSPACE_ROOT/.env.prod" <<'PY'
import datetime,json,os,sys
env=dict(line.rstrip('\n').split('=',1) for line in open(sys.argv[1]) if '=' in line)
read=env['ESHOP_CATALOG_READ_ENABLED']=='true'
now=datetime.datetime.now(datetime.timezone.utc)
if os.environ.get('TEST_HEALTH_STALE')=='true': now-=datetime.timedelta(hours=2)
if read and os.environ.get('TEST_HEALTH_BAD_CUTOVER')=='true': read=False
status='degraded' if not read and os.environ.get('TEST_FAIL_LEGACY_HEALTH')=='true' else 'ok'
print(json.dumps({'status':status,'releaseId':'v0.1.25',
    'catalogSnapshot': {'ready':True,'products':29326 if read else 28503},
    'eshopCatalogSnapshot': {'syncEnabled':True,'readEnabled':read,
        'ready':True,'products':29326,'publishedProducts':8787,
        'completedAt':now.isoformat(),'lastError':None}}))
PY
    ;;
  *'/api/mobile/catalog/products'*)
    if [[ " $* " == *' --data-urlencode '* ]]; then
      if [[ "${TEST_FAIL_LEGACY_SEARCH:-false}" == true ]]; then
        printf '%s\n' '{"items":[],"total":0}'
      else
        printf '%s\n' '{"items":[{"id":"prod_fixture","name":"Test product"}],"total":1}'
      fi
    else
      printf '%s\n' '{"items":[{"id":"prod_fixture","name":"Test product"}],"total":1}'
    fi ;;
  *) exit 1 ;;
esac
STUB
cat > "$test_root/tools/release/smoke.sh" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1" == v0.1.25 && "$2" == v0.1.25 ]]
flag="$(sed -n 's/^ESHOP_CATALOG_READ_ENABLED=//p' "$TEST_WORKSPACE_ROOT/.env.prod")"
printf 'smoke:%s\n' "$flag" >> "$TEST_EVENTS"
[[ "$flag" != true || "${TEST_FAIL_SMOKE_CUTOVER:-false}" != true ]]
STUB
chmod +x "$test_root/bin/docker" "$test_root/bin/flock" "$test_root/bin/curl" \
  "$test_root/tools/release/smoke.sh" "$test_root/tools/release/switch-eshop-catalog-read.sh"
export TEST_WORKSPACE_ROOT="$test_root" TEST_EVENTS="$test_root/events"
export PATH="$test_root/bin:$PATH"
: > "$TEST_EVENTS"
switch=("$test_root/tools/release/switch-eshop-catalog-read.sh" v0.1.25 v0.1.25)

"${switch[@]}" --dry-run
cmp -s "$test_root/original.env" "$test_root/.env.prod"
[[ ! -s "$TEST_EVENTS" || "$(cat "$TEST_EVENTS")" == smoke:false ]]
[[ ! -d "$test_root/releases/catalog-read-switch" ]]

: > "$TEST_EVENTS"
if TEST_BAD_RENDER=true "${switch[@]}" >/dev/null 2>&1; then
  echo 'Accepted a candidate Compose model without site reads' >&2; exit 1
fi
cmp -s "$test_root/original.env" "$test_root/.env.prod"
[[ ! -s "$TEST_EVENTS" ]]
if TEST_HEALTH_STALE=true "${switch[@]}" >/dev/null 2>&1; then
  echo 'Accepted a stale site snapshot' >&2; exit 1
fi
cmp -s "$test_root/original.env" "$test_root/.env.prod"
[[ ! -s "$TEST_EVENTS" ]]
if TEST_LOCKED=true "${switch[@]}" >/dev/null 2>&1; then
  echo 'Ignored the shared application release lock' >&2; exit 1
fi
[[ ! -s "$TEST_EVENTS" ]]

"${switch[@]}"
grep -Fxq 'ESHOP_CATALOG_READ_ENABLED=true' "$test_root/.env.prod"
grep -Fxq 'SECRET=keep every byte # including spaces' "$test_root/.env.prod"
[[ "$(stat -f '%Lp' "$test_root/.env.prod" 2>/dev/null || stat -c '%a' "$test_root/.env.prod")" == 600 ]]
transaction_dir="$(cat "$test_root/releases/catalog-read-switch/active-transaction")"
cmp -s "$test_root/original.env" "$transaction_dir/pre.env.prod"
[[ "$(cat "$TEST_EVENTS")" == $'smoke:false\nup:true:backend\nsmoke:true' ]]

: > "$TEST_EVENTS"
"${switch[@]}" --rollback --dry-run
grep -Fxq 'ESHOP_CATALOG_READ_ENABLED=true' "$test_root/.env.prod"
[[ ! -s "$TEST_EVENTS" ]]
[[ -f "$test_root/releases/catalog-read-switch/active-transaction" ]]

cp -p "$transaction_dir/pre.env.prod" "$test_root/clean-pre.env.prod"
printf '# tampered\n' >> "$transaction_dir/pre.env.prod"
if "${switch[@]}" --rollback --dry-run >/dev/null 2>&1; then
  echo 'Rollback accepted a tampered saved env' >&2; exit 1
fi
[[ ! -s "$TEST_EVENTS" ]]
mv -f "$test_root/clean-pre.env.prod" "$transaction_dir/pre.env.prod"

: > "$TEST_EVENTS"
if CATALOG_READ_SWITCH_WAIT_SECONDS=0 TEST_FAIL_LEGACY_HEALTH=true \
  "${switch[@]}" --rollback >/dev/null 2>&1; then
  echo 'Rollback ignored unhealthy legacy reads' >&2; exit 1
fi
grep -Fxq 'ESHOP_CATALOG_READ_ENABLED=true' "$test_root/.env.prod"
[[ "$(cat "$TEST_EVENTS")" == $'up:false:backend\nup:true:backend' ]]
[[ -f "$test_root/releases/catalog-read-switch/active-transaction" ]]

: > "$TEST_EVENTS"
if TEST_FAIL_LEGACY_SEARCH=true "${switch[@]}" --rollback >/dev/null 2>&1; then
  echo 'Rollback ignored failed legacy search' >&2; exit 1
fi
grep -Fxq 'ESHOP_CATALOG_READ_ENABLED=true' "$test_root/.env.prod"
[[ "$(cat "$TEST_EVENTS")" == $'up:false:backend\nup:true:backend' ]]
[[ -f "$test_root/releases/catalog-read-switch/active-transaction" ]]

: > "$TEST_EVENTS"
"${switch[@]}" --rollback
cmp -s "$test_root/original.env" "$test_root/.env.prod"
[[ "$(cat "$TEST_EVENTS")" == 'up:false:backend' ]]
[[ ! -e "$test_root/releases/catalog-read-switch/active-transaction" ]]
rollback_records=("$test_root/releases/catalog-read-switch"/rollback-*/completed-active-transaction)
[[ "${#rollback_records[@]}" -eq 1 && -f "${rollback_records[0]}" ]]

: > "$TEST_EVENTS"
if TEST_FAIL_SMOKE_CUTOVER=true "${switch[@]}" >/dev/null 2>&1; then
  echo 'Failed cutover smoke did not trigger rollback' >&2; exit 1
fi
cmp -s "$test_root/original.env" "$test_root/.env.prod"
[[ "$(cat "$TEST_EVENTS")" == $'smoke:false\nup:true:backend\nsmoke:true\nup:false:backend' ]]

: > "$TEST_EVENTS"
if CATALOG_READ_SWITCH_WAIT_SECONDS=0 TEST_HEALTH_BAD_CUTOVER=true \
  "${switch[@]}" >/dev/null 2>&1; then
  echo 'Unhealthy site reads did not trigger rollback' >&2; exit 1
fi
cmp -s "$test_root/original.env" "$test_root/.env.prod"
[[ "$(cat "$TEST_EVENTS")" == $'smoke:false\nup:true:backend\nup:false:backend' ]]

: > "$TEST_EVENTS"
if TEST_FAIL_UP=true "${switch[@]}" >/dev/null 2>&1; then
  echo 'Partial backend recreation did not trigger rollback' >&2; exit 1
fi
cmp -s "$test_root/original.env" "$test_root/.env.prod"
[[ "$(cat "$TEST_EVENTS")" == $'smoke:false\nup:true:backend\nup:false:backend' ]]

chmod 644 "$test_root/.env.prod"
: > "$TEST_EVENTS"
if "${switch[@]}" >/dev/null 2>&1; then
  echo 'Accepted a non-private production env file' >&2; exit 1
fi
[[ ! -s "$TEST_EVENTS" ]]
echo 'Site-catalogue read-switch transaction contract OK'
