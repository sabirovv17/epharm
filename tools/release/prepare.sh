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
load_env_file "$RELEASE_ROOT/.env.prod"
[[ "${MEDUSA_ENABLED:-}" == "true" ]] || {
  echo "ERROR: MEDUSA_ENABLED=true is required for a production release" >&2
  exit 1
}
"$RELEASE_ROOT/tools/smoke-medusa.sh"
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
