#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(CDPATH='' cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

fail() {
  printf 'P0 policy failed: %s\n' "$*" >&2
  exit 1
}

runtime_paths=(
  .env.prod.example
  docker-compose.prod.yml
  admin-panel/backend/src/main
  admin-panel/frontend/src
  storefront/.env.example
  storefront/deploy
  storefront/scripts
  storefront/src
  builds/build_all.sh
  ios
)

if grep -R -n -- 'MEDUSA_ALLOW_INSECURE_LEGACY_HTTP' "${runtime_paths[@]}"; then
  fail "broad legacy cleartext Medusa bypass remains available"
fi
if grep -R -n -- '--no-strict\|P55D384HK5' ios builds/build_all.sh; then
  fail "personal-team or weakened Apple signing configuration remains"
fi
if grep -R -n -- 'epharm\.inkar\.kz:8060' lib android ios; then
  fail "mobile production runtime must not fall back to public cleartext HTTP"
fi
if grep -R -n -F -- '.csrf { it.disable() }' admin-panel/backend/src/main; then
  fail "backend must not disable CSRF globally; exempt only explicitly stateless API paths"
fi
grep -Fq 'dev-mode: ${OTP_DEV_MODE:false}' \
  admin-panel/backend/src/main/resources/application-prod.yml \
  || fail "production OTP must default to dev-mode=false"
grep -Fxq 'MEDUSA_ENABLED=false' .env.prod.example \
  || fail "the retired remote Medusa catalogue must remain disabled in production"
grep -Fxq 'MEDUSA_BASE_URL=' .env.prod.example \
  || fail "production must not retain an active retired Medusa origin"
grep -Fxq 'STOREFRONT_CATALOG_ENABLED=true' .env.prod.example \
  || fail "production must use the aptekasosklada.kz website catalogue"
grep -Fxq 'STOREFRONT_CATALOG_BASE_URL=https://www.aptekasosklada.kz' .env.prod.example \
  || fail "production catalogue must default to the authoritative website HTTPS origin"
if grep -Fq 'signingConfigs.debug' android/app/build.gradle; then
  fail "Android release configuration must not fall back to the debug key"
fi
if grep -R -n -E 'uses:[[:space:]]+[^[:space:]#]+@(main|master|v[0-9]+([.][0-9]+)*)' .github/workflows; then
  fail "GitHub Actions must be pinned to immutable commit SHAs"
fi

printf 'OK: P0 static configuration policy passed\n'
