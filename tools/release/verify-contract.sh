#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=tools/release/lib.sh
source "$SCRIPT_DIR/lib.sh"

release_id="${1:-${GITHUB_REF_NAME:-}}"
[[ -n "$release_id" ]] || { echo "Usage: $0 vX.Y.Z" >&2; exit 2; }
assert_release_id "$release_id"
commit="$(release_commit_for_tag "$release_id")"
head_commit="$(git -C "$RELEASE_ROOT" rev-parse HEAD)"
[[ "$commit" == "$head_commit" ]] || {
  echo "ERROR: $release_id points to $commit, current checkout is $head_commit" >&2
  exit 1
}

version="${release_id#v}"
grep -Eq "^## \\[${version//./\.}\\] - [0-9]{4}-[0-9]{2}-[0-9]{2}$" "$RELEASE_ROOT/CHANGELOG.md" || {
  echo "ERROR: CHANGELOG.md has no dated section for $version" >&2
  exit 1
}

if ! git -C "$RELEASE_ROOT" diff --quiet || ! git -C "$RELEASE_ROOT" diff --cached --quiet; then
  echo "ERROR: tracked files must be clean for an immutable release" >&2
  exit 1
fi

echo "Release contract OK: $release_id -> $commit"
