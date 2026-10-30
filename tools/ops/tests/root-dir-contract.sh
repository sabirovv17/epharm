#!/usr/bin/env bash
# The shared root must be physical even when the scripts are reached through a symlink.
set -euo pipefail
source_root="$(cd "$(dirname "$0")/../../.." && pwd -P)"
test_root="$(mktemp -d "${TMPDIR:-/tmp}/epharm-root-dir.XXXXXXXX")"
test_root="$(cd "$test_root" && pwd -P)"
trap 'rm -rf -- "$test_root"' EXIT
mkdir -p "$test_root/repository/tools/ops"
cp "$source_root/tools/ops/lib.sh" "$test_root/repository/tools/ops/lib.sh"
ln -s "$test_root/repository" "$test_root/current"
for entry in "$test_root/repository" "$test_root/current"; do
  actual="$(bash -c 'source "$1/tools/ops/lib.sh"; ops_root_dir' _ "$entry")"
  [[ "$actual" == "$test_root/repository" ]]
done
echo 'Canonical physical ops_root_dir through real and symlink paths OK'
