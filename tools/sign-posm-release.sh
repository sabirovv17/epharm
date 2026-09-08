#!/usr/bin/env bash
set -Eeuo pipefail

usage() {
  echo "Usage: $0 <private-key.pem> <platform> <version> <https-url> <release.zip> [mandatory]" >&2
  exit 64
}

[[ $# -ge 5 && $# -le 6 ]] || usage

private_key=$1
platform=$2
version=$3
release_url=$4
release_zip=$5
mandatory=${6:-false}

[[ -f "$private_key" && -r "$private_key" ]] || { echo "Private key is not readable" >&2; exit 66; }
[[ -f "$release_zip" && -r "$release_zip" ]] || { echo "Release ZIP is not readable" >&2; exit 66; }
[[ "$platform" =~ ^[a-zA-Z0-9._-]{1,32}$ ]] || { echo "Invalid platform" >&2; exit 65; }
[[ "$version" =~ ^[0-9]+([.][0-9]+){1,3}([+-][a-zA-Z0-9.-]+)?$ ]] || { echo "Invalid version" >&2; exit 65; }
[[ "$release_url" =~ ^https:// ]] || { echo "Release URL must use HTTPS" >&2; exit 65; }
mandatory=$(printf '%s' "$mandatory" | tr '[:upper:]' '[:lower:]')
[[ "$mandatory" == "true" || "$mandatory" == "false" ]] || { echo "mandatory must be true or false" >&2; exit 65; }

work_dir=$(mktemp -d "${TMPDIR:-/tmp}/epharm-posm-sign.XXXXXX")
trap 'rm -rf -- "$work_dir"' EXIT

sha256=$(shasum -a 256 "$release_zip" | awk '{print $1}')
manifest_file="$work_dir/manifest.txt"
signature_file="$work_dir/manifest.sig"
printf 'epharm-posm-update-v1\n%s\n%s\n%s\n%s\n%s' \
  "$platform" "$version" "$release_url" "$sha256" "$mandatory" > "$manifest_file"

openssl dgst -sha256 -sign "$private_key" -out "$signature_file" "$manifest_file"
signature=$(openssl base64 -A -in "$signature_file")
public_key=$(openssl pkey -in "$private_key" -pubout -outform DER 2>/dev/null | openssl base64 -A)

echo "platform=$platform"
echo "version=$version"
echo "url=$release_url"
echo "sha256=$sha256"
echo "mandatory=$mandatory"
echo "manifestSignature=$signature"
echo "POSM_UPDATE_PUBLIC_KEY_SPKI=$public_key"
