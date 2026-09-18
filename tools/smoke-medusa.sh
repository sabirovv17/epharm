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

medusa_origin="${MEDUSA_BASE_URL%/}"
if [[ "$MEDUSA_BASE_URL" =~ ^https://[A-Za-z0-9.-]+(:[0-9]+)?/?$ ]]; then
  transport="HTTPS"
elif [[ "$medusa_origin" == "http://78.140.246.238:9000" ]]; then
  transport="pinned legacy HTTP"
else
  die "MEDUSA_BASE_URL must be an HTTPS origin or the pinned legacy origin http://78.140.246.238:9000"
fi
response_file="$(mktemp -t epharm-medusa-smoke.XXXXXX)"
trap 'rm -f -- "$response_file"' EXIT

curl --fail-with-body --silent --show-error \
  --connect-timeout "${MEDUSA_SMOKE_CONNECT_TIMEOUT_SEC:-3}" \
  --max-time "${MEDUSA_SMOKE_TIMEOUT_SEC:-20}" \
  --header 'accept: application/json' \
  --header "x-publishable-api-key: ${MEDUSA_PUBLISHABLE_KEY}" \
  --get "${medusa_origin}/store/products" \
  --data-urlencode "sales_channel_id=${MEDUSA_SALES_CHANNEL_ID}" \
  --data-urlencode "region_id=${MEDUSA_REGION_ID}" \
  --data-urlencode 'fields=id' \
  --data-urlencode 'limit=1' \
  --output "$response_file"

jq -e '
  (.products | type == "array") and
  (.count | type == "number") and
  ((.products | length) == 0 or (.products[0].id | type == "string" and length > 0))
' "$response_file" >/dev/null || die "Store API response does not match the expected Medusa catalog contract"

product_count="$(jq -r '.count' "$response_file")"
sample_product_id="$(jq -r '.products[0].id // "none"' "$response_file")"
printf 'OK: Medusa Store API reachable (%s); count=%s sample=%s\n' \
  "$transport" "$product_count" "$sample_product_id"
