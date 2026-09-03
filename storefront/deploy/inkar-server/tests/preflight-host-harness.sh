#!/usr/bin/env bash
set -Eeuo pipefail

TEST_DIR="$(mktemp -d)"
trap 'rm -rf -- "$TEST_DIR"' EXIT
TEST_SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
BUNDLE_DIR="$(CDPATH= cd -- "${TEST_SCRIPT_DIR}/.." && pwd)"
mkdir -p "${TEST_DIR}/bin" "${TEST_DIR}/disk"

cat >"${TEST_DIR}/os-release" <<'FIXTURE'
ID=ubuntu
VERSION_ID="26.04"
FIXTURE
cat >"${TEST_DIR}/meminfo" <<'FIXTURE'
MemTotal:        6291456 kB
FIXTURE

cat >"${TEST_DIR}/bin/uname" <<'MOCK'
#!/usr/bin/env bash
printf 'x86_64\n'
MOCK
cat >"${TEST_DIR}/bin/nproc" <<'MOCK'
#!/usr/bin/env bash
printf '4\n'
MOCK
cat >"${TEST_DIR}/bin/df" <<'MOCK'
#!/usr/bin/env bash
printf 'Filesystem 1024-blocks Used Available Capacity Mounted on\n'
printf '/dev/mock 262144000 10485760 251658240 4%% /fixture\n'
MOCK
cat >"${TEST_DIR}/bin/id" <<'MOCK'
#!/usr/bin/env bash
case "${1:-}" in
  -u) printf '1000\n' ;;
  -un) printf 'adm-quasar\n' ;;
  *) printf 'uid=1000(adm-quasar)\n' ;;
esac
MOCK
cat >"${TEST_DIR}/bin/sudo" <<'MOCK'
#!/usr/bin/env bash
printf 'sudo %s\n' "$*" >>"$PREFLIGHT_CALL_LOG"
[[ "${1:-}" == -n && "${2:-}" == true ]]
MOCK
cat >"${TEST_DIR}/bin/docker" <<'MOCK'
#!/usr/bin/env bash
printf 'docker %s\n' "$*" >>"$PREFLIGHT_CALL_LOG"
case "${1:-}" in
  --version) printf 'Docker version 27.5.1, build fixture\n' ;;
  compose) [[ "${2:-}" == version ]] && printf 'Docker Compose version v2.32.4\n' ;;
  info) printf '27.5.1\n' ;;
  *) exit 1 ;;
esac
MOCK
cat >"${TEST_DIR}/bin/ss" <<'MOCK'
#!/usr/bin/env bash
printf 'ss %s\n' "$*" >>"$PREFLIGHT_CALL_LOG"
if [[ -n "${MOCK_BOUND_PORT:-}" && "$*" == *":${MOCK_BOUND_PORT}"* ]]; then
  printf 'LISTEN 0 4096 0.0.0.0:%s 0.0.0.0:*\n' "$MOCK_BOUND_PORT"
fi
MOCK
cat >"${TEST_DIR}/bin/getent" <<'MOCK'
#!/usr/bin/env bash
printf 'getent %s\n' "$*" >>"$PREFLIGHT_CALL_LOG"
[[ "${1:-}" == ahostsv4 ]]
if [[ "${MOCK_DNS_IP:-10.10.1.80}" == unresolved ]]; then exit 2; fi
printf '%s STREAM %s\n' "${MOCK_DNS_IP:-10.10.1.80}" "${2:-fixture}"
printf '%s DGRAM  %s\n' "${MOCK_DNS_IP:-10.10.1.80}" "${2:-fixture}"
MOCK
cat >"${TEST_DIR}/bin/ip" <<'MOCK'
#!/usr/bin/env bash
printf 'ip %s\n' "$*" >>"$PREFLIGHT_CALL_LOG"
case "$*" in
  '-4 route get 10.10.1.80') printf 'local 10.10.1.80 dev lo src 10.10.1.80\n' ;;
  '-4 route get 10.10.1.76') printf '10.10.1.76 via 10.10.1.1 dev eth0 src 10.10.1.80\n' ;;
  '-o -4 addr show') printf '2: eth0 inet 10.10.1.80/24 brd 10.10.1.255 scope global eth0\n' ;;
  *) exit 1 ;;
esac
MOCK
cat >"${TEST_DIR}/bin/timeout" <<'MOCK'
#!/usr/bin/env bash
printf 'timeout %s\n' "$*" >>"$PREFLIGHT_CALL_LOG"
exit "${MOCK_TCP_EXIT:-0}"
MOCK
chmod +x "${TEST_DIR}/bin/"*

export PATH="${TEST_DIR}/bin:${PATH}"
export PREFLIGHT_CALL_LOG="${TEST_DIR}/calls.log"
export PREFLIGHT_OS_RELEASE_FILE="${TEST_DIR}/os-release"
export PREFLIGHT_MEMINFO_FILE="${TEST_DIR}/meminfo"
export PREFLIGHT_DISK_PATH="${TEST_DIR}/disk"
export MOCK_DNS_IP=10.10.1.80
export MOCK_TCP_EXIT=0
unset MOCK_BOUND_PORT
: >"$PREFLIGHT_CALL_LOG"

bash "${BUNDLE_DIR}/preflight-host.sh" >"${TEST_DIR}/pass.out"
grep -Eq '^SUMMARY pass=[0-9]+ warn=0 fail=0$' "${TEST_DIR}/pass.out"
grep -q '^PASS  Ubuntu 26.04$' "${TEST_DIR}/pass.out"
grep -q '^PASS  DNS A includes expected 10.10.1.80$' "${TEST_DIR}/pass.out"
grep -q '^PASS  catalog source TCP 10.10.1.76:22 is reachable$' "${TEST_DIR}/pass.out"

export MOCK_DNS_IP=203.0.113.20
export PREFLIGHT_EXPECTED_DNS_IP=10.10.1.80
: >"$PREFLIGHT_CALL_LOG"
if bash "${BUNDLE_DIR}/preflight-host.sh" >"${TEST_DIR}/dns-fail.out"; then
  printf 'DNS mismatch unexpectedly passed preflight\n' >&2
  exit 1
fi
grep -q '^FAIL  DNS A does not include expected 10.10.1.80' "${TEST_DIR}/dns-fail.out"
grep -Eq '^SUMMARY pass=[0-9]+ warn=0 fail=1$' "${TEST_DIR}/dns-fail.out"

export PREFLIGHT_EXPECTED_DNS_IP=203.0.113.20
: >"$PREFLIGHT_CALL_LOG"
bash "${BUNDLE_DIR}/preflight-host.sh" >"${TEST_DIR}/nat-pass.out"
grep -q '^PASS  DNS A includes expected 203.0.113.20$' "${TEST_DIR}/nat-pass.out"

export MOCK_DNS_IP=10.10.1.80
unset PREFLIGHT_EXPECTED_DNS_IP
export MOCK_BOUND_PORT=443
: >"$PREFLIGHT_CALL_LOG"
if bash "${BUNDLE_DIR}/preflight-host.sh" >"${TEST_DIR}/port-fail.out"; then
  printf 'bound port unexpectedly passed preflight\n' >&2
  exit 1
fi
grep -q '^FAIL  TCP 443 is already bound$' "${TEST_DIR}/port-fail.out"
grep -Eq '^SUMMARY pass=[0-9]+ warn=0 fail=1$' "${TEST_DIR}/port-fail.out"

if grep -Eiq '(^| )(apt|apt-get|dnf|yum|apk|systemctl|service|docker (run|pull|build)|docker compose (up|down|start|stop))($| )' \
  "$PREFLIGHT_CALL_LOG"; then
  printf 'preflight attempted a mutating command\n' >&2
  exit 1
fi

printf 'host preflight harness passed\n'
