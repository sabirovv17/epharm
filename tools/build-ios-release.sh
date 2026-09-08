#!/usr/bin/env bash
set -Eeuo pipefail

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

script_dir="$(CDPATH='' cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(CDPATH='' cd -- "${script_dir}/.." && pwd)"
flutter_bin="${FLUTTER_BIN:-/Users/amir/development/flutter/bin/flutter}"
team_id="${APPLE_DEVELOPMENT_TEAM:-}"
api_base="${API_BASE:-https://epharm.inkar.kz}"
export_options="${repo_root}/ios/ExportOptions.plist"
signing_config="${repo_root}/ios/Flutter/Signing.xcconfig"

[[ -x "$flutter_bin" ]] || die "Flutter executable not found; set FLUTTER_BIN"
command -v xcodebuild >/dev/null 2>&1 || die "Xcode command-line tools are required"
[[ "$team_id" =~ ^[A-Z0-9]{10}$ ]] || die "APPLE_DEVELOPMENT_TEAM must be the paid team's 10-character ID"
[[ "$api_base" =~ ^https://[A-Za-z0-9.-]+(:[0-9]+)?/?$ ]] \
  || die "API_BASE must be an HTTPS origin"

if ! security find-identity -v -p codesigning 2>/dev/null | grep -q '"Apple Distribution:'; then
  die "No Apple Distribution identity is installed; enroll the paid team in Xcode first"
fi

printf 'APPLE_DEVELOPMENT_TEAM = %s\n' "$team_id" >"$signing_config"
sed "s/ABCDE12345/${team_id}/g" \
  "${repo_root}/ios/ExportOptions.plist.example" >"$export_options"

cd "$repo_root"
"$flutter_bin" pub get
"$flutter_bin" build ipa --release \
  --export-options-plist="$export_options" \
  --dart-define=USE_API=true \
  --dart-define="API_BASE=${api_base}"

ipa_path="$(find "${repo_root}/build/ios/ipa" -maxdepth 1 -type f -name '*.ipa' -print -quit)"
[[ -n "$ipa_path" ]] || die "IPA export completed without an IPA artifact"
codesign --verify --deep --strict \
  "${repo_root}/build/ios/archive/Runner.xcarchive/Products/Applications/Runner.app"
printf 'OK: strict signed IPA is ready: %s\n' "$ipa_path"
