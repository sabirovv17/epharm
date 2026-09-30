#!/usr/bin/env bash
# Creates a protected, checksum-verified rollback bundle before backend-only deploy.
set -euo pipefail
umask 077

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=tools/release/lib.sh
source "$SCRIPT_DIR/lib.sh"

backend_tag="${1:-}"
frontend_tag="${2:-}"
backup_root="${3:-}"
[[ $# -eq 3 ]] || {
  echo 'Usage: prepare-backend-only-backup.sh <running-backend-tag> <pinned-frontend-tag> <absolute-backup-root>' >&2
  exit 2
}
assert_release_id "$backend_tag"
assert_release_id "$frontend_tag"
[[ "$backup_root" == /* && "${backup_root##*/}" == epharm-backend-only-backups ]] || {
  echo 'ERROR: backup root must be a dedicated absolute epharm-backend-only-backups directory' >&2; exit 2;
}
require_command docker
require_command gzip
require_command sha256sum
require_command flock
require_command python3
[[ -r "$RELEASE_ROOT/.env.prod" && -r "$RELEASE_ROOT/.release.env" \
   && -r "$RELEASE_ROOT/docker-compose.prod.yml" && -r "$RELEASE_ROOT/Caddyfile" ]] || {
  echo 'ERROR: production configuration files are missing' >&2; exit 1;
}

running_backend_id="$(docker inspect --format '{{.Image}}' epharm-backend)"
running_frontend_id="$(docker inspect --format '{{.Image}}' epharm-frontend)"
[[ "$running_backend_id" == "$(docker image inspect --format '{{.Id}}' "epharm/backend:$backend_tag")" \
   && "$running_frontend_id" == "$(docker image inspect --format '{{.Id}}' "epharm/frontend:$frontend_tag")" ]] || {
  echo 'ERROR: running application images differ from requested backup tags' >&2; exit 1;
}

if [[ -e "$backup_root" ]]; then
  [[ -d "$backup_root" && ! -L "$backup_root" ]] || {
    echo 'ERROR: backup root is not a real directory' >&2; exit 1;
  }
  python3 - "$backup_root" <<'PY' || {
import os, stat, sys
entry = os.stat(sys.argv[1])
raise SystemExit(0 if entry.st_uid == os.geteuid()
                 and stat.S_IMODE(entry.st_mode) == 0o700 else 1)
PY
    echo 'ERROR: existing backup root must be owned by the caller and mode 0700' >&2; exit 1;
  }
else
  mkdir -m 700 "$backup_root"
fi
mkdir -p "$RELEASE_ROOT/releases/backend-only"
exec 9>"$RELEASE_ROOT/releases/backend-only/.deploy.lock"
flock -n 9 || { echo 'ERROR: another backend-only backup/deploy is active' >&2; exit 1; }
stage="$(mktemp -d "$backup_root/.pre-backend-only.XXXXXXXX")"
cleanup() {
  if [[ -d "$stage" ]]; then rm -rf -- "$stage"; fi
}
trap cleanup EXIT INT TERM
cp -p "$RELEASE_ROOT/.env.prod" "$stage/env.prod"
cp -p "$RELEASE_ROOT/.release.env" "$stage/release.env"
cp -p "$RELEASE_ROOT/docker-compose.prod.yml" "$stage/docker-compose.prod.yml"
cp -p "$RELEASE_ROOT/Caddyfile" "$stage/Caddyfile"
chmod 600 "$stage"/*

docker exec epharm-postgres sh -c 'exec pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' \
  > "$stage/epharm.dump"
[[ -s "$stage/epharm.dump" ]] || { echo 'ERROR: PostgreSQL dump is empty' >&2; exit 1; }
docker exec -i epharm-postgres pg_restore --list < "$stage/epharm.dump" > /dev/null
docker image save "epharm/backend:$backend_tag" | gzip -1 > "$stage/backend-${backend_tag}.tar.gz"
docker image save "epharm/frontend:$frontend_tag" | gzip -1 > "$stage/frontend-${frontend_tag}.tar.gz"
gzip -t "$stage/backend-${backend_tag}.tar.gz" "$stage/frontend-${frontend_tag}.tar.gz"
[[ "$running_backend_id" == "$(docker image inspect --format '{{.Id}}' "epharm/backend:$backend_tag")" \
   && "$running_frontend_id" == "$(docker image inspect --format '{{.Id}}' "epharm/frontend:$frontend_tag")" ]] || {
  echo 'ERROR: image tags changed while creating the backup' >&2; exit 1;
}

(cd "$stage" && sha256sum epharm.dump env.prod release.env docker-compose.prod.yml Caddyfile \
  "backend-${backend_tag}.tar.gz" "frontend-${frontend_tag}.tar.gz" > SHA256SUMS)
(cd "$stage" && sha256sum --status -c SHA256SUMS)
chmod 600 "$stage"/*
suffix="${stage##*.}"
final_dir="$backup_root/pre-backend-only-$(date -u +%Y%m%dT%H%M%SZ)-$suffix"
mv "$stage" "$final_dir"
trap - EXIT INT TERM
echo "Verified backend-only backup: $final_dir"
