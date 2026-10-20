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
verify_release_source "$commit"

require_command docker
verify_production_catalog_source
# Website outages use the existing read-only local snapshot. The post-deploy catalogue smoke
# validates the effective backend response; never contact the retired remote origin here.
mkdir -p "$RELEASE_ROOT/releases/$release_id"
manifest="$RELEASE_ROOT/releases/$release_id/manifest.json"
if [[ -e "$manifest" ]]; then
  echo "ERROR: release manifest already exists; release ids are immutable: $manifest" >&2
  exit 1
fi

# Build with the candidate release coordinates without changing the file that
# records the currently active production release.  deploy.sh reads
# .release.env before switching images so it can create the rollback pointer;
# mutating it here makes a freshly prepared release look already deployed.
build_release_env="$(mktemp "$RELEASE_ROOT/.release.prepare.XXXXXX")"
cleanup_build_release_env() {
  rm -f "$build_release_env"
}
trap cleanup_build_release_env EXIT
write_release_env "$release_id" "$commit" "$build_release_env"
docker compose \
  --env-file "$RELEASE_ROOT/.env.prod" \
  --env-file "$build_release_env" \
  -f "$RELEASE_ROOT/docker-compose.prod.yml" \
  build --pull backend frontend
ensure_release_images "$release_id"
verify_merchandising_image "$(docker image inspect --format '{{.Id}}' "epharm/backend:$release_id")"
verify_admin_image "$(docker image inspect --format '{{.Id}}' "epharm/frontend:$release_id")"

backend_image_id="$(docker image inspect --format '{{.Id}}' "epharm/backend:$release_id")"
frontend_image_id="$(docker image inspect --format '{{.Id}}' "epharm/frontend:$release_id")"
verify_release_source "$commit"
created_at="$(date -u +%FT%TZ)"
tmp="$manifest.$$"
printf '{\n  "releaseId": "%s",\n  "commit": "%s",\n  "createdAt": "%s",\n  "images": {\n    "backend": "%s",\n    "frontend": "%s"\n  }\n}\n' \
  "$release_id" "$commit" "$created_at" "$backend_image_id" "$frontend_image_id" > "$tmp"
mv "$tmp" "$manifest"
chmod 600 "$manifest"

echo "Prepared immutable release $release_id ($commit)"
echo "Manifest: $manifest"
