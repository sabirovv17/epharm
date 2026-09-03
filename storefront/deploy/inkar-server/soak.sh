#!/usr/bin/env bash
set -Euo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
STATE_ROOT="${INKAR_STATE_ROOT:-/srv/inkar-shop}"
STATE_FILE="${STATE_ROOT}/state/release.env"
MANIFEST_ROOT="${STATE_ROOT}/manifests"

DURATION_SECONDS="${SOAK_DURATION_SECONDS:-21600}"
INTERVAL_SECONDS="${SOAK_INTERVAL_SECONDS:-300}"
MAX_ITERATIONS="${SOAK_MAX_ITERATIONS:-73}"
CURL_MAX_TIME="${SOAK_CURL_MAX_TIME:-30}"
CRITICAL_FAILURE_LIMIT="${SOAK_CRITICAL_FAILURE_LIMIT:-3}"
PRODUCT_SLUG="${SOAK_PRODUCT_SLUG:-vezilyut-peptid-bio-200mg-30-kaps}"
NO_SLEEP="${SOAK_NO_SLEEP:-0}"
PLAN_ONLY="${SOAK_PLAN_ONLY:-0}"

die() { printf 'ERROR: %s\n' "$*" >&2; exit 2; }
integer_between() {
  local value="$1" label="$2" minimum="$3" maximum="$4"
  [[ "$value" =~ ^[0-9]+$ ]] || die "${label} must be an integer"
  (( value >= minimum && value <= maximum )) \
    || die "${label} must be between ${minimum} and ${maximum}"
}
state_value() {
  awk -F= -v wanted="$1" '$1 == wanted { sub(/^[^=]*=/, ""); print; exit }' "$STATE_FILE"
}

integer_between "$DURATION_SECONDS" SOAK_DURATION_SECONDS 1 21600
integer_between "$INTERVAL_SECONDS" SOAK_INTERVAL_SECONDS 1 300
integer_between "$MAX_ITERATIONS" SOAK_MAX_ITERATIONS 1 73
integer_between "$CURL_MAX_TIME" SOAK_CURL_MAX_TIME 1 60
integer_between "$CRITICAL_FAILURE_LIMIT" SOAK_CRITICAL_FAILURE_LIMIT 1 6
[[ "$NO_SLEEP" == 0 || "$NO_SLEEP" == 1 ]] || die "SOAK_NO_SLEEP must be 0 or 1"
[[ "$PLAN_ONLY" == 0 || "$PLAN_ONLY" == 1 ]] || die "SOAK_PLAN_ONLY must be 0 or 1"
[[ "$PRODUCT_SLUG" =~ ^[a-zA-Z0-9][a-zA-Z0-9_-]{0,199}$ ]] || die "invalid SOAK_PRODUCT_SLUG"

scheduled_span_seconds=$(( (MAX_ITERATIONS - 1) * INTERVAL_SECONDS ))
if [[ "$PLAN_ONLY" == 1 ]]; then
  printf 'duration_seconds=%s\ninterval_seconds=%s\nmax_iterations=%s\nscheduled_span_seconds=%s\n' \
    "$DURATION_SECONDS" "$INTERVAL_SECONDS" "$MAX_ITERATIONS" "$scheduled_span_seconds"
  if (( scheduled_span_seconds >= DURATION_SECONDS )); then
    printf 'covers_duration=true\n'
  else
    printf 'covers_duration=false\n'
  fi
  exit 0
fi

command -v curl >/dev/null 2>&1 || die "curl is required"
command -v awk >/dev/null 2>&1 || die "awk is required"
command -v sort >/dev/null 2>&1 || die "sort is required"
[[ -f "$STATE_FILE" ]] || die "deployment state is missing: ${STATE_FILE}"

if [[ -n "${SOAK_BASE_URL:-}" ]]; then
  BASE_URL="${SOAK_BASE_URL%/}"
else
  domain="$(state_value SITE_DOMAIN)"
  case "$(state_value TLS_MODE)" in
    tls) BASE_URL="https://${domain}" ;;
    http) BASE_URL="http://${domain}" ;;
    *) die "invalid TLS_MODE in ${STATE_FILE}" ;;
  esac
fi
[[ "$BASE_URL" =~ ^https?://[A-Za-z0-9._:-]+$ ]] || die "SOAK_BASE_URL must be a plain HTTP(S) origin"

CURL_HEADERS=(-H "Accept: application/json,text/html;q=0.9")
if [[ -n "${SOAK_HOST_HEADER:-}" ]]; then
  [[ "$SOAK_HOST_HEADER" =~ ^[A-Za-z0-9.-]+$ ]] || die "invalid SOAK_HOST_HEADER"
  CURL_HEADERS+=(-H "Host: ${SOAK_HOST_HEADER}")
fi

umask 027
mkdir -p "$MANIFEST_ROOT"
run_id="$(date -u +%Y%m%dT%H%M%SZ)"
LOG_FILE="${MANIFEST_ROOT}/soak-${run_id}.tsv"
SUMMARY_FILE="${MANIFEST_ROOT}/soak-${run_id}.summary.txt"
temporary_body="$(mktemp "${MANIFEST_ROOT}/.soak-body.XXXXXX")"
temporary_error="$(mktemp "${MANIFEST_ROOT}/.soak-error.XXXXXX")"
trap 'rm -f -- "$temporary_body" "$temporary_error"' EXIT
printf 'timestamp_utc\titeration\tcheck\turl\thttp_status\tcurl_exit\tlatency_ms\tbytes\tresult\tdetail\n' >"$LOG_FILE"

declare -A CHECK_TOTAL=()
declare -A CHECK_PASS=()
CHECKS=(shallow_health deep_health catalog_api catalog_filter catalog_page product_api product_page)

validate_body() {
  local validator="$1" file="$2"
  case "$validator" in
    health) grep -Eq '"ok"[[:space:]]*:[[:space:]]*true' "$file" ;;
    catalog) grep -Eq '"products"[[:space:]]*:[[:space:]]*\[' "$file" ;;
    product) grep -Eq '"product"[[:space:]]*:[[:space:]]*\{' "$file" ;;
    html) grep -Eqi '<!doctype|<html' "$file" ;;
    *) return 1 ;;
  esac
}

LAST_PROBE_OK=0
probe() {
  local iteration="$1" name="$2" path="$3" validator="$4"
  local url="${BASE_URL}${path}" metrics curl_exit=0 status=000 seconds=0 bytes=0
  local latency_ms=0 result=fail detail
  : >"$temporary_body"
  : >"$temporary_error"
  metrics="$(
    curl --silent --show-error --request GET --connect-timeout 8 --max-time "$CURL_MAX_TIME" \
      --proto '=http,https' "${CURL_HEADERS[@]}" --output "$temporary_body" \
      --write-out $'%{http_code}\t%{time_total}\t%{size_download}' "$url" \
      2>"$temporary_error"
  )" || curl_exit=$?
  if [[ "$metrics" == *$'\t'* ]]; then
    IFS=$'\t' read -r status seconds bytes <<<"$metrics"
  fi
  latency_ms="$(awk -v seconds="${seconds:-0}" 'BEGIN { printf "%.0f", seconds * 1000 }')"
  LAST_PROBE_OK=0
  if (( curl_exit == 0 )) && [[ "$status" == 200 ]] && validate_body "$validator" "$temporary_body"; then
    result=pass
    detail=ok
    LAST_PROBE_OK=1
    CHECK_PASS["$name"]=$(( ${CHECK_PASS["$name"]:-0} + 1 ))
  elif (( curl_exit != 0 )); then
    detail="curl_${curl_exit}"
  elif [[ "$status" != 200 ]]; then
    detail="http_${status}"
  else
    detail="body_contract"
  fi
  CHECK_TOTAL["$name"]=$(( ${CHECK_TOTAL["$name"]:-0} + 1 ))
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$iteration" "$name" "$url" "$status" \
    "$curl_exit" "$latency_ms" "${bytes:-0}" "$result" "$detail" >>"$LOG_FILE"
}

required_success_percent() {
  case "$1" in
    shallow_health) printf 100 ;;
    deep_health) printf 98 ;;
    *) printf 95 ;;
  esac
}

p95_limit_ms() {
  case "$1" in
    shallow_health) printf 1500 ;;
    deep_health) printf 10000 ;;
    catalog_api|catalog_filter) printf 6000 ;;
    catalog_page|product_page) printf 5000 ;;
    product_api) printf 6000 ;;
  esac
}

p95_for_check() {
  local check="$1"
  awk -F '\t' -v wanted="$check" 'NR > 1 && $3 == wanted { print $7 }' "$LOG_FILE" \
    | sort -n \
    | awk '{ values[NR] = $1 } END { if (!NR) { print 0; exit }; position = int((NR * 95 + 99) / 100); print values[position] }'
}

started_epoch="$(date +%s)"
deadline_epoch=$((started_epoch + DURATION_SECONDS))
iteration=0
consecutive_critical_failures=0
aborted=0

printf 'Starting bounded read-only soak: base=%s duration=%ss interval=%ss max_iterations=%s\n' \
  "$BASE_URL" "$DURATION_SECONDS" "$INTERVAL_SECONDS" "$MAX_ITERATIONS"

while (( iteration < MAX_ITERATIONS )); do
  iteration=$((iteration + 1))
  cycle_critical_failed=0

  probe "$iteration" shallow_health "/api/health" health
  (( LAST_PROBE_OK == 1 )) || cycle_critical_failed=1
  probe "$iteration" deep_health "/api/health?deep=1" health
  (( LAST_PROBE_OK == 1 )) || cycle_critical_failed=1
  probe "$iteration" catalog_api "/api/catalog?limit=24&page=1&includeFacets=1" catalog
  probe "$iteration" catalog_filter "/api/catalog?limit=12&page=1&includeFacets=0&sort=price_asc&inStock=true" catalog
  probe "$iteration" catalog_page "/catalog?inStock=true&sort=price_asc" html
  probe "$iteration" product_api "/api/product/${PRODUCT_SLUG}" product
  probe "$iteration" product_page "/product/${PRODUCT_SLUG}" html

  if (( cycle_critical_failed == 1 )); then
    consecutive_critical_failures=$((consecutive_critical_failures + 1))
  else
    consecutive_critical_failures=0
  fi
  printf 'Soak cycle %s/%s complete; consecutive critical failures=%s\n' \
    "$iteration" "$MAX_ITERATIONS" "$consecutive_critical_failures"
  if (( consecutive_critical_failures >= CRITICAL_FAILURE_LIMIT )); then
    aborted=1
    break
  fi
  now_epoch="$(date +%s)"
  (( now_epoch < deadline_epoch && iteration < MAX_ITERATIONS )) || break
  next_sample_epoch=$((started_epoch + iteration * INTERVAL_SECONDS))
  if (( next_sample_epoch > deadline_epoch )); then next_sample_epoch="$deadline_epoch"; fi
  sleep_seconds=$((next_sample_epoch - now_epoch))
  if [[ "$NO_SLEEP" == 0 && "$sleep_seconds" -gt 0 ]]; then sleep "$sleep_seconds"; fi
done

ended_epoch="$(date +%s)"
overall=pass
{
  printf 'Inkar storefront read-only soak\n'
  printf 'base_url=%s\nstarted_utc=%s\nended_utc=%s\nduration_seconds=%s\niterations=%s\n' \
    "$BASE_URL" "$(date -u -d "@${started_epoch}" +%Y-%m-%dT%H:%M:%SZ)" \
    "$(date -u -d "@${ended_epoch}" +%Y-%m-%dT%H:%M:%SZ)" "$((ended_epoch - started_epoch))" "$iteration"
  printf 'aborted_after_consecutive_critical_failures=%s\n' "$aborted"
  printf '\ncheck\ttotal\tpassed\tsuccess_percent\trequired_percent\tp95_ms\tp95_limit_ms\tresult\n'
  for check in "${CHECKS[@]}"; do
    total="${CHECK_TOTAL["$check"]:-0}"
    passed="${CHECK_PASS["$check"]:-0}"
    required="$(required_success_percent "$check")"
    latency_limit="$(p95_limit_ms "$check")"
    p95="$(p95_for_check "$check")"
    success_percent="$(awk -v passed="$passed" -v total="$total" 'BEGIN { if (!total) print 0; else printf "%.2f", passed * 100 / total }')"
    check_result=pass
    if (( total == 0 )) \
      || ! awk -v actual="$success_percent" -v required="$required" 'BEGIN { exit !(actual + 0 >= required + 0) }' \
      || (( p95 > latency_limit )); then
      check_result=fail
      overall=fail
    fi
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
      "$check" "$total" "$passed" "$success_percent" "$required" "$p95" "$latency_limit" "$check_result"
  done
  if (( aborted == 1 )); then overall=fail; fi
  printf '\noverall=%s\nlog_file=%s\n' "$overall" "$LOG_FILE"
} >"$SUMMARY_FILE"

cat "$SUMMARY_FILE"
printf 'Soak artifacts: %s and %s\n' "$LOG_FILE" "$SUMMARY_FILE"
[[ "$overall" == pass ]]
