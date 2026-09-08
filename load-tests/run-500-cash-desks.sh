#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BASE_URL="${BASE_URL:-http://host.docker.internal:8080}"
RESULT_DIR="${K6_RESULT_DIR:-$ROOT_DIR/load-tests/results}"
K6_IMAGE="${K6_IMAGE:-grafana/k6:0.54.0}"
RUN_ID="${RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)-$$}"

if [[ "$BASE_URL" == *epharm.inkar.kz* && "${CONFIRM_PRODUCTION_LOAD:-}" != I_ACCEPT_PRODUCTION_IMPACT ]]; then
  echo 'ERROR: refusing production load without CONFIRM_PRODUCTION_LOAD=I_ACCEPT_PRODUCTION_IMPACT' >&2
  exit 1
fi

mkdir -p "$RESULT_DIR"
docker_args=(--rm -i -v "$RESULT_DIR:/results")
if [[ -n "${POSM_DEVICE_KEYS_FILE:-}" ]]; then
  [[ -r "$POSM_DEVICE_KEYS_FILE" ]] || {
    echo "ERROR: POSM_DEVICE_KEYS_FILE is not readable: $POSM_DEVICE_KEYS_FILE" >&2
    exit 1
  }
  docker_args+=(-v "$POSM_DEVICE_KEYS_FILE:/run/secrets/posm-device-keys:ro")
  docker_args+=(-e POSM_DEVICE_KEYS_FILE=/run/secrets/posm-device-keys)
else
  docker_args+=(-e POSM_DEVICE_KEY="${POSM_DEVICE_KEY:-dev-posm-key}")
fi

docker run "${docker_args[@]}" \
  -e BASE_URL="$BASE_URL" \
  -e RUN_ID="$RUN_ID" \
  -e PHARMACY_IDS="${PHARMACY_IDS:-ph_auezova_134}" \
  -e ADMIN_TOKEN="${ADMIN_TOKEN:-}" \
  -e SCENARIOS="${SCENARIOS:-heartbeat,playlist_polling,recommendations,sales,offline_sync}" \
  -e DURATION="${DURATION:-10m}" \
  -e UPLOAD_DURATION="${UPLOAD_DURATION:-10m}" \
  -e OFFLINE_EVENTS_PER_DESK="${OFFLINE_EVENTS_PER_DESK:-10}" \
  -e OFFLINE_SYNC_START="${OFFLINE_SYNC_START:-2m}" \
  -e RECOMMENDATIONS_PER_SECOND="${RECOMMENDATIONS_PER_SECOND:-20}" \
  -e SALES_PER_SECOND="${SALES_PER_SECOND:-10}" \
  -e VIDEO_UPLOADS_PER_MINUTE="${VIDEO_UPLOADS_PER_MINUTE:-12}" \
  -e VIDEO_SIZE_KB="${VIDEO_SIZE_KB:-256}" \
  -e TRIGGER_BARCODE="${TRIGGER_BARCODE:-4603423004936}" \
  -e TRIGGER_SKU="${TRIGGER_SKU:-}" \
  -e CONFIRM_PRODUCTION_LOAD="${CONFIRM_PRODUCTION_LOAD:-}" \
  "$K6_IMAGE" run - < "$ROOT_DIR/load-tests/k6/500-cash-desks.js"
