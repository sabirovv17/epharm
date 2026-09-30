#!/usr/bin/env bash
set -euo pipefail

bundle_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
tmp_dir="$(mktemp -d)"
trap 'rm -r -- "$tmp_dir"' EXIT
umask 077
bash -n "$bundle_dir/deploy.sh"

# CI uses a disposable certificate; the production wildcard key is never in Git.
openssl req -x509 -newkey rsa:2048 -nodes -days 1 \
  -subj '/CN=*.inkar.kz' -addext 'subjectAltName=DNS:*.inkar.kz,DNS:inkar.kz' \
  -keyout "$tmp_dir/crm.key" -out "$tmp_dir/crm.fullchain.pem" >/dev/null 2>&1

docker run --rm --network none \
  -e SITE_DOMAIN=inkeshopapteka.inkar.kz -e SERVER_IP=10.10.1.80 \
  -v "$bundle_dir/Caddyfile.http:/etc/caddy/Caddyfile:ro" \
  caddy:2.10-alpine caddy validate --config /etc/caddy/Caddyfile >/dev/null

docker run --rm --network none \
  -e SITE_DOMAIN=inkeshopapteka.inkar.kz -e SERVER_IP=10.10.1.80 \
  -v "$bundle_dir/Caddyfile.crm-internal-tls:/etc/caddy/Caddyfile:ro" \
  -v "$tmp_dir/crm.fullchain.pem:/etc/caddy/tls/crm.fullchain.pem:ro" \
  -v "$tmp_dir/crm.key:/etc/caddy/tls/crm.key:ro" \
  caddy:2.10-alpine caddy validate --config /etc/caddy/Caddyfile >/dev/null

export SITE_DOMAIN=inkeshopapteka.inkar.kz SERVER_IP=10.10.1.80
export DATA_ROOT="$tmp_dir/data" CADDYFILE_PATH="$bundle_dir/Caddyfile.http"
export APP_ENV_FILE="$bundle_dir/app.env.example"
export POSTGRES_ENV_FILE="$bundle_dir/postgres.env.example"
export ACTIVE_WEB_UPSTREAM=web-blue:3000
export DEPLOY_IMAGE=example.invalid/inkar:test
export WEB_BLUE_IMAGE=example.invalid/inkar:test
export WEB_GREEN_IMAGE=example.invalid/inkar:test

base_compose=(docker compose -f "$bundle_dir/compose.yml")
tls_compose=(docker compose -f "$bundle_dir/compose.yml" -f "$bundle_dir/compose.crm-internal-tls.yml")
"${tls_compose[@]}" config --quiet
test "$(grep -c 'create_host_path: false' "$bundle_dir/compose.crm-internal-tls.yml")" -eq 3

# The override may change only the edge service, not the shop, database or jobs.
base_other="$("${base_compose[@]}" config --format json | jq -Sc 'del(.services.edge)')"
tls_other="$("${tls_compose[@]}" config --format json | jq -Sc 'del(.services.edge)')"
test "$base_other" = "$tls_other"

"${tls_compose[@]}" config --format json | jq -e '
  .services.edge as $edge |
  ([ $edge.volumes[] | select(.target == "/etc/caddy/Caddyfile") ] | length) == 1 and
  ([ $edge.volumes[] | select(.target == "/etc/caddy/Caddyfile" and (.source | endswith("/Caddyfile.crm-internal-tls")) and .read_only and .bind.create_host_path != true) ] | length) == 1 and
  ([ $edge.volumes[] | select(.target == "/etc/caddy/tls/crm.fullchain.pem" and .read_only and .bind.create_host_path != true) ] | length) == 1 and
  ([ $edge.volumes[] | select(.target == "/etc/caddy/tls/crm.key" and .read_only and .bind.create_host_path != true) ] | length) == 1 and
  ([ $edge.ports[] | select(.published == "80") ] | length) == 1 and
  ([ $edge.ports[] | select(.published == "443") ] | length) == 1
' >/dev/null

# Exercise the real deploy.sh state transitions without touching Docker,
# networking, production files or the live edge.
export INKAR_STATE_ROOT="$tmp_dir/state" INKAR_DEPLOY_LIBRARY_ONLY=1
mkdir -p "$INKAR_STATE_ROOT/state" "$INKAR_STATE_ROOT/config"
cp "$bundle_dir/release.env.example" "$INKAR_STATE_ROOT/state/release.env"
cp "$bundle_dir/app.env.example" "$INKAR_STATE_ROOT/config/app.env"
cp "$bundle_dir/postgres.env.example" "$INKAR_STATE_ROOT/config/postgres.env"
# shellcheck source=../deploy.sh
source "$bundle_dir/deploy.sh"
docker() {
  if [[ "$1" == compose && "$*" == *'up -d --no-deps --force-recreate edge'* ]]; then
    if [[ "$*" == *'compose.crm-internal-tls.yml'* ]]; then
      saw_tls_compose=true
    else
      saw_http_compose=true
    fi
  fi
  return 0
}
curl() {
  if [[ "${fail_http_once:-false}" == true && "$*" == *'http://127.0.0.1'* ]]; then
    fail_http_once=false
    return 1
  fi
  if [[ "${fail_https_probe:-false}" == true && "$*" == *'https://crm.inkar.kz'* ]]; then
    return 1
  fi
  return 0
}
sleep() { :; }

saw_tls_compose=false saw_http_compose=false
enable_crm_internal_tls
test "$(state_value CRM_INTERNAL_TLS_ENABLED)" = true
test "$saw_tls_compose" = true
disable_crm_internal_tls
test "$(state_value CRM_INTERNAL_TLS_ENABLED)" = false
test "$saw_http_compose" = true

# A failed HTTP disable must recreate AND health-check the previous HTTPS edge.
enable_crm_internal_tls
if (fail_http_once=true fail_https_probe=true; disable_crm_internal_tls) >"$tmp_dir/rollback.log" 2>&1; then
  printf 'Expected failed HTTP rollback to return a nonzero status\n' >&2
  exit 1
fi
grep -q 'HTTPS edge was recreated but its health probe failed' "$tmp_dir/rollback.log"
test "$(state_value CRM_INTERNAL_TLS_ENABLED)" = true
disable_crm_internal_tls
test "$(state_value CRM_INTERNAL_TLS_ENABLED)" = false

if (fail_https_probe=true; enable_crm_internal_tls) >/dev/null 2>&1; then
  printf 'Expected failed CRM HTTPS probe to trigger rollback\n' >&2
  exit 1
fi
test "$(state_value CRM_INTERNAL_TLS_ENABLED)" = false

set_state TLS_MODE tls
if (enable_crm_internal_tls) >/dev/null 2>&1; then
  printf 'Expected shop ACME TLS mode to reject CRM internal TLS\n' >&2
  exit 1
fi
test "$(state_value CRM_INTERNAL_TLS_ENABLED)" = false

printf 'CRM internal TLS Caddy and Compose contracts passed\n'
