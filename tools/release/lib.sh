#!/usr/bin/env bash

RELEASE_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=tools/ops/lib.sh
source "$RELEASE_SCRIPT_DIR/../ops/lib.sh"
RELEASE_ROOT="$(ops_root_dir)"

assert_release_id() {
  local release_id="$1"
  [[ "$release_id" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]] || {
    echo "ERROR: release id must look like v1.2.3 or v1.2.3-rc.1" >&2
    return 1
  }
}

release_commit_for_tag() {
  local release_id="$1"
  git -C "$RELEASE_ROOT" rev-parse "$release_id^{commit}" 2>/dev/null || {
    echo "ERROR: immutable git tag does not exist: $release_id" >&2
    return 1
  }
}

write_release_env() {
  local release_id="$1"
  local commit="$2"
  local target="${3:-$RELEASE_ROOT/.release.env}"
  local tmp="$target.$$"
  printf 'RELEASE_ID=%s\nRELEASE_COMMIT=%s\n' "$release_id" "$commit" > "$tmp"
  chmod 600 "$tmp"
  mv -f "$tmp" "$target"
}

read_release_env_value() {
  local file="$1"
  local name="$2"
  [[ -r "$file" ]] || return 1
  sed -n "s/^${name}=//p" "$file" | head -n 1
}

compose_prod() {
  docker compose \
    --env-file "$RELEASE_ROOT/.env.prod" \
    --env-file "$RELEASE_ROOT/.release.env" \
    -f "$RELEASE_ROOT/docker-compose.prod.yml" "$@"
}

ensure_release_images() {
  local release_id="$1"
  docker image inspect "epharm/backend:$release_id" >/dev/null
  docker image inspect "epharm/frontend:$release_id" >/dev/null
}
