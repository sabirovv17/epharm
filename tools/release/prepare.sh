#!/usr/bin/env bash
# Builds immutable application images and records their content-addressed image IDs.
set -euo pipefail
umask 077

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=tools/release/lib.sh
source "$SCRIPT_DIR/lib.sh"

release_id="${1:-}"
[[ -n "$release_id" ]] || { echo "Usage: $0 vX.Y.Z" >&2; exit 2; }
"$SCRIPT_DIR/verify-contract.sh" "$release_id"
commit="$(release_commit_for_tag "$release_id")"

require_command docker
[[ -r "$RELEASE_ROOT/.env.prod" ]] || { echo "ERROR: missing $RELEASE_ROOT/.env.prod" >&2; exit 1; }
mkdir -p "$RELEASE_ROOT/releases/$release_id"
manifest="$RELEASE_ROOT/releases/$release_id/manifest.json"
if [[ -e "$manifest" ]]; then
  echo "ERROR: release manifest already exists; release ids are immutable: $manifest" >&2
  exit 1
fi

write_release_env "$release_id" "$commit"
compose_prod build --pull backend frontend
ensure_release_images "$release_id"

backend_image_id="$(docker image inspect --format '{{.Id}}' "epharm/backend:$release_id")"
frontend_image_id="$(docker image inspect --format '{{.Id}}' "epharm/frontend:$release_id")"
created_at="$(date -u +%FT%TZ)"
tmp="$manifest.$$"
printf '{\n  "releaseId": "%s",\n  "commit": "%s",\n  "createdAt": "%s",\n  "images": {\n    "backend": "%s",\n    "frontend": "%s"\n  }\n}\n' \
  "$release_id" "$commit" "$created_at" "$backend_image_id" "$frontend_image_id" > "$tmp"
mv "$tmp" "$manifest"
chmod 600 "$manifest"

echo "Prepared immutable release $release_id ($commit)"
echo "Manifest: $manifest"
