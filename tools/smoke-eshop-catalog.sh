#!/usr/bin/env bash
# Validate the private catalogue exporter without downloading a full generation.
set -euo pipefail
umask 077

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
[[ -n "${ESHOP_CATALOG_BASE_URL:-}" ]] || die 'ESHOP_CATALOG_BASE_URL is required'
[[ -n "${ESHOP_CATALOG_TOKEN:-}" ]] || die 'ESHOP_CATALOG_TOKEN is required'
[[ "$ESHOP_CATALOG_BASE_URL" =~ ^https://[A-Za-z0-9.-]+(:[0-9]+)?/?$ \
   || "$ESHOP_CATALOG_BASE_URL" == 'http://10.10.1.80:13105' ]] \
  || die 'catalogue exporter must use HTTPS or the pinned private .80 endpoint'

response_file="$(mktemp -t epharm-eshop-catalog.XXXXXX)"
trap 'rm -f "$response_file"' EXIT

curl --fail --silent --show-error \
  --connect-timeout "${ESHOP_CATALOG_SMOKE_CONNECT_TIMEOUT_SEC:-3}" \
  --max-time "${ESHOP_CATALOG_SMOKE_TIMEOUT_SEC:-15}" \
  --header "Authorization: Bearer ${ESHOP_CATALOG_TOKEN}" \
  --header 'Accept: application/json' \
  --output "$response_file" \
  "${ESHOP_CATALOG_BASE_URL%/}/health" \
  || die 'private catalogue exporter health request failed'

python3 - "$response_file" <<'PY' || die 'private catalogue exporter health contract invalid'
import json
import sys
import uuid

with open(sys.argv[1], encoding='utf-8') as source:
    value = json.load(source)
if value.get('status') != 'ok' or value.get('schemaVersion') != 1:
    raise SystemExit(1)
if not 1 <= value.get('catalogCount', 0) <= 1_000_000:
    raise SystemExit(1)
uuid.UUID(value['catalogRunId'])
if value.get('availabilityRunId') is not None:
    uuid.UUID(value['availabilityRunId'])
PY

printf 'OK: private catalogue exporter is ready\n'
