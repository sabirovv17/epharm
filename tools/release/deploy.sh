#!/usr/bin/env bash
# Deploys an already-built release. A data backup is mandatory and smoke failure
# automatically restores the previously running application images.
set -euo pipefail
umask 077

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=tools/release/lib.sh
source "$SCRIPT_DIR/lib.sh"

release_id="${1:-}"
[[ -n "$release_id" ]] || { echo "Usage: $0 vX.Y.Z" >&2; exit 2; }
assert_release_id "$release_id"
commit="$(release_commit_for_tag "$release_id")"
ensure_release_images "$release_id"

previous_id="$(read_release_env_value "$RELEASE_ROOT/.release.env" RELEASE_ID || true)"
previous_commit="$(read_release_env_value "$RELEASE_ROOT/.release.env" RELEASE_COMMIT || true)"
[[ "$previous_id" != "$release_id" ]] || { echo "ERROR: $release_id is already active" >&2; exit 1; }

"$RELEASE_ROOT/tools/backup-all.sh"
write_release_env "$release_id" "$commit"

rollback_on_error() {
  if [[ -n "$previous_id" && -n "$previous_commit" ]]; then
    echo "Deployment failed; restoring application release $previous_id" >&2
    write_release_env "$previous_id" "$previous_commit"
    compose_prod up -d --no-build backend frontend caddy || true
  fi
}
trap rollback_on_error ERR

compose_prod up -d --no-build backend frontend caddy
"$SCRIPT_DIR/smoke.sh" "$release_id"
trap - ERR
printf '%s\n' "$previous_id" > "$RELEASE_ROOT/releases/$release_id/previous-release"
echo "Deployment verified: $release_id (previous=${previous_id:-none})"
