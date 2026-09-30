#!/usr/bin/env bash
# Runs the real backend-only transaction against fake Docker; no Git checkout or network.
set -euo pipefail

source_root="$(cd "$(dirname "$0")/../../.." && pwd)"
test_root="$(mktemp -d "${TMPDIR:-/tmp}/epharm-backend-only.XXXXXXXX")"
trap 'rm -rf -- "$test_root"' EXIT
mkdir -p "$test_root/tools/release" "$test_root/tools/ops" "$test_root/bin" "$test_root/backup"
cp "$source_root/tools/release/"*.sh "$test_root/tools/release/"
cp "$source_root/tools/ops/lib.sh" "$test_root/tools/ops/lib.sh"
printf '# fixture compose\n' > "$test_root/docker-compose.prod.yml"
printf '# fixture Caddyfile\n' > "$test_root/Caddyfile"
printf 'POSTGRES_PASSWORD=fixture\nMERCH_TASKS_ENABLED=true\nMERCH_TASKS_STAFF_URL=https://crm.inkar.kz/staff\nMERCH_TASKS_BASE_URL=http://10.10.1.80:8080\nMERCH_TASKS_INTEGRATION_KEY=fixture-integration-key\nMERCH_TASKS_MAX_CONCURRENT=16\nMERCH_PORTAL_UPSTREAM=10.10.1.80:8080\n' > "$test_root/.env.prod"
printf 'RELEASE_ID=v0.1.20\nRELEASE_COMMIT=previous-marker\n' > "$test_root/.release.env"
printf 'verified fixture\n' > "$test_root/backup/epharm.dump"
cp "$test_root/.env.prod" "$test_root/backup/env.prod"
cp "$test_root/docker-compose.prod.yml" "$test_root/backup/docker-compose.prod.yml"
cp "$test_root/Caddyfile" "$test_root/backup/Caddyfile"
(printf 'backend image fixture\n' | gzip -c > "$test_root/backup/backend-v0.1.20.tar.gz")
(printf 'frontend image fixture\n' | gzip -c > "$test_root/backup/frontend-v0.1.22.tar.gz")
(cd "$test_root/backup" && shasum -a 256 epharm.dump env.prod docker-compose.prod.yml Caddyfile backend-v0.1.20.tar.gz frontend-v0.1.22.tar.gz > SHA256SUMS)

printf 'sha256:%064d\n' 2 > "$test_root/backend-image"
printf 'sha256:%064d\n' 3 > "$test_root/frontend-image"
cat > "$test_root/bin/docker" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
backend_old="sha256:$(printf '%064d' 2)"
backend_new="sha256:$(printf '%064d' 4)"
backend_fail="sha256:$(printf '%064d' 5)"
frontend="sha256:$(printf '%064d' 3)"
candidate_commit="$(printf 'a%.0s' {1..40})"
case "${1:-}:${2:-}" in
  inspect:--format)
    if [[ "${3:-}" == '{{range .Config.Env}}{{println .}}{{end}}' && "${4:-}" == epharm-caddy ]]; then
      echo 'MERCH_PORTAL_UPSTREAM=10.10.1.80:8080'
      exit 0
    fi
    if [[ "${3:-}" == '{{.Id}}' && "${4:-}" == epharm-frontend ]]; then
      echo fixture-frontend-container
      exit 0
    fi
    case "${4:-}" in
      epharm-backend) cat "$TEST_BACKEND_IMAGE_FILE" ;;
      epharm-frontend) cat "$TEST_FRONTEND_IMAGE_FILE" ;;
      *) exit 1 ;;
    esac
    ;;
  image:inspect)
    template="${4:-}"
    image="${5:-}"
    case "$template" in
      '{{.Id}}')
        case "$image" in
          epharm/backend:v0.1.20) echo "$backend_old" ;;
          epharm/backend:v0.1.21) echo "$backend_new" ;;
          epharm/backend:v0.1.23) echo "$backend_fail" ;;
          epharm/frontend:v0.1.22) echo "$frontend" ;;
          *) exit 1 ;;
        esac
        ;;
      *org.opencontainers.image.version*)
        printf '%s\n' "${image##*:}"
        ;;
      *org.opencontainers.image.revision*)
        case "$image" in
          epharm/frontend:*) echo learner-auth-sidebar-fix ;;
          *) echo "$candidate_commit" ;;
        esac
        ;;
      *) exit 1 ;;
    esac
    ;;
  image:save)
    printf 'fixture image archive: %s\n' "${3:-}"
    ;;
  exec:*)
    if [[ " $* " == *' pg_dump '* ]]; then
      printf 'fixture custom pg dump\n'
    elif [[ " $* " == *' pg_restore '* ]]; then
      :
    else
      exit 1
    fi
    ;;
  compose:*)
    action=''
    release_env=''
    previous=''
    for argument in "$@"; do
      if [[ "$previous" == env ]]; then release_env="$argument"; previous=''; continue; fi
      case "$argument" in
        --env-file) previous=env ;;
        config|up) action="$argument" ;;
      esac
    done
    case "$action" in
      config)
        python3 - "$release_env" <<'PY'
import json, sys
env = dict(line.strip().split('=', 1) for line in open(sys.argv[1]) if '=' in line)
tag = env['BACKEND_RELEASE_ID']
front = env['FRONTEND_RELEASE_ID']
print(json.dumps({'services': {
    'backend': {'image': 'epharm/backend:' + tag, 'environment': {
        'RELEASE_ID': tag, 'RELEASE_COMMIT': env['BACKEND_RELEASE_COMMIT'],
        'MERCH_TASKS_ENABLED': 'true', 'MERCH_TASKS_STAFF_URL': 'https://crm.inkar.kz/staff',
        'MERCH_TASKS_BASE_URL': 'http://10.10.1.80:8080',
        'MERCH_TASKS_INTEGRATION_KEY': 'fixture-integration-key',
        'MERCH_TASKS_MAX_CONCURRENT': '16'}},
    'frontend': {'image': 'epharm/frontend:' + front, 'build': {'args': {
        'VITE_RELEASE_COMMIT': env['FRONTEND_RELEASE_COMMIT']}}},
    'caddy': {'environment': {'MERCH_PORTAL_UPSTREAM': '10.10.1.80:8080'}}}}))
PY
        ;;
      up)
        grep -Fxq -- '--no-deps' <(printf '%s\n' "$@")
        grep -Fxq -- 'backend' <(printf '%s\n' "$@")
        [[ " $* " != *' frontend '* ]]
        tag="$(sed -n 's/^BACKEND_RELEASE_ID=//p' "$release_env" | tail -n 1)"
        if [[ -z "$tag" ]]; then tag="$(sed -n 's/^RELEASE_ID=//p' "$release_env" | tail -n 1)"; fi
        case "$tag" in
          v0.1.20) echo "$backend_old" > "$TEST_BACKEND_IMAGE_FILE" ;;
          v0.1.21) echo "$backend_new" > "$TEST_BACKEND_IMAGE_FILE" ;;
          v0.1.23) echo "$backend_fail" > "$TEST_BACKEND_IMAGE_FILE" ;;
          *) exit 1 ;;
        esac
        printf 'up:%s\n' "$tag" >> "$TEST_EVENTS"
        ;;
      *) exit 1 ;;
    esac
    ;;
  *) echo "Unexpected Docker call: $*" >&2; exit 1 ;;
esac
STUB
cat > "$test_root/bin/flock" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1" == -n && "$2" == 9 ]]
STUB
cat > "$test_root/bin/sha256sum" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == --status && "${2:-}" == -c ]]; then
  shasum -a 256 -c "$3" > /dev/null
else
  shasum -a 256 "$@"
fi
STUB
cat > "$test_root/bin/curl" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${TEST_BAD_CADDY_REDIRECT:-false}" == true ]]; then
  printf 'HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n'
else
  printf 'HTTP/1.1 302 Found\r\nLocation: https://crm.inkar.kz/staff?task=bridge-probe\r\nReferrer-Policy: no-referrer\r\nCache-Control: no-store\r\n\r\n'
fi
STUB
cat > "$test_root/tools/release/smoke.sh" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
[[ "$(sed -n 's/^BACKEND_RELEASE_ID=//p' "$TEST_WORKSPACE_ROOT/.release.env" | tail -n 1)" == "$1" ]] || {
  [[ "$(sed -n 's/^RELEASE_ID=//p' "$TEST_WORKSPACE_ROOT/.release.env" | tail -n 1)" == "$1" ]]
}
[[ "$2" == v0.1.22 ]]
grep -Fxq 'FRONTEND_RELEASE_ID=v0.1.22' "$TEST_WORKSPACE_ROOT/.release.env"
printf 'smoke:%s/%s\n' "$1" "$2" >> "$TEST_EVENTS"
[[ "${TEST_FAIL_SMOKE:-false}" != true || "$1" == v0.1.20 ]]
STUB
chmod +x "$test_root/bin/docker" "$test_root/bin/flock" "$test_root/bin/sha256sum" \
  "$test_root/bin/curl" "$test_root/tools/release/smoke.sh"
export TEST_WORKSPACE_ROOT="$test_root"
export TEST_BACKEND_IMAGE_FILE="$test_root/backend-image"
export TEST_FRONTEND_IMAGE_FILE="$test_root/frontend-image"
export TEST_EVENTS="$test_root/events"
: > "$TEST_EVENTS"
export PATH="$test_root/bin:$PATH"
commit="$(printf 'a%.0s' {1..40})"
candidate_image="sha256:$(printf '%064d' 4)"
failed_image="sha256:$(printf '%064d' 5)"
compose_sha="$(shasum -a 256 "$test_root/docker-compose.prod.yml" | awk '{print $1}')"

if "$test_root/tools/release/deploy-backend-only.sh" v0.1.21 "$commit" \
  "sha256:$(printf '%064d' 9)" v0.1.22 "$test_root/backup" "$compose_sha" >/dev/null 2>&1; then
  echo 'Backend-only deploy accepted a wrong image ID' >&2; exit 1
fi
[[ "$(cat "$test_root/backend-image")" == "sha256:$(printf '%064d' 2)" ]]
before_events="$(wc -l < "$TEST_EVENTS")"
if TEST_BAD_CADDY_REDIRECT=true "$test_root/tools/release/deploy-backend-only.sh" \
  v0.1.21 "$commit" "$candidate_image" v0.1.22 "$test_root/backup" "$compose_sha" >/dev/null 2>&1; then
  echo 'Backend-only deploy accepted a non-redirecting legacy staff route' >&2; exit 1
fi
[[ "$(wc -l < "$TEST_EVENTS")" == "$before_events" ]]
"$test_root/tools/release/deploy-backend-only.sh" v0.1.21 "$commit" \
  "$candidate_image" v0.1.22 "$test_root/backup" "$compose_sha"
grep -Fxq 'BACKEND_RELEASE_ID=v0.1.21' "$test_root/.release.env"
grep -Fxq 'FRONTEND_RELEASE_ID=v0.1.22' "$test_root/.release.env"
[[ "$(cat "$test_root/frontend-image")" == "sha256:$(printf '%064d' 3)" ]]
grep -Fxq 'up:v0.1.21' "$TEST_EVENTS"
grep -Fxq 'smoke:v0.1.21/v0.1.22' "$TEST_EVENTS"
transaction_dir="$(cat "$test_root/releases/backend-only/active-transaction")"
"$test_root/tools/release/rollback-backend-only.sh" "$transaction_dir" "$test_root/epharm-backend-only-backups"
grep -Fxq 'RELEASE_ID=v0.1.20' "$test_root/.release.env"
grep -Fxq 'FRONTEND_RELEASE_ID=v0.1.22' "$test_root/.release.env"
[[ "$(cat "$test_root/backend-image")" == "sha256:$(printf '%064d' 2)" ]]
[[ "$(cat "$test_root/frontend-image")" == "sha256:$(printf '%064d' 3)" ]]
grep -Fxq 'up:v0.1.20' "$TEST_EVENTS"

# A changed live secret config must stop before switching either image or env.
printf 'MERCH_TASKS_TIMEOUT_MS=8000\n' >> "$test_root/.env.prod"
before_events="$(wc -l < "$TEST_EVENTS")"
if "$test_root/tools/release/deploy-backend-only.sh" v0.1.23 "$commit" \
  "$failed_image" v0.1.22 "$test_root/backup" "$compose_sha" >/dev/null 2>&1; then
  echo 'Backend-only deploy ignored config drift after backup' >&2; exit 1
fi
[[ "$(wc -l < "$TEST_EVENTS")" -eq "$before_events" ]]
grep -Fxq 'RELEASE_ID=v0.1.20' "$test_root/.release.env"
[[ "$(cat "$test_root/backend-image")" == "sha256:$(printf '%064d' 2)" ]]
cp "$test_root/backup/env.prod" "$test_root/.env.prod"

# Simulate an existing older backend and a new candidate that fails smoke. The
# exact previous env file must be restored and only backend may be recreated.
printf 'RELEASE_ID=v0.1.20\nRELEASE_COMMIT=previous-marker\n' > "$test_root/.release.env"
printf 'sha256:%064d\n' 2 > "$test_root/backend-image"
if TEST_FAIL_SMOKE=true "$test_root/tools/release/deploy-backend-only.sh" v0.1.23 "$commit" \
  "$failed_image" v0.1.22 "$test_root/backup" "$compose_sha" >/dev/null 2>&1; then
  echo 'Backend-only deploy ignored failed smoke' >&2; exit 1
fi
grep -Fxq 'RELEASE_ID=v0.1.20' "$test_root/.release.env"
grep -Fxq 'FRONTEND_RELEASE_ID=v0.1.22' "$test_root/.release.env"
[[ "$(cat "$test_root/backend-image")" == "sha256:$(printf '%064d' 2)" ]]
[[ "$(cat "$test_root/frontend-image")" == "sha256:$(printf '%064d' 3)" ]]
grep -Fxq 'up:v0.1.23' "$TEST_EVENTS"
grep -Fxq 'up:v0.1.20' "$TEST_EVENTS"
grep -Fxq 'smoke:v0.1.20/v0.1.22' "$TEST_EVENTS"

created_backup_output="$("$test_root/tools/release/prepare-backend-only-backup.sh" \
  v0.1.20 v0.1.22 "$test_root/epharm-backend-only-backups")"
created_backup="${created_backup_output##* }"
[[ -s "$created_backup/epharm.dump" && -s "$created_backup/backend-v0.1.20.tar.gz" \
   && -s "$created_backup/frontend-v0.1.22.tar.gz" && -s "$created_backup/SHA256SUMS" ]]
(cd "$created_backup" && sha256sum --status -c SHA256SUMS)
echo 'Backend-only release/rollback contract OK'
