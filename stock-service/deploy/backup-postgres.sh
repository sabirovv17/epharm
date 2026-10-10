#!/usr/bin/env bash
set -euo pipefail
umask 077

if [[ "$(id -u)" -ne 0 ]]; then
  echo 'Run stock backup as root.' >&2
  exit 1
fi
if ! mountpoint -q /srv/epharm-stock-postgres; then
  echo 'Dedicated stock PostgreSQL volume is not mounted.' >&2
  exit 1
fi
if [[ "$(docker inspect epharm-stock-postgres --format '{{.State.Health.Status}}')" != healthy ]]; then
  echo 'Stock PostgreSQL is not healthy.' >&2
  exit 1
fi

backup_dir=/var/backups/epharm-stock/periodic
install -d -m 0700 "$backup_dir"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
tmp="$backup_dir/.stock-$stamp-$$.dump"
final="$backup_dir/stock-$stamp.dump"
trap 'rm -f "$tmp"' EXIT

docker exec epharm-stock-postgres pg_dump -U stock_owner -Fc stocks > "$tmp"
test -s "$tmp"
docker exec -i epharm-stock-postgres pg_restore -l < "$tmp" >/dev/null
local_hash="$(sha256sum "$tmp" | cut -d' ' -f1)"
remote_hash="$(ssh -i /root/.ssh/epharm-stock-backup \
  -o BatchMode=yes -o StrictHostKeyChecking=yes \
  -o ConnectTimeout=10 -o ServerAliveInterval=30 -o ServerAliveCountMax=3 \
  -T adm-quasar@10.10.1.76 < "$tmp")"
if [[ "$remote_hash" != "$local_hash" ]]; then
  echo 'Off-host backup digest mismatch.' >&2
  exit 1
fi

mv "$tmp" "$final"
printf '%s  %s\n' "$local_hash" "$(basename "$final")" > "$final.sha256"
find "$backup_dir" -maxdepth 1 -type f \( -name 'stock-*.dump' -o -name 'stock-*.dump.sha256' \) -mtime +7 -delete
printf 'Stock PostgreSQL backup verified locally and off-host: %s\n' "$(basename "$final")"
