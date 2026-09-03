#!/usr/bin/env bash
set -Eeuo pipefail

STATE_ROOT="${INKAR_STATE_ROOT:-/srv/inkar-shop}"
CONFIG_FILE="${CLICKHOUSE_TUNNEL_ENV_FILE:-${STATE_ROOT}/config/clickhouse-tunnel.env}"

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
is_port() { [[ "$1" =~ ^[0-9]+$ ]] && (( 10#$1 >= 1 && 10#$1 <= 65535 )); }
is_host() { [[ "$1" =~ ^[A-Za-z0-9._-]+$ ]]; }

command -v docker >/dev/null 2>&1 || die "docker is required"
command -v ssh >/dev/null 2>&1 || die "OpenSSH client is required"
[[ -f "$CONFIG_FILE" && ! -L "$CONFIG_FILE" ]] || die "missing regular config file: ${CONFIG_FILE}"

set -a
# shellcheck disable=SC1090
. "$CONFIG_FILE"
set +a

: "${SSH_HOST:?SSH_HOST is required}"
: "${SSH_PORT:?SSH_PORT is required}"
: "${SSH_USER:?SSH_USER is required}"
: "${SSH_KEY_PATH:?SSH_KEY_PATH is required}"
: "${SSH_KNOWN_HOSTS_PATH:?SSH_KNOWN_HOSTS_PATH is required}"
: "${LOCAL_PORT:?LOCAL_PORT is required}"
: "${REMOTE_CLICKHOUSE_HOST:?REMOTE_CLICKHOUSE_HOST is required}"
: "${REMOTE_CLICKHOUSE_PORT:?REMOTE_CLICKHOUSE_PORT is required}"

is_host "$SSH_HOST" || die "invalid SSH_HOST"
is_port "$SSH_PORT" || die "invalid SSH_PORT"
[[ "$SSH_USER" =~ ^[A-Za-z_][A-Za-z0-9_-]*$ ]] || die "invalid SSH_USER"
is_port "$LOCAL_PORT" || die "invalid LOCAL_PORT"
is_host "$REMOTE_CLICKHOUSE_HOST" || die "invalid REMOTE_CLICKHOUSE_HOST"
is_port "$REMOTE_CLICKHOUSE_PORT" || die "invalid REMOTE_CLICKHOUSE_PORT"
[[ "$REMOTE_CLICKHOUSE_HOST" != replace_* ]] || die "REMOTE_CLICKHOUSE_HOST is still a placeholder"
[[ -f "$SSH_KEY_PATH" && ! -L "$SSH_KEY_PATH" ]] || die "SSH key must be a regular file"
[[ -f "$SSH_KNOWN_HOSTS_PATH" && ! -L "$SSH_KNOWN_HOSTS_PATH" ]] \
  || die "verified SSH known-hosts file is required"

key_mode="$(stat -c '%a' "$SSH_KEY_PATH")"
[[ "$key_mode" == 400 || "$key_mode" == 600 ]] || die "SSH key mode must be 0400 or 0600"

docker_gateway="$(
  docker network inspect bridge \
    --format '{{with (index .IPAM.Config 0)}}{{.Gateway}}{{end}}' 2>/dev/null
)"
[[ "$docker_gateway" =~ ^[0-9]+(\.[0-9]+){3}$ ]] \
  || die "cannot determine Docker host-gateway IPv4 address"

exec ssh -NT \
  -4 \
  -p "$SSH_PORT" \
  -i "$SSH_KEY_PATH" \
  -o BatchMode=yes \
  -o ExitOnForwardFailure=yes \
  -o IdentitiesOnly=yes \
  -o ServerAliveInterval=30 \
  -o ServerAliveCountMax=3 \
  -o StrictHostKeyChecking=yes \
  -o "UserKnownHostsFile=${SSH_KNOWN_HOSTS_PATH}" \
  -L "${docker_gateway}:${LOCAL_PORT}:${REMOTE_CLICKHOUSE_HOST}:${REMOTE_CLICKHOUSE_PORT}" \
  "${SSH_USER}@${SSH_HOST}"
