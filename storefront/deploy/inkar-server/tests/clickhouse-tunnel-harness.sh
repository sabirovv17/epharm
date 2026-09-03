#!/usr/bin/env bash
set -Eeuo pipefail

TEST_ROOT="$(mktemp -d)"
trap 'rm -rf -- "$TEST_ROOT"' EXIT
TEST_SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
BUNDLE_DIR="$(CDPATH= cd -- "${TEST_SCRIPT_DIR}/.." && pwd)"
mkdir -p "${TEST_ROOT}/bin"

key="${TEST_ROOT}/tunnel-key"
known_hosts="${TEST_ROOT}/known-hosts"
config="${TEST_ROOT}/tunnel.env"
printf 'fixture-key\n' >"$key"
printf 'fixture-known-host\n' >"$known_hosts"
chmod 600 "$key" "$known_hosts"

cat >"$config" <<EOF
SSH_HOST=10.10.1.76
SSH_PORT=22
SSH_USER=adm-quasar
SSH_KEY_PATH=${key}
SSH_KNOWN_HOSTS_PATH=${known_hosts}
LOCAL_PORT=18123
REMOTE_CLICKHOUSE_HOST=172.20.0.10
REMOTE_CLICKHOUSE_PORT=8123
EOF

cat >"${TEST_ROOT}/bin/docker" <<'MOCK'
#!/usr/bin/env bash
[[ "$*" == "network inspect bridge --format {{with (index .IPAM.Config 0)}}{{.Gateway}}{{end}}" ]]
printf '172.17.0.1\n'
MOCK
cat >"${TEST_ROOT}/bin/ssh" <<'MOCK'
#!/usr/bin/env bash
printf '%s\n' "$*" >"$TUNNEL_SSH_ARGS_LOG"
MOCK
cat >"${TEST_ROOT}/bin/stat" <<'MOCK'
#!/usr/bin/env bash
[[ "${1:-}" == -c && "${2:-}" == %a ]]
printf '600\n'
MOCK
chmod +x "${TEST_ROOT}/bin/docker" "${TEST_ROOT}/bin/ssh" "${TEST_ROOT}/bin/stat"

export PATH="${TEST_ROOT}/bin:${PATH}"
export CLICKHOUSE_TUNNEL_ENV_FILE="$config"
export TUNNEL_SSH_ARGS_LOG="${TEST_ROOT}/ssh-args.log"

bash "${BUNDLE_DIR}/clickhouse-tunnel.sh"
grep -Fq -- '-o BatchMode=yes' "$TUNNEL_SSH_ARGS_LOG"
grep -Fq -- '-o StrictHostKeyChecking=yes' "$TUNNEL_SSH_ARGS_LOG"
grep -Fq -- '-L 172.17.0.1:18123:172.20.0.10:8123' "$TUNNEL_SSH_ARGS_LOG"
grep -Fq -- 'adm-quasar@10.10.1.76' "$TUNNEL_SSH_ARGS_LOG"

sed -i 's/REMOTE_CLICKHOUSE_HOST=172.20.0.10/REMOTE_CLICKHOUSE_HOST=replace_with_clickhouse_container_ip/' "$config"
if bash "${BUNDLE_DIR}/clickhouse-tunnel.sh" >"${TEST_ROOT}/placeholder.out" 2>&1; then
  printf 'placeholder tunnel target unexpectedly passed\n' >&2
  exit 1
fi
grep -Fq 'REMOTE_CLICKHOUSE_HOST is still a placeholder' "${TEST_ROOT}/placeholder.out"

printf 'ClickHouse tunnel harness passed\n'
