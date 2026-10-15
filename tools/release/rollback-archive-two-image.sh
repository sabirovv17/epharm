#!/usr/bin/env bash
# Restore only the saved predecessor of the currently running archive transaction.
set -euo pipefail
umask 077
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=tools/release/lib.sh
source "$SCRIPT_DIR/lib.sh"
transaction_dir="${1:-}"
backup_root="${2:-}"
[[ $# -eq 2 && "$transaction_dir" == /* ]] || {
  echo 'Usage: rollback-archive-two-image.sh <saved-archive-transaction-dir> <absolute-epharm-backend-only-backups-root>' >&2; exit 2;
}
for command in docker python3 flock; do require_command "$command"; done
# Read protected records as data. Never source/eval a transaction or release env.
coordinates="$(python3 - "$RELEASE_ROOT" "$transaction_dir" <<'PY'
import os, pathlib, re, stat, sys
root, state = map(pathlib.Path, sys.argv[1:])
protected = root / 'releases/archive-two-image'
def secure(path, directory=False):
    info = path.lstat()
    expected = 0o700 if directory else 0o600
    if path.is_symlink() or info.st_uid != os.geteuid() or stat.S_IMODE(info.st_mode) != expected:
        raise SystemExit('ERROR: saved archive state must be caller-owned and mode 0700/0600')
    if not (stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode)):
        raise SystemExit('ERROR: invalid archive state file type')
if state.resolve(strict=True) != state or state.parent != protected.resolve(strict=True):
    raise SystemExit('ERROR: transaction must be a real direct child of the archive release directory')
secure(protected, True)
secure(state, True)
def read(path, protected_file=False):
    if protected_file: secure(path)
    if path.stat().st_size > 16384: raise SystemExit('ERROR: oversized release metadata')
    rows = {}
    for line in path.read_text().splitlines():
        key, separator, value = line.partition('=')
        if not separator or key in rows: raise SystemExit('ERROR: duplicate or malformed release metadata')
        rows[key] = value
    return rows
manifest = read(state / 'manifest', True)
pins = read(state / 'rollback.release.env', True)
current = read(root / '.release.env')
allowed = {'RELEASE_ID', 'RELEASE_COMMIT', 'BACKEND_RELEASE_ID', 'BACKEND_RELEASE_COMMIT', 'FRONTEND_RELEASE_ID', 'FRONTEND_RELEASE_COMMIT'}
if set(pins) != allowed or set(current) != allowed:
    raise SystemExit('ERROR: release pins contain missing or unexpected keys')
values = []
for phase, metadata in (('previous', pins), ('candidate', current)):
    for component in ('backend', 'frontend'):
        tag, identity = manifest[f'{phase}_{component}_tag'], manifest[f'{phase}_{component}_id']
        commit = metadata[f'{component.upper()}_RELEASE_COMMIT']
        if not re.fullmatch(r'v[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?', tag) or not re.fullmatch(r'sha256:[a-f0-9]{64}', identity):
            raise SystemExit('ERROR: invalid saved image coordinate')
        if not re.fullmatch(r'[A-Za-z0-9_.-]+', commit) or metadata[f'{component.upper()}_RELEASE_ID'] != tag:
            raise SystemExit('ERROR: release pins disagree with the saved transaction')
        values.extend((tag, identity, commit))
    if metadata.get('RELEASE_ID') != metadata['BACKEND_RELEASE_ID'] or metadata.get('RELEASE_COMMIT') != metadata['BACKEND_RELEASE_COMMIT']:
        raise SystemExit('ERROR: generic and component release pins disagree')
caddy = manifest['previous_caddy_container']
if not re.fullmatch(r'[a-f0-9]{64}', caddy): raise SystemExit('ERROR: invalid saved Caddy identity')
print('|'.join(values + [caddy]))
PY
)"
# Candidate revisions are read indirectly by check_images through commit_key.
# shellcheck disable=SC2034
IFS='|' read -r previous_backend_tag previous_backend_id previous_backend_commit previous_frontend_tag previous_frontend_id previous_frontend_commit \
  candidate_backend_tag candidate_backend_id candidate_backend_commit candidate_frontend_tag candidate_frontend_id candidate_frontend_commit caddy_id <<< "$coordinates"
manifest_sha="$(sha256_file "$transaction_dir/manifest")"
rollback_sha="$(sha256_file "$transaction_dir/rollback.release.env")"
image_id() { docker image inspect --format '{{.Id}}' "$1"; }
image_label() { docker image inspect --format "{{index .Config.Labels \"$2\"}}" "$1"; }
check_images() {
  local component phase tag identity revision
  for phase in previous candidate; do
    [[ "${1:-}" == candidate && "$phase" == previous ]] && continue
    for component in backend frontend; do
      local tag_key="${phase}_${component}_tag" id_key="${phase}_${component}_id" commit_key="${phase}_${component}_commit"
      tag="${!tag_key}"; identity="${!id_key}"; revision="${!commit_key}"
      [[ "$(image_id "epharm/$component:$tag" 2>/dev/null || true)" == "$identity" \
        && "$(image_label "epharm/$component:$tag" org.opencontainers.image.version)" == "$tag" \
        && "$(image_label "epharm/$component:$tag" org.opencontainers.image.revision)" == "$revision" ]] || {
        echo 'ERROR: saved image is absent or changed; restore its exact tag from the verified archive before retrying' >&2; return 1;
      }
    done
  done
}
check_running() {
  [[ "$(docker inspect --format '{{.Image}}' epharm-backend)" == "$1" \
    && "$(docker inspect --format '{{.Image}}' epharm-frontend)" == "$2" \
    && "$(docker inspect --format '{{.Id}}' epharm-caddy)" == "$caddy_id" ]]
}
check_images
check_running "$candidate_backend_id" "$candidate_frontend_id" || {
  echo 'ERROR: current application is not the saved candidate; rollback denied' >&2; exit 1;
}
fresh_output="$("$SCRIPT_DIR/prepare-backend-only-backup.sh" "$candidate_backend_tag" "$candidate_frontend_tag" "$backup_root")"
fresh_backup="${fresh_output#Verified backend-only backup: }"
[[ "$fresh_backup" == "$backup_root/"* && -d "$fresh_backup" ]] || {
  echo 'ERROR: fresh rollback backup did not return its protected bundle' >&2; exit 1;
}
exec 9>"$RELEASE_ROOT/releases/backend-only/.deploy.lock"
flock -n 9 || { echo 'ERROR: another application backup/deployment is active' >&2; exit 1; }
check_images
check_running "$candidate_backend_id" "$candidate_frontend_id"
[[ "$(sha256_file "$transaction_dir/manifest")" == "$manifest_sha" \
  && "$(sha256_file "$transaction_dir/rollback.release.env")" == "$rollback_sha" ]] || {
  echo 'ERROR: saved rollback state changed during the backup' >&2; exit 1;
}
configs_unchanged() {
  local pair
  for pair in 'env.prod:.env.prod' 'docker-compose.prod.yml:docker-compose.prod.yml' 'Caddyfile:Caddyfile'; do
    [[ "$(sha256_file "$fresh_backup/${pair%%:*}")" == "$(sha256_file "$RELEASE_ROOT/${pair#*:}")" ]] || return 1
  done
}
configs_unchanged
[[ "$(sha256_file "$fresh_backup/release.env")" == "$(sha256_file "$RELEASE_ROOT/.release.env")" ]]
docker compose --env-file "$RELEASE_ROOT/.env.prod" --env-file "$transaction_dir/rollback.release.env" \
  -f "$RELEASE_ROOT/docker-compose.prod.yml" config --format json \
  | python3 -c 'import json,sys; s=json.load(sys.stdin)["services"]; x=sys.argv[1:]; b=s["backend"]; f=s["frontend"]; a=f.get("build",{}).get("args",{}); sys.exit(0 if b["image"]=="epharm/backend:"+x[0] and b["environment"]["RELEASE_ID"]==x[0] and b["environment"]["RELEASE_COMMIT"]==x[1] and f["image"]=="epharm/frontend:"+x[2] and a.get("VITE_RELEASE_ID")==x[2] and a.get("VITE_RELEASE_COMMIT")==x[3] else 1)' \
    "$previous_backend_tag" "$previous_backend_commit" "$previous_frontend_tag" "$previous_frontend_commit"
backup_marker="$(mktemp "$transaction_dir/.pre-rollback-backup.XXXXXX")"
printf '%s\n' "$fresh_backup" > "$backup_marker"
mv -f "$backup_marker" "$transaction_dir/pre-rollback-backup"
forward_ready="$(mktemp "$RELEASE_ROOT/.release.archive-forward.XXXXXX")"
rollback_ready="$(mktemp "$RELEASE_ROOT/.release.archive-rollback.XXXXXX")"
cp -p "$RELEASE_ROOT/.release.env" "$forward_ready"
cp -p "$transaction_dir/rollback.release.env" "$rollback_ready"
chmod 600 "$forward_ready" "$rollback_ready"
switched=false
smoke_saved() {
  SMOKE_BACKEND_COMMIT='' SMOKE_FRONTEND_COMMIT='' SMOKE_ADMIN_IMAGE_ID='' "$SCRIPT_DIR/smoke.sh" "$1" "$2"
}
restore_on_exit() {
  local status=$? restore_ok=true
  trap - EXIT
  if [[ "$switched" == true ]]; then
    echo 'ERROR: archive rollback failed; restoring the current candidate' >&2
    check_images candidate || restore_ok=false
    if [[ "$restore_ok" == true ]]; then
      mv -f "$forward_ready" "$RELEASE_ROOT/.release.env" || restore_ok=false
      compose_prod up -d --no-build --no-deps --force-recreate backend frontend || restore_ok=false
    fi
    check_running "$candidate_backend_id" "$candidate_frontend_id" || restore_ok=false
    configs_unchanged || restore_ok=false
    smoke_saved "$candidate_backend_tag" "$candidate_frontend_tag" || restore_ok=false
    [[ "$restore_ok" == true ]] || echo "CRITICAL: candidate restoration failed; inspect $transaction_dir" >&2
  fi
  rm -f -- "$forward_ready" "$rollback_ready"
  exit "$status"
}
trap restore_on_exit EXIT
trap 'exit 1' INT TERM
check_images
check_running "$candidate_backend_id" "$candidate_frontend_id"
[[ "$(sha256_file "$rollback_ready")" == "$rollback_sha" ]]
switched=true
mv -f "$rollback_ready" "$RELEASE_ROOT/.release.env"
compose_prod up -d --no-build --no-deps --force-recreate backend frontend
smoke_saved "$previous_backend_tag" "$previous_frontend_tag"
check_running "$previous_backend_id" "$previous_frontend_id"
configs_unchanged
rollback_marker="$(mktemp "$transaction_dir/.rolled-back.XXXXXX")"
printf 'rolled_back_at=%s\n' "$(date -u +%FT%TZ)" > "$rollback_marker"
mv -f "$rollback_marker" "$transaction_dir/rolled-back"
switched=false
echo "Archive rollback verified: $candidate_backend_tag/$candidate_frontend_tag -> $previous_backend_tag/$previous_frontend_tag"
