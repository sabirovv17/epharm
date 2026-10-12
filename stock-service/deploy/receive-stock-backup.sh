#!/bin/sh
set -eu
umask 077

# Installed as the forced command for one dedicated SSH public key on .76.
if [ -n "${SSH_ORIGINAL_COMMAND:-}" ]; then
  echo 'Commands are not accepted on the stock backup key.' >&2
  exit 2
fi
backup_dir=/home/adm-quasar/offhost-stock-backups/periodic
install -d -m 0700 "$backup_dir"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
tmp="$backup_dir/.stock-$stamp-$$.dump"
final="$backup_dir/stock-$stamp.dump"
trap 'rm -f "$tmp"' EXIT HUP INT TERM

timeout 900 cat > "$tmp"
test -s "$tmp"
hash="$(sha256sum "$tmp" | cut -d' ' -f1)"
mv "$tmp" "$final"
printf '%s  %s\n' "$hash" "$(basename "$final")" > "$final.sha256"
find "$backup_dir" -maxdepth 1 -type f \( -name 'stock-*.dump' -o -name 'stock-*.dump.sha256' \) -mtime +30 -delete
printf '%s\n' "$hash"
