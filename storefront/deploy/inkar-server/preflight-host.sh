#!/usr/bin/env bash
set -uo pipefail

# Read-only host gate. It never installs packages, starts services, binds ports,
# creates directories or writes configuration.
SITE_DOMAIN="${PREFLIGHT_SITE_DOMAIN:-inkeshopapteka.inkar.kz}"
EXPECTED_SERVER_IP="${PREFLIGHT_EXPECTED_SERVER_IP:-10.10.1.80}"
EXPECTED_DNS_IP="${PREFLIGHT_EXPECTED_DNS_IP:-$EXPECTED_SERVER_IP}"
CATALOG_SOURCE_HOST="${PREFLIGHT_CATALOG_SOURCE_HOST:-10.10.1.76}"
CATALOG_SOURCE_PORT="${PREFLIGHT_CATALOG_SOURCE_PORT:-22}"
REQUIRED_UBUNTU_VERSION="${PREFLIGHT_REQUIRED_UBUNTU_VERSION:-26.04}"
MIN_CPU="${PREFLIGHT_MIN_CPU:-4}"
MIN_RAM_GIB="${PREFLIGHT_MIN_RAM_GIB:-5}"
MIN_DISK_FREE_GIB="${PREFLIGHT_MIN_DISK_FREE_GIB:-20}"
WARN_DISK_TOTAL_GIB="${PREFLIGHT_WARN_DISK_TOTAL_GIB:-200}"
ALLOW_BOUND_PORTS="${PREFLIGHT_ALLOW_BOUND_PORTS:-0}"
OS_RELEASE_FILE="${PREFLIGHT_OS_RELEASE_FILE:-/etc/os-release}"
MEMINFO_FILE="${PREFLIGHT_MEMINFO_FILE:-/proc/meminfo}"

passed=0
warned=0
failed=0

pass() { passed=$((passed + 1)); printf 'PASS  %s\n' "$*"; }
warn() { warned=$((warned + 1)); printf 'WARN  %s\n' "$*"; }
fail() { failed=$((failed + 1)); printf 'FAIL  %s\n' "$*"; }
invalid() { printf 'ERROR %s\n' "$*" >&2; exit 2; }

is_uint() { [[ "$1" =~ ^[0-9]+$ ]]; }
is_ipv4() {
  local candidate="$1" part
  [[ "$candidate" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || return 1
  IFS=. read -r -a parts <<<"$candidate"
  for part in "${parts[@]}"; do (( 10#$part <= 255 )) || return 1; done
}

[[ "$SITE_DOMAIN" =~ ^[A-Za-z0-9.-]+$ ]] || invalid "invalid PREFLIGHT_SITE_DOMAIN"
is_ipv4 "$EXPECTED_SERVER_IP" || invalid "invalid PREFLIGHT_EXPECTED_SERVER_IP"
is_ipv4 "$EXPECTED_DNS_IP" || invalid "invalid PREFLIGHT_EXPECTED_DNS_IP"
is_ipv4 "$CATALOG_SOURCE_HOST" || invalid "invalid PREFLIGHT_CATALOG_SOURCE_HOST"
for pair in \
  "$CATALOG_SOURCE_PORT:PREFLIGHT_CATALOG_SOURCE_PORT:1:65535" \
  "$MIN_CPU:PREFLIGHT_MIN_CPU:1:256" \
  "$MIN_RAM_GIB:PREFLIGHT_MIN_RAM_GIB:1:1024" \
  "$MIN_DISK_FREE_GIB:PREFLIGHT_MIN_DISK_FREE_GIB:1:100000" \
  "$WARN_DISK_TOTAL_GIB:PREFLIGHT_WARN_DISK_TOTAL_GIB:1:100000"; do
  IFS=: read -r value label minimum maximum <<<"$pair"
  is_uint "$value" && (( value >= minimum && value <= maximum )) \
    || invalid "${label} must be between ${minimum} and ${maximum}"
done
[[ "$ALLOW_BOUND_PORTS" == 0 || "$ALLOW_BOUND_PORTS" == 1 ]] \
  || invalid "PREFLIGHT_ALLOW_BOUND_PORTS must be 0 or 1"

printf 'Inkar target host preflight (read-only)\n'
printf 'domain=%s expected_server_ip=%s expected_dns_ip=%s catalog_source=%s:%s\n' \
  "$SITE_DOMAIN" "$EXPECTED_SERVER_IP" "$EXPECTED_DNS_IP" \
  "$CATALOG_SOURCE_HOST" "$CATALOG_SOURCE_PORT"

if [[ -r "$OS_RELEASE_FILE" ]]; then
  os_id="$(awk -F= '$1 == "ID" { gsub(/^"|"$/, "", $2); print $2; exit }' "$OS_RELEASE_FILE")"
  os_version="$(awk -F= '$1 == "VERSION_ID" { gsub(/^"|"$/, "", $2); print $2; exit }' "$OS_RELEASE_FILE")"
  if [[ "$os_id" == ubuntu && "$os_version" == "$REQUIRED_UBUNTU_VERSION" ]]; then
    pass "Ubuntu ${os_version}"
  else
    fail "expected Ubuntu ${REQUIRED_UBUNTU_VERSION}; found ${os_id:-unknown} ${os_version:-unknown}"
  fi
else
  fail "cannot read ${OS_RELEASE_FILE}"
fi

if command -v uname >/dev/null 2>&1; then
  architecture="$(uname -m 2>/dev/null || true)"
  if [[ "$architecture" == x86_64 || "$architecture" == amd64 ]]; then
    pass "CPU architecture=${architecture}"
  else
    fail "expected amd64/x86_64 CPU architecture; found ${architecture:-unknown}"
  fi
else
  fail "uname is unavailable"
fi

if command -v nproc >/dev/null 2>&1; then
  cpu_count="$(nproc --all 2>/dev/null || true)"
  if is_uint "$cpu_count" && (( cpu_count >= MIN_CPU )); then
    pass "CPU threads=${cpu_count} (minimum ${MIN_CPU})"
  else
    fail "CPU threads=${cpu_count:-unknown} (minimum ${MIN_CPU})"
  fi
else
  fail "nproc is unavailable"
fi

if [[ -r "$MEMINFO_FILE" ]]; then
  memory_kib="$(awk '$1 == "MemTotal:" { print $2; exit }' "$MEMINFO_FILE")"
  minimum_memory_kib=$((MIN_RAM_GIB * 1024 * 1024))
  memory_gib="$(awk -v kib="${memory_kib:-0}" 'BEGIN { printf "%.1f", kib / 1024 / 1024 }')"
  if is_uint "${memory_kib:-}" && (( memory_kib >= minimum_memory_kib )); then
    pass "RAM=${memory_gib} GiB (minimum ${MIN_RAM_GIB} GiB)"
  else
    fail "RAM=${memory_gib} GiB (minimum ${MIN_RAM_GIB} GiB)"
  fi
else
  fail "cannot read ${MEMINFO_FILE}"
fi

if [[ -n "${PREFLIGHT_DISK_PATH:-}" ]]; then
  disk_path="$PREFLIGHT_DISK_PATH"
elif [[ -e /srv/inkar-shop ]]; then
  disk_path=/srv/inkar-shop
elif [[ -e /srv ]]; then
  disk_path=/srv
else
  disk_path=/
fi
if [[ ! -e "$disk_path" ]]; then
  fail "disk path does not exist: ${disk_path}"
elif command -v df >/dev/null 2>&1; then
  read -r disk_total_kib disk_available_kib < <(
    df -Pk "$disk_path" 2>/dev/null | awk 'NR == 2 { print $2, $4 }'
  )
  minimum_disk_kib=$((MIN_DISK_FREE_GIB * 1024 * 1024))
  warning_total_kib=$((WARN_DISK_TOTAL_GIB * 1024 * 1024))
  disk_total_gib="$(awk -v kib="${disk_total_kib:-0}" 'BEGIN { printf "%.1f", kib / 1024 / 1024 }')"
  disk_available_gib="$(awk -v kib="${disk_available_kib:-0}" 'BEGIN { printf "%.1f", kib / 1024 / 1024 }')"
  if is_uint "${disk_available_kib:-}" && (( disk_available_kib >= minimum_disk_kib )); then
    pass "disk=${disk_path} total=${disk_total_gib} GiB free=${disk_available_gib} GiB"
  else
    fail "disk=${disk_path} free=${disk_available_gib} GiB (minimum ${MIN_DISK_FREE_GIB} GiB)"
  fi
  if is_uint "${disk_total_kib:-}" && (( disk_total_kib < warning_total_kib )); then
    warn "disk total ${disk_total_gib} GiB is below the expected ~250 GiB target"
  fi
else
  fail "df is unavailable"
fi

if [[ "$(id -u)" == 0 ]]; then
  pass "sudo capability: running as root"
elif ! command -v sudo >/dev/null 2>&1; then
  fail "sudo is unavailable"
elif sudo -n true >/dev/null 2>&1; then
  pass "sudo capability: non-interactive command allowed"
else
  warn "sudo exists but needs interactive authorization"
fi

if ! command -v docker >/dev/null 2>&1; then
  fail "Docker CLI is unavailable"
else
  docker_version="$(docker --version 2>/dev/null || true)"
  [[ -n "$docker_version" ]] && pass "$docker_version" || fail "Docker CLI version check failed"
  compose_version="$(docker compose version 2>/dev/null || true)"
  [[ -n "$compose_version" ]] && pass "$compose_version" || fail "Docker Compose v2 is unavailable"
  docker_server_version="$(docker info --format '{{.ServerVersion}}' 2>/dev/null || true)"
  [[ -n "$docker_server_version" ]] \
    && pass "Docker daemon reachable (server ${docker_server_version})" \
    || fail "Docker daemon is not reachable by the deployment user"
fi

if ! command -v ss >/dev/null 2>&1; then
  fail "ss is unavailable; cannot verify ports 80/443"
else
  for port in 80 443; do
    if ss -H -ltn "sport = :${port}" 2>/dev/null | grep -q .; then
      if [[ "$ALLOW_BOUND_PORTS" == 1 ]]; then
        warn "TCP ${port} is already bound (explicitly allowed)"
      else
        fail "TCP ${port} is already bound"
      fi
    else
      pass "TCP ${port} is free"
    fi
  done
fi

if ! command -v getent >/dev/null 2>&1; then
  fail "getent is unavailable; cannot verify DNS"
else
  dns_addresses="$(getent ahostsv4 "$SITE_DOMAIN" 2>/dev/null | awk '!seen[$1]++ { print $1 }')"
  if [[ -z "$dns_addresses" ]]; then
    fail "DNS A lookup is unresolved for ${SITE_DOMAIN}"
  elif grep -Fxq "$EXPECTED_DNS_IP" <<<"$dns_addresses"; then
    pass "DNS A includes expected ${EXPECTED_DNS_IP}"
  else
    fail "DNS A does not include expected ${EXPECTED_DNS_IP}; resolved=$(tr '\n' ',' <<<"$dns_addresses" | sed 's/,$//')"
  fi
fi

if ! command -v ip >/dev/null 2>&1; then
  fail "iproute2 is unavailable"
else
  expected_route="$(ip -4 route get "$EXPECTED_SERVER_IP" 2>/dev/null | head -n 1)"
  [[ -n "$expected_route" ]] \
    && pass "kernel route exists for expected server IP" \
    || fail "no kernel route for expected server IP"
  catalog_source_route="$(ip -4 route get "$CATALOG_SOURCE_HOST" 2>/dev/null | head -n 1)"
  [[ -n "$catalog_source_route" ]] \
    && pass "kernel route exists for catalog source" \
    || fail "no kernel route for catalog source"
  owned_addresses="$(ip -o -4 addr show 2>/dev/null | awk '{ sub(/\/.*/, "", $4); print $4 }')"
  if grep -Fxq "$EXPECTED_SERVER_IP" <<<"$owned_addresses"; then
    pass "expected server IP is assigned locally"
  else
    warn "expected server IP is not assigned locally (confirm approved NAT/interface design)"
  fi
fi

if ! command -v timeout >/dev/null 2>&1; then
  fail "timeout is unavailable; cannot bound catalog-source TCP probe"
elif timeout 5 bash -c 'exec 3<>/dev/tcp/"$1"/"$2"' _ "$CATALOG_SOURCE_HOST" "$CATALOG_SOURCE_PORT" 2>/dev/null; then
  pass "catalog source TCP ${CATALOG_SOURCE_HOST}:${CATALOG_SOURCE_PORT} is reachable"
else
  fail "catalog source TCP ${CATALOG_SOURCE_HOST}:${CATALOG_SOURCE_PORT} is unreachable"
fi

printf 'SUMMARY pass=%s warn=%s fail=%s\n' "$passed" "$warned" "$failed"
if (( failed > 0 )); then
  exit 1
fi
