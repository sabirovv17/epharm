#!/usr/bin/env bash
set -Eeuo pipefail

TEST_DIR="$(mktemp -d)"
trap 'rm -rf -- "$TEST_DIR"' EXIT
export INKAR_STATE_ROOT="$TEST_DIR"
export INKAR_DEPLOY_LIBRARY_ONLY=1

TEST_SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
BUNDLE_DIR="$(CDPATH= cd -- "${TEST_SCRIPT_DIR}/.." && pwd)"
mkdir -p "${TEST_DIR}/state"
cp -- "${BUNDLE_DIR}/release.env.example" "${TEST_DIR}/state/release.env"

# shellcheck source=../deploy.sh
source "${BUNDLE_DIR}/deploy.sh"

ROUTER_LOG="${TEST_DIR}/router.log"
FAIL_CANDIDATE=1
compose() {
  if [[ "$*" == *"router"* ]]; then
    printf '%s\n' "${ACTIVE_WEB_UPSTREAM:-$(state_value ACTIVE_WEB_UPSTREAM)}" >>"$ROUTER_LOG"
    if [[ "${ACTIVE_WEB_UPSTREAM:-}" == "web-green:3000" && "$FAIL_CANDIDATE" == "1" ]]; then
      return 1
    fi
  fi
  return 0
}
wait_healthy() { return 0; }
note() { :; }

set_state WEB_BLUE_IMAGE inkar-shop:old
set_state WEB_GREEN_IMAGE inkar-shop:candidate
set_state DEPLOY_IMAGE inkar-shop:candidate

if (activate_slot green blue 2>/dev/null); then
  printf 'failed candidate unexpectedly activated\n' >&2
  exit 1
fi
[[ "$(state_value ACTIVE_SLOT)" == "blue" ]]
[[ "$(state_value ACTIVE_WEB_UPSTREAM)" == "web-blue:3000" ]]
[[ "$(state_value DEPLOY_IMAGE)" == "inkar-shop:old" ]]
grep -qx 'web-green:3000' "$ROUTER_LOG"
grep -qx 'web-blue:3000' "$ROUTER_LOG"

: >"$ROUTER_LOG"
FAIL_CANDIDATE=0
activate_slot green blue
[[ "$(state_value ACTIVE_SLOT)" == "green" ]]
[[ "$(state_value ACTIVE_WEB_UPSTREAM)" == "web-green:3000" ]]
[[ "$(state_value DEPLOY_IMAGE)" == "inkar-shop:candidate" ]]
grep -qx 'web-green:3000' "$ROUTER_LOG"

# A failure before activation must also restore the previous inactive image
# mapping and the operation image used by scheduled jobs.
commit_active_state blue web-blue:3000 inkar-shop:old
set_state WEB_GREEN_IMAGE inkar-shop:previous-green
FAIL_CANDIDATE=0
preflight() { :; }
release_id() { printf candidate-test; }
backup() { :; }
build_image() { :; }
ensure_vapid_keys() { :; }
prepare_data_permissions() { :; }
migrate() { :; }
sync_catalog() { :; }
sync_offers() { :; }
deep_check() { return 1; }
if (release 2>/dev/null); then
  printf 'failed release unexpectedly activated\n' >&2
  exit 1
fi
[[ "$(state_value ACTIVE_SLOT)" == "blue" ]]
[[ "$(state_value ACTIVE_WEB_UPSTREAM)" == "web-blue:3000" ]]
[[ "$(state_value DEPLOY_IMAGE)" == "inkar-shop:old" ]]
[[ "$(state_value WEB_GREEN_IMAGE)" == "inkar-shop:previous-green" ]]

printf 'state switch harness passed\n'