#!/usr/bin/env bash
# Archive-host transaction: change only frontend; retain the exact backend/Caddy.
# The operator verifies the immutable signed tag before calling this promotion gate.
set -euo pipefail
umask 077

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=tools/release/lib.sh
source "$SCRIPT_DIR/lib.sh"

if (( $# != 9 && $# != 10 )) || { (( $# == 10 )) && [[ "${10}" != --dry-run ]]; }; then
  echo 'Usage: deploy-frontend-only.sh <frontend-tag> <merged-commit> <frontend-image-id> <pinned-backend-tag> <pinned-backend-commit> <pinned-backend-image-id> <running-frontend-tag> <verified-backup-dir> <compose-sha256> [--dry-run]' >&2
  exit 2
fi
candidate_tag="$1" candidate_commit="$2" candidate_id="$3"
backend_tag="$4" backend_commit="$5" backend_id="$6"
previous_tag="$7" verified_backup_dir="$8" expected_compose_sha="$9"
dry_run="${10:-}"
for tag in "$candidate_tag" "$backend_tag" "$previous_tag"; do assert_release_id "$tag"; done
for commit in "$candidate_commit" "$backend_commit"; do
  [[ "$commit" =~ ^[a-f0-9]{40}$ ]] || { echo 'ERROR: exact 40-hex commits are required' >&2; exit 2; }
done
for identity in "$candidate_id" "$backend_id"; do
  [[ "$identity" =~ ^sha256:[a-f0-9]{64}$ ]] || { echo 'ERROR: exact sha256 image IDs are required' >&2; exit 2; }
done
[[ "$expected_compose_sha" =~ ^[a-f0-9]{64}$ && "$candidate_tag" != "$previous_tag" ]] || {
  echo 'ERROR: a new frontend tag and reviewed Compose SHA-256 are required' >&2; exit 2;
}
for command in docker python3 flock gzip curl; do require_command "$command"; done
for config in .env.prod .release.env docker-compose.prod.yml Caddyfile; do
  [[ -f "$RELEASE_ROOT/$config" && ! -L "$RELEASE_ROOT/$config" && -r "$RELEASE_ROOT/$config" ]] || {
    echo "ERROR: missing or unsafe production configuration $config" >&2; exit 1;
  }
done
mkdir -p "$RELEASE_ROOT/releases/backend-only" "$RELEASE_ROOT/releases/frontend-only"
chmod 700 "$RELEASE_ROOT/releases/frontend-only"
# Same lock as archive/backend-only backup, deploy and rollback transactions.
exec 9>"$RELEASE_ROOT/releases/backend-only/.deploy.lock"
flock -n 9 || { echo 'ERROR: another application backup/deployment is active' >&2; exit 1; }
verify_release_source "$candidate_commit"
verify_production_catalog_source
[[ "$(sha256_file "$RELEASE_ROOT/docker-compose.prod.yml")" == "$expected_compose_sha" ]] || {
  echo 'ERROR: live Compose differs from the reviewed source' >&2; exit 1;
}

# Use the existing two-image backup producer; a UI-only transaction never restores its DB dump.
python3 - "$verified_backup_dir" "$backend_tag" "$previous_tag" <<'PY' || {
import hashlib, os, stat, sys, time
root, backend, frontend = sys.argv[1:]
if (not os.path.isabs(root) or os.path.islink(root) or not os.path.isdir(root)
        or any(ord(character) < 32 for character in root)):
    raise SystemExit(1)
entry = os.stat(root)
if entry.st_uid != os.geteuid() or stat.S_IMODE(entry.st_mode) != 0o700:
    raise SystemExit(1)
manifest = os.path.join(root, 'SHA256SUMS')
if not os.path.isfile(manifest) or os.path.islink(manifest):
    raise SystemExit(1)
if not 0 <= time.time() - os.stat(manifest).st_mtime <= 86400:
    raise SystemExit(1)
required = {'epharm.dump', 'env.prod', 'release.env', 'docker-compose.prod.yml', 'Caddyfile',
            f'backend-{backend}.tar.gz', f'frontend-{frontend}.tar.gz'}
seen = set()
with open(manifest, encoding='ascii') as source:
    for line in source:
        row = line.rstrip('\n').split('  ', 1)
        if len(row) != 2:
            raise SystemExit(1)
        expected, name = row
        if (len(expected) != 64 or any(c not in '0123456789abcdef' for c in expected)
                or os.path.basename(name) != name or not name or name in seen):
            raise SystemExit(1)
        seen.add(name)
        path = os.path.join(root, name)
        if os.path.islink(path) or not os.path.isfile(path) or os.path.getsize(path) == 0:
            raise SystemExit(1)
        digest = hashlib.sha256()
        with open(path, 'rb') as artifact:
            for block in iter(lambda: artifact.read(1024 * 1024), b''):
                digest.update(block)
        if digest.hexdigest() != expected:
            raise SystemExit(1)
if not required.issubset(seen):
    raise SystemExit(1)
PY
  echo 'ERROR: fresh verified rollback bundle is missing, incomplete or altered' >&2; exit 1;
}
assert_backup_configs() {
  local pair backup_name live_name
  for pair in 'env.prod:.env.prod' 'release.env:.release.env' 'docker-compose.prod.yml:docker-compose.prod.yml' 'Caddyfile:Caddyfile'; do
    backup_name="${pair%%:*}"; live_name="${pair#*:}"
    [[ "$(sha256_file "$verified_backup_dir/$backup_name")" == "$(sha256_file "$RELEASE_ROOT/$live_name")" ]] || {
      echo "ERROR: $live_name changed after the verified backup" >&2; return 1;
    }
  done
}
assert_backup_configs
docker exec -i epharm-postgres pg_restore --list < "$verified_backup_dir/epharm.dump" > /dev/null
gzip -t "$verified_backup_dir/backend-${backend_tag}.tar.gz" "$verified_backup_dir/frontend-${previous_tag}.tar.gz"
image_id() { docker image inspect --format '{{.Id}}' "$1"; }
image_label() { docker image inspect --format "{{index .Config.Labels \"$2\"}}" "$1"; }
[[ "$(image_id "epharm/backend:$backend_tag")" == "$backend_id" \
   && "$(image_label "epharm/backend:$backend_tag" org.opencontainers.image.version)" == "$backend_tag" \
   && "$(image_label "epharm/backend:$backend_tag" org.opencontainers.image.revision)" == "$backend_commit" ]] || {
  echo 'ERROR: pinned backend image/version/commit differs from the approved live artifact' >&2; exit 1;
}
[[ "$(docker inspect --format '{{.Image}}' epharm-backend)" == "$backend_id" ]] || {
  echo 'ERROR: running backend differs from the approved pinned image' >&2; exit 1;
}
backend_container="$(docker inspect --format '{{.Id}}' epharm-backend)"
caddy_container="$(docker inspect --format '{{.Id}}' epharm-caddy)"
caddy_id="$(docker inspect --format '{{.Image}}' epharm-caddy)"
previous_id="$(image_id "epharm/frontend:$previous_tag")"
previous_container="$(docker inspect --format '{{.Id}}' epharm-frontend)"
previous_commit="$(image_label "epharm/frontend:$previous_tag" org.opencontainers.image.revision)"
[[ "$previous_id" =~ ^sha256:[a-f0-9]{64}$ && "$previous_commit" =~ ^[A-Za-z0-9_.-]+$ \
   && "$(image_label "epharm/frontend:$previous_tag" org.opencontainers.image.version)" == "$previous_tag" \
   && "$(docker inspect --format '{{.Image}}' epharm-frontend)" == "$previous_id" ]] || {
  echo 'ERROR: running frontend does not match the named rollback image' >&2; exit 1;
}
[[ "$(image_id "epharm/frontend:$candidate_tag")" == "$candidate_id" \
   && "$(image_label "epharm/frontend:$candidate_tag" org.opencontainers.image.version)" == "$candidate_tag" \
   && "$(image_label "epharm/frontend:$candidate_tag" org.opencontainers.image.revision)" == "$candidate_commit" ]] || {
  echo 'ERROR: frontend candidate image/version/commit differs from the approved artifact' >&2; exit 1;
}
python3 "$SCRIPT_DIR/verify-image-archive.py" "$verified_backup_dir/backend-${backend_tag}.tar.gz" "epharm/backend:$backend_tag" > /dev/null
python3 "$SCRIPT_DIR/verify-image-archive.py" "$verified_backup_dir/frontend-${previous_tag}.tar.gz" "epharm/frontend:$previous_tag" > /dev/null
verify_financial_transition "$backend_id"
verify_admin_image "$candidate_id"

assert_transaction_dir() {
  python3 - "$RELEASE_ROOT/releases/frontend-only" "$transaction_dir" <<'PY'
import os, pathlib, stat, sys
parent_raw, transaction_raw = sys.argv[1:]
parent, transaction = map(pathlib.Path, (parent_raw, transaction_raw))
try:
    if str(parent) != parent_raw or str(transaction) != transaction_raw:
        raise ValueError('directory path contains noncanonical components')
    if transaction.parent != parent:
        raise ValueError('transaction is not a direct child of the frontend release directory')
    for path in (parent, transaction):
        info = path.lstat()
        if (not path.is_absolute() or path.resolve(strict=True) != path or path.is_symlink()
                or not stat.S_ISDIR(info.st_mode) or info.st_uid != os.geteuid()
                or stat.S_IMODE(info.st_mode) != 0o700
                or any(ord(character) < 32 or ord(character) == 127 for character in str(path))):
            raise ValueError('directory must be absolute, canonical, caller-owned and mode 0700')
except (OSError, RuntimeError, ValueError) as error:
    raise SystemExit(f'ERROR: unsafe frontend transaction directory: {error}')
PY
}
transaction_dir="$(mktemp -d "$RELEASE_ROOT/releases/frontend-only/${candidate_tag}-$(date -u +%Y%m%dT%H%M%SZ)-XXXXXXXX")"
assert_transaction_dir
cp -p "$RELEASE_ROOT/.release.env" "$transaction_dir/original.release.env"
original_release_sha="$(sha256_file "$RELEASE_ROOT/.release.env")"
original_env_sha="$(sha256_file "$RELEASE_ROOT/.env.prod")"
original_caddy_sha="$(sha256_file "$RELEASE_ROOT/Caddyfile")"
printf 'backend_tag=%s\nbackend_commit=%s\nbackend_id=%s\nbackend_container=%s\ncaddy_id=%s\ncaddy_container=%s\nprevious_frontend_tag=%s\nprevious_frontend_commit=%s\nprevious_frontend_id=%s\nprevious_frontend_container=%s\ncandidate_frontend_tag=%s\ncandidate_frontend_commit=%s\ncandidate_frontend_id=%s\nbackup_dir=%s\n' \
  "$backend_tag" "$backend_commit" "$backend_id" "$backend_container" "$caddy_id" "$caddy_container" \
  "$previous_tag" "$previous_commit" "$previous_id" "$previous_container" "$candidate_tag" "$candidate_commit" "$candidate_id" \
  "$verified_backup_dir" > "$transaction_dir/manifest"
write_pins() {
  printf 'RELEASE_ID=%s\nRELEASE_COMMIT=%s\nBACKEND_RELEASE_ID=%s\nBACKEND_RELEASE_COMMIT=%s\nFRONTEND_RELEASE_ID=%s\nFRONTEND_RELEASE_COMMIT=%s\n' \
    "$backend_tag" "$backend_commit" "$backend_tag" "$backend_commit" "$2" "$3" > "$1"
  chmod 600 "$1"
}
write_pins "$transaction_dir/rollback.release.env" "$previous_tag" "$previous_commit"
staged_env="$(mktemp "$RELEASE_ROOT/.release.frontend-candidate.XXXXXX")"
write_pins "$staged_env" "$candidate_tag" "$candidate_commit"
candidate_release_sha="$(sha256_file "$staged_env")"
rollback_ready="$(mktemp "$RELEASE_ROOT/.release.frontend-rollback.XXXXXX")"
cp -p "$transaction_dir/rollback.release.env" "$rollback_ready"
rollback_release_sha="$(sha256_file "$rollback_ready")"
switched=false
assert_previous_frontend() {
  [[ "$(docker inspect --format '{{.Image}}' epharm-frontend)" == "$previous_id" \
     && "$(docker inspect --format '{{.Id}}' epharm-frontend)" == "$previous_container" ]] || {
    echo 'ERROR: frontend changed since preflight; refusing to overwrite another operator' >&2; return 1;
  }
}
assert_fixed_components() {
  [[ "$(docker inspect --format '{{.Image}}' epharm-backend)" == "$backend_id" \
     && "$(docker inspect --format '{{.Id}}' epharm-backend)" == "$backend_container" \
     && "$(docker inspect --format '{{.Image}}' epharm-caddy)" == "$caddy_id" \
     && "$(docker inspect --format '{{.Id}}' epharm-caddy)" == "$caddy_container" ]] || {
    echo 'ERROR: backend or Caddy changed; no automatic write to either component is permitted' >&2; return 1;
  }
}
assert_protected_configs() {
  [[ "$(sha256_file "$RELEASE_ROOT/.env.prod")" == "$original_env_sha" \
     && "$(sha256_file "$RELEASE_ROOT/Caddyfile")" == "$original_caddy_sha" \
     && "$(sha256_file "$RELEASE_ROOT/docker-compose.prod.yml")" == "$expected_compose_sha" ]] || {
    echo 'ERROR: protected configuration changed during the frontend transaction' >&2; return 1;
  }
}
rollback_on_exit() {
  local status=$? rollback_ok=true rollback_id="$previous_id" current_release_sha current_frontend_id current_frontend_names
  trap - EXIT
  if [[ "$switched" == true ]]; then
    assert_fixed_components && assert_protected_configs || rollback_ok=false
    current_release_sha="$(sha256_file "$RELEASE_ROOT/.release.env" 2>/dev/null || true)"
    [[ "$current_release_sha" == "$candidate_release_sha" || "$current_release_sha" == "$original_release_sha" ]] || rollback_ok=false
    current_frontend_id="$(docker inspect --format '{{.Image}}' epharm-frontend 2>/dev/null || true)"
    if [[ -z "$current_frontend_id" ]]; then
      # Compose may remove the old container before failing to create its replacement.
      if ! current_frontend_names="$(docker container ls --all --filter 'name=^/epharm-frontend$' --format '{{.Names}}')"; then
        rollback_ok=false
      elif [[ -n "$current_frontend_names" ]]; then
        rollback_ok=false
      fi
    elif [[ "$current_frontend_id" != "$candidate_id" && "$current_frontend_id" != "$previous_id" ]]; then
      rollback_ok=false
    fi
    if [[ "$rollback_ok" == true ]]; then
      if [[ "$(image_id "epharm/frontend:$previous_tag" 2>/dev/null || true)" != "$previous_id" ]]; then
        gzip -dc "$verified_backup_dir/frontend-${previous_tag}.tar.gz" | docker image load > /dev/null || rollback_ok=false
        python3 "$SCRIPT_DIR/verify-image-archive.py" "$verified_backup_dir/frontend-${previous_tag}.tar.gz" \
          "epharm/frontend:$previous_tag" > /dev/null || rollback_ok=false
        rollback_id="$(image_id "epharm/frontend:$previous_tag" 2>/dev/null || true)"
        [[ "$rollback_id" =~ ^sha256:[a-f0-9]{64}$ ]] || rollback_ok=false
      fi
    fi
    if [[ "$rollback_ok" == true ]]; then
      mv -f "$rollback_ready" "$RELEASE_ROOT/.release.env" || rollback_ok=false
    fi
    if [[ "$rollback_ok" == true ]]; then
      compose_prod up -d --no-build --no-deps --force-recreate frontend || rollback_ok=false
      [[ "$(docker inspect --format '{{.Image}}' epharm-frontend 2>/dev/null || true)" == "$rollback_id" ]] || rollback_ok=false
      assert_fixed_components && assert_protected_configs || rollback_ok=false
      SMOKE_BACKEND_COMMIT="$backend_commit" SMOKE_FRONTEND_COMMIT="$previous_commit" SMOKE_ADMIN_IMAGE_ID='' \
        "$SCRIPT_DIR/smoke.sh" "$backend_tag" "$previous_tag" || rollback_ok=false
      [[ "$(sha256_file "$RELEASE_ROOT/.release.env" 2>/dev/null || true)" == "$rollback_release_sha" ]] || rollback_ok=false
    fi
    if [[ "$rollback_ok" == true ]]; then
      echo "Frontend rollback verified: $previous_tag; backend/Caddy unchanged" >&2
    else
      echo "CRITICAL: frontend recovery not verified; inspect $transaction_dir; backend/Caddy were not intentionally changed" >&2
    fi
  fi
  rm -f -- "$staged_env" "$rollback_ready"
  exit "$status"
}
trap rollback_on_exit EXIT
trap 'exit 1' INT TERM

# Secret-bearing Compose JSON stays inside the parser; only frozen-model hashes leave it.
verify_compose() {
  docker compose --env-file "$RELEASE_ROOT/.env.prod" --env-file "$1" \
    -f "$RELEASE_ROOT/docker-compose.prod.yml" config --format json \
    | python3 -c 'import hashlib,json,sys; s=json.load(sys.stdin)["services"]; b=s["backend"]; f=s["frontend"]; e=b["environment"]; a=f.get("build",{}).get("args",{}); bt,bc,ft,fc=sys.argv[1:]; ok=(b["image"]=="epharm/backend:"+bt and e.get("RELEASE_ID")==bt and e.get("RELEASE_COMMIT")==bc and f["image"]=="epharm/frontend:"+ft and a.get("VITE_RELEASE_ID")==ft and a.get("VITE_RELEASE_COMMIT")==fc); assert ok,"Rendered component pins differ"; print(hashlib.sha256(json.dumps({k:s[k] for k in ("backend","caddy")},sort_keys=True,separators=(",",":")).encode()).hexdigest())' \
    "$backend_tag" "$backend_commit" "$2" "$3"
}
candidate_frozen_sha="$(verify_compose "$staged_env" "$candidate_tag" "$candidate_commit")"
rollback_frozen_sha="$(verify_compose "$rollback_ready" "$previous_tag" "$previous_commit")"
[[ "$candidate_frozen_sha" == "$rollback_frozen_sha" ]] || {
  echo 'ERROR: frontend release pins would change backend/Caddy configuration' >&2; exit 1;
}
assert_previous_frontend
SMOKE_BACKEND_COMMIT="$backend_commit" SMOKE_FRONTEND_COMMIT="$previous_commit" SMOKE_ADMIN_IMAGE_ID='' \
  "$SCRIPT_DIR/smoke.sh" "$backend_tag" "$previous_tag"
assert_fixed_components
assert_previous_frontend
assert_backup_configs
if [[ "$dry_run" == --dry-run ]]; then
  echo 'Frontend-only dry-run passed; no application container or release pins changed'
  exit 0
fi
verify_release_source "$candidate_commit"
verify_financial_transition "$backend_id"
[[ "$(image_id "epharm/frontend:$candidate_tag")" == "$candidate_id" \
   && "$(image_id "epharm/backend:$backend_tag")" == "$backend_id" ]] || {
  echo 'ERROR: candidate frontend or pinned backend tag changed before switch' >&2; exit 1;
}
assert_fixed_components
assert_previous_frontend
assert_backup_configs
assert_transaction_dir
switched=true
mv -f "$staged_env" "$RELEASE_ROOT/.release.env"
compose_prod up -d --no-build --no-deps --force-recreate frontend
SMOKE_BACKEND_COMMIT="$backend_commit" SMOKE_FRONTEND_COMMIT="$candidate_commit" \
  SMOKE_ADMIN_IMAGE_ID="$candidate_id" "$SCRIPT_DIR/smoke.sh" "$backend_tag" "$candidate_tag"
[[ "$(docker inspect --format '{{.Image}}' epharm-frontend)" == "$candidate_id" \
   && "$(sha256_file "$RELEASE_ROOT/.release.env")" == "$candidate_release_sha" ]] || {
  echo 'ERROR: frontend image or release pins differ from the approved candidate' >&2; exit 1;
}
assert_fixed_components
assert_protected_configs
assert_transaction_dir
active_tmp="$(mktemp "$RELEASE_ROOT/releases/frontend-only/.active-transaction.XXXXXX")"
printf '%s\n' "$transaction_dir" > "$active_tmp"
mv -f "$active_tmp" "$RELEASE_ROOT/releases/frontend-only/active-transaction"
switched=false
echo "Frontend-only deployment verified: $candidate_tag; backend $backend_tag and Caddy unchanged"
