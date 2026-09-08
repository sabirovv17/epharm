#!/usr/bin/env bash
set -Eeuo pipefail

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

need() {
  command -v "$1" >/dev/null 2>&1 || die "required command is missing: $1"
}

require_env() {
  local name="$1" value="${!1:-}"
  [[ -n "$value" ]] || die "$name is required"
  [[ "$value" != *$'\n'* && "$value" != *$'\r'* ]] || die "$name must be a single line"
}

need curl
need jq
require_env MEDUSA_BASE_URL
require_env MEDUSA_PUBLISHABLE_KEY
require_env MEDUSA_SALES_CHANNEL_ID
require_env MEDUSA_REGION_ID

[[ "$MEDUSA_BASE_URL" =~ ^https://[A-Za-z0-9.-]+(:[0-9]+)?/?$ ]] \
  || die "MEDUSA_BASE_URL must be an HTTPS origin without credentials, path, query or fragment"

medusa_origin="${MEDUSA_BASE_URL%/}"
response_file="$(mktemp -t epharm-medusa-smoke.XXXXXX)"
trap 'rm -f -- "$response_file"' EXIT

curl --fail-with-body --silent --show-error \
  --connect-timeout "${MEDUSA_SMOKE_CONNECT_TIMEOUT_SEC:-3}" \
  --max-time "${MEDUSA_SMOKE_TIMEOUT_SEC:-10}" \
  --header 'accept: application/json' \
  --header "x-publishable-api-key: ${MEDUSA_PUBLISHABLE_KEY}" \
  --get "${medusa_origin}/store/products" \
  --data-urlencode "sales_channel_id=${MEDUSA_SALES_CHANNEL_ID}" \
  --data-urlencode "region_id=${MEDUSA_REGION_ID}" \
  --data-urlencode 'limit=1' \
  --output "$response_file"

jq -e '
  (.products | type == "array") and
  (.count | type == "number") and
  ((.products | length) == 0 or (.products[0].id | type == "string" and length > 0))
' "$response_file" >/dev/null || die "Store API response does not match the expected Medusa catalog contract"

product_count="$(jq -r '.count' "$response_file")"
sample_product_id="$(jq -r '.products[0].id // "none"' "$response_file")"
printf 'OK: Medusa Store API reachable over HTTPS; count=%s sample=%s\n' \
  "$product_count" "$sample_product_id"
