#!/usr/bin/env bash
# Application rollback only. Flyway migrations are forward-only and must remain
# backward-compatible for at least one release.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=tools/release/lib.sh
source "$SCRIPT_DIR/lib.sh"

target="${1:-}"
[[ -n "$target" ]] || { echo "Usage: $0 vX.Y.Z" >&2; exit 2; }
assert_release_id "$target"
target_commit="$(release_commit_for_tag "$target")"
ensure_release_images "$target"

current_id="$(read_release_env_value "$RELEASE_ROOT/.release.env" RELEASE_ID || true)"
current_commit="$(read_release_env_value "$RELEASE_ROOT/.release.env" RELEASE_COMMIT || true)"
[[ "$current_id" != "$target" ]] || { echo "ERROR: $target is already active" >&2; exit 1; }

"$RELEASE_ROOT/tools/backup-all.sh"
write_release_env "$target" "$target_commit"
restore_current_on_error() {
  if [[ -n "$current_id" && -n "$current_commit" ]]; then
    write_release_env "$current_id" "$current_commit"
    compose_prod up -d --no-build backend frontend caddy || true
  fi
}
trap restore_current_on_error ERR

compose_prod up -d --no-build backend frontend caddy
"$SCRIPT_DIR/smoke.sh" "$target"
trap - ERR
echo "Rollback verified: ${current_id:-unknown} -> $target"
