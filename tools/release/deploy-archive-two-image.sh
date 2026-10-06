#!/usr/bin/env bash
# Transactional two-image release for an archive-based host without a Git checkout.
# Caddy, databases, volumes and the protected .env.prod are deliberately untouched.
set -euo pipefail
umask 077

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=tools/release/lib.sh
source "$SCRIPT_DIR/lib.sh"

if (( $# != 9 && $# != 10 )); then
  echo 'Usage: deploy-archive-two-image.sh <backend-tag> <frontend-tag> <merged-commit> <backend-image-id> <frontend-image-id> <running-backend-tag> <running-frontend-tag> <verified-backup-dir> <compose-sha256> [--dry-run]' >&2
  exit 2
fi
if (( $# == 10 )) && [[ "${10}" != --dry-run ]]; then
  echo 'Usage: deploy-archive-two-image.sh <backend-tag> <frontend-tag> <merged-commit> <backend-image-id> <frontend-image-id> <running-backend-tag> <running-frontend-tag> <verified-backup-dir> <compose-sha256> [--dry-run]' >&2
  exit 2
fi
candidate_backend_tag="$1"
candidate_frontend_tag="$2"
candidate_commit="$3"
candidate_backend_id="$4"
candidate_frontend_id="$5"
previous_backend_tag="$6"
previous_frontend_tag="$7"
verified_backup_dir="$8"
expected_compose_sha="$9"
dry_run="${10:-}"

for tag in "$candidate_backend_tag" "$candidate_frontend_tag" "$previous_backend_tag" "$previous_frontend_tag"; do
  assert_release_id "$tag"
done
[[ "$candidate_commit" =~ ^[a-f0-9]{40}([a-f0-9]{24})?$ ]] || {
  echo 'ERROR: candidate revision must be an exact Git commit hash' >&2; exit 2;
}
for image_id in "$candidate_backend_id" "$candidate_frontend_id"; do
  [[ "$image_id" =~ ^sha256:[a-f0-9]{64}$ ]] || {
    echo 'ERROR: candidate image ID must be an exact sha256 digest' >&2; exit 2;
  }
done
[[ "$expected_compose_sha" =~ ^[a-f0-9]{64}$ ]] || {
  echo 'ERROR: Compose SHA-256 must be exact' >&2; exit 2;
}
for command in docker python3 flock gzip curl; do require_command "$command"; done
for config in .env.prod .release.env docker-compose.prod.yml Caddyfile; do
  [[ -r "$RELEASE_ROOT/$config" ]] || {
    echo "ERROR: missing production configuration $config" >&2; exit 1;
  }
done
[[ "$(sha256_file "$RELEASE_ROOT/docker-compose.prod.yml")" == "$expected_compose_sha" ]] || {
  echo 'ERROR: live Compose differs from the reviewed source' >&2; exit 1;
}
[[ "$verified_backup_dir" == /* && -d "$verified_backup_dir" && ! -L "$verified_backup_dir" ]] || {
  echo 'ERROR: backup path must be an absolute real directory' >&2; exit 1;
}

# A backup is both a data restore point and a content-addressed rollback bundle.
# Validate every listed file without printing the protected env or the Compose model.
python3 - "$verified_backup_dir" "$previous_backend_tag" "$previous_frontend_tag" <<'PY' || {
import hashlib
import os
import stat
import sys
import time

root, backend_tag, frontend_tag = sys.argv[1:]
entry = os.stat(root)
if entry.st_uid != os.geteuid() or stat.S_IMODE(entry.st_mode) != 0o700:
    raise SystemExit(1)
manifest = os.path.join(root, 'SHA256SUMS')
if not os.path.isfile(manifest) or os.path.islink(manifest):
    raise SystemExit(1)
age = time.time() - os.stat(manifest).st_mtime
if age < 0 or age > 24 * 3600:
    raise SystemExit(1)
required = {
    'epharm.dump', 'env.prod', 'release.env', 'docker-compose.prod.yml',
    'Caddyfile', f'backend-{backend_tag}.tar.gz', f'frontend-{frontend_tag}.tar.gz',
}
seen = set()
with open(manifest, encoding='ascii') as source:
    for line in source:
        row = line.rstrip('\n').split('  ', 1)
        if len(row) != 2:
            raise SystemExit(1)
        expected, name = row
        if len(expected) != 64 or any(c not in '0123456789abcdef' for c in expected):
            raise SystemExit(1)
        if os.path.basename(name) != name or not name or name in seen:
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
  echo 'ERROR: fresh verified backup is missing, incomplete or altered' >&2
  exit 1
}
for pair in 'env.prod:.env.prod' 'release.env:.release.env' 'docker-compose.prod.yml:docker-compose.prod.yml' 'Caddyfile:Caddyfile'; do
  backup_name="${pair%%:*}"
  live_name="${pair#*:}"
  [[ "$(sha256_file "$verified_backup_dir/$backup_name")" == "$(sha256_file "$RELEASE_ROOT/$live_name")" ]] || {
    echo "ERROR: $live_name changed after the verified backup" >&2; exit 1;
  }
done
docker exec -i epharm-postgres pg_restore --list < "$verified_backup_dir/epharm.dump" > /dev/null || {
  echo 'ERROR: PostgreSQL backup archive cannot be listed' >&2; exit 1;
}
for archive in "backend-${previous_backend_tag}.tar.gz" "frontend-${previous_frontend_tag}.tar.gz"; do
  gzip -t "$verified_backup_dir/$archive" || {
    echo "ERROR: rollback image archive is corrupt: $archive" >&2; exit 1;
  }
done

mkdir -p "$RELEASE_ROOT/releases/archive-two-image"
chmod 700 "$RELEASE_ROOT/releases/archive-two-image"
# Share the backend-only lock so its backup/deploy/rollback helpers cannot race
# this transaction on the same .release.env and backend container.
mkdir -p "$RELEASE_ROOT/releases/backend-only"
exec 9>"$RELEASE_ROOT/releases/backend-only/.deploy.lock"
flock -n 9 || { echo 'ERROR: another application backup/deployment is active' >&2; exit 1; }

image_id() { docker image inspect --format '{{.Id}}' "$1"; }
image_label() { docker image inspect --format "{{index .Config.Labels \"$2\"}}" "$1"; }
previous_backend_id="$(image_id "epharm/backend:$previous_backend_tag")"
previous_frontend_id="$(image_id "epharm/frontend:$previous_frontend_tag")"
running_backend_id="$(docker inspect --format '{{.Image}}' epharm-backend)"
running_frontend_id="$(docker inspect --format '{{.Image}}' epharm-frontend)"
running_caddy_container="$(docker inspect --format '{{.Id}}' epharm-caddy)"
[[ "$running_backend_id" == "$previous_backend_id" && "$running_frontend_id" == "$previous_frontend_id" ]] || {
  echo 'ERROR: running images differ from the explicitly named rollback tags' >&2; exit 1;
}
# Docker's containerd store may use an OCI manifest digest for `.Id`, while
# `docker save`/`load` on another engine uses the config digest. Validate the
# archive's rootfs and runnable config against the currently running tag now;
# only a content-equivalent archive is eligible for emergency reload.
previous_backend_archive_config_id="$(python3 "$SCRIPT_DIR/verify-image-archive.py" \
  "$verified_backup_dir/backend-${previous_backend_tag}.tar.gz" "epharm/backend:$previous_backend_tag")" || {
  echo 'ERROR: backend rollback archive differs from the running image' >&2; exit 1;
}
previous_frontend_archive_config_id="$(python3 "$SCRIPT_DIR/verify-image-archive.py" \
  "$verified_backup_dir/frontend-${previous_frontend_tag}.tar.gz" "epharm/frontend:$previous_frontend_tag")" || {
  echo 'ERROR: frontend rollback archive differs from the running image' >&2; exit 1;
}
actual_backend_id="$(image_id "epharm/backend:$candidate_backend_tag")"
actual_frontend_id="$(image_id "epharm/frontend:$candidate_frontend_tag")"
[[ "$actual_backend_id" == "$candidate_backend_id" && "$actual_frontend_id" == "$candidate_frontend_id" ]] || {
  echo 'ERROR: candidate image IDs differ from the approved artifacts' >&2; exit 1;
}
for spec in "backend:$candidate_backend_tag" "frontend:$candidate_frontend_tag"; do
  component="${spec%%:*}"
  tag="${spec#*:}"
  image="epharm/$component:$tag"
  [[ "$(image_label "$image" org.opencontainers.image.version)" == "$tag" \
     && "$(image_label "$image" org.opencontainers.image.revision)" == "$candidate_commit" ]] || {
    echo "ERROR: $component image OCI version/revision does not match the merged commit" >&2; exit 1;
  }
done
previous_backend_commit="$(image_label "epharm/backend:$previous_backend_tag" org.opencontainers.image.revision)"
previous_frontend_commit="$(image_label "epharm/frontend:$previous_frontend_tag" org.opencontainers.image.revision)"
[[ "$(image_label "epharm/backend:$previous_backend_tag" org.opencontainers.image.version)" == "$previous_backend_tag" \
   && "$(image_label "epharm/frontend:$previous_frontend_tag" org.opencontainers.image.version)" == "$previous_frontend_tag" ]] || {
  echo 'ERROR: rollback image version labels do not match the running tags' >&2; exit 1;
}
for revision in "$previous_backend_commit" "$previous_frontend_commit"; do
  [[ "$revision" =~ ^[A-Za-z0-9_.-]+$ ]] || {
    echo 'ERROR: rollback image has an unsafe or absent revision label' >&2; exit 1;
  }
done

transaction_dir="$(mktemp -d "$RELEASE_ROOT/releases/archive-two-image/${candidate_backend_tag}-${candidate_frontend_tag}-$(date -u +%Y%m%dT%H%M%SZ)-XXXXXXXX")"
cp -p "$RELEASE_ROOT/.release.env" "$transaction_dir/original.release.env"
printf 'previous_backend_tag=%s\nprevious_backend_id=%s\nprevious_backend_archive_config_id=%s\nprevious_frontend_tag=%s\nprevious_frontend_id=%s\nprevious_frontend_archive_config_id=%s\nprevious_caddy_container=%s\ncandidate_backend_tag=%s\ncandidate_backend_id=%s\ncandidate_frontend_tag=%s\ncandidate_frontend_id=%s\nbackup_dir=%s\n' \
  "$previous_backend_tag" "$previous_backend_id" "$previous_backend_archive_config_id" \
  "$previous_frontend_tag" "$previous_frontend_id" "$previous_frontend_archive_config_id" \
  "$running_caddy_container" "$candidate_backend_tag" "$candidate_backend_id" \
  "$candidate_frontend_tag" "$candidate_frontend_id" "$verified_backup_dir" \
  > "$transaction_dir/manifest"
chmod 600 "$transaction_dir/manifest"

write_pins() {
  local target="$1" backend_tag="$2" backend_commit="$3" frontend_tag="$4" frontend_commit="$5"
  printf 'RELEASE_ID=%s\nRELEASE_COMMIT=%s\nBACKEND_RELEASE_ID=%s\nBACKEND_RELEASE_COMMIT=%s\nFRONTEND_RELEASE_ID=%s\nFRONTEND_RELEASE_COMMIT=%s\n' \
    "$backend_tag" "$backend_commit" "$backend_tag" "$backend_commit" \
    "$frontend_tag" "$frontend_commit" > "$target"
  chmod 600 "$target"
}
write_pins "$transaction_dir/rollback.release.env" \
  "$previous_backend_tag" "$previous_backend_commit" "$previous_frontend_tag" "$previous_frontend_commit"
staged_env="$(mktemp "$RELEASE_ROOT/.release.archive-candidate.XXXXXX")"
write_pins "$staged_env" "$candidate_backend_tag" "$candidate_commit" "$candidate_frontend_tag" "$candidate_commit"

# Rendering is parsed internally; Compose output can contain credentials.
verify_compose() {
  local release_env="$1" backend_tag="$2" backend_commit="$3" frontend_tag="$4" frontend_commit="$5"
  docker compose --env-file "$RELEASE_ROOT/.env.prod" --env-file "$release_env" \
    -f "$RELEASE_ROOT/docker-compose.prod.yml" config --format json \
    | python3 -c 'import json,sys; s=json.load(sys.stdin)["services"]; b=s["backend"]; f=s["frontend"]; want=sys.argv[1:5]; be=b["environment"]; args=f.get("build",{}).get("args",{}); ok=(b["image"]=="epharm/backend:"+want[0] and be.get("RELEASE_ID")==want[0] and be.get("RELEASE_COMMIT")==want[1] and f["image"]=="epharm/frontend:"+want[2] and args.get("VITE_RELEASE_ID")==want[2] and args.get("VITE_RELEASE_COMMIT")==want[3]); sys.exit(0 if ok else 1)' \
    "$backend_tag" "$backend_commit" "$frontend_tag" "$frontend_commit"
}
verify_compose "$staged_env" "$candidate_backend_tag" "$candidate_commit" \
  "$candidate_frontend_tag" "$candidate_commit" || {
    echo 'ERROR: candidate Compose render does not pin both approved images' >&2; exit 1;
  }
verify_compose "$transaction_dir/rollback.release.env" "$previous_backend_tag" "$previous_backend_commit" \
  "$previous_frontend_tag" "$previous_frontend_commit" || {
    echo 'ERROR: rollback Compose render does not pin both running images' >&2; exit 1;
  }

# A failing baseline should not be misdiagnosed as a candidate regression.
"$SCRIPT_DIR/smoke.sh" "$previous_backend_tag" "$previous_frontend_tag"
[[ "$(docker inspect --format '{{.Image}}' epharm-backend)" == "$previous_backend_id" \
   && "$(docker inspect --format '{{.Image}}' epharm-frontend)" == "$previous_frontend_id" \
   && "$(docker inspect --format '{{.Id}}' epharm-caddy)" == "$running_caddy_container" ]] || {
  echo 'ERROR: application or Caddy changed while validating the transaction' >&2; exit 1;
}
if [[ "$dry_run" == --dry-run ]]; then
  rm -f -- "$staged_env"
  echo 'Archive deployment dry-run passed; no application container was changed'
  exit 0
fi

rollback_ready="$(mktemp "$RELEASE_ROOT/.release.archive-rollback.XXXXXX")"
cp -p "$transaction_dir/rollback.release.env" "$rollback_ready"
chmod 600 "$rollback_ready"
original_env_sha="$(sha256_file "$RELEASE_ROOT/.env.prod")"
original_caddy_sha="$(sha256_file "$RELEASE_ROOT/Caddyfile")"
switched=false
rollback_on_exit() {
  local status=$?
  trap - EXIT
  if [[ "$switched" == true ]]; then
    echo 'ERROR: two-image release failed; restoring the exact previous pins' >&2
    local rollback_ok=true
    local rollback_backend_id="$previous_backend_id"
    local rollback_frontend_id="$previous_frontend_id"
    for spec in "backend:$previous_backend_tag:$previous_backend_id" "frontend:$previous_frontend_tag:$previous_frontend_id"; do
      IFS=: read -r component tag digest_prefix digest <<< "$spec"
      expected_id="$digest_prefix:$digest"
      image="epharm/$component:$tag"
      if [[ "$(image_id "$image" 2>/dev/null || true)" != "$expected_id" ]]; then
        gzip -dc "$verified_backup_dir/${component}-${tag}.tar.gz" | docker image load >/dev/null || rollback_ok=false
      fi
      # If reloaded, the Docker engine can assign a new OCI-manifest ID even
      # though the config digest, rootfs and runtime settings are identical.
      # Content equivalence, not cross-format ID equality, is the reload gate.
      if [[ "$(image_id "$image" 2>/dev/null || true)" != "$expected_id" ]]; then
        python3 "$SCRIPT_DIR/verify-image-archive.py" \
          "$verified_backup_dir/${component}-${tag}.tar.gz" "$image" >/dev/null || rollback_ok=false
      fi
      restored_id="$(image_id "$image" 2>/dev/null || true)"
      [[ "$restored_id" =~ ^sha256:[a-f0-9]{64}$ ]] || rollback_ok=false
      if [[ "$component" == backend ]]; then rollback_backend_id="$restored_id";
      else rollback_frontend_id="$restored_id"; fi
    done
    if [[ "$rollback_ok" == true ]]; then
      mv -f "$rollback_ready" "$RELEASE_ROOT/.release.env" || rollback_ok=false
    fi
    if [[ "$rollback_ok" == true ]]; then
      compose_prod up -d --no-build --no-deps --force-recreate backend frontend || rollback_ok=false
    fi
    [[ "$(docker inspect --format '{{.Image}}' epharm-backend 2>/dev/null || true)" == "$rollback_backend_id" \
       && "$(docker inspect --format '{{.Image}}' epharm-frontend 2>/dev/null || true)" == "$rollback_frontend_id" \
       && "$(docker inspect --format '{{.Id}}' epharm-caddy 2>/dev/null || true)" == "$running_caddy_container" ]] || rollback_ok=false
    [[ "$(sha256_file "$RELEASE_ROOT/.env.prod")" == "$original_env_sha" \
       && "$(sha256_file "$RELEASE_ROOT/Caddyfile")" == "$original_caddy_sha" ]] || rollback_ok=false
    if [[ "$rollback_ok" == true ]]; then
      "$SCRIPT_DIR/smoke.sh" "$previous_backend_tag" "$previous_frontend_tag" || rollback_ok=false
    fi
    if [[ "$rollback_ok" == true ]]; then
      echo "Rollback verified: $previous_backend_tag/$previous_frontend_tag" >&2
    else
      echo "CRITICAL: rollback verification failed; inspect $transaction_dir before further deployment" >&2
    fi
  fi
  rm -f -- "$staged_env" "$rollback_ready"
  exit "$status"
}
trap rollback_on_exit EXIT
trap 'exit 1' INT TERM

for pair in 'env.prod:.env.prod' 'release.env:.release.env' 'docker-compose.prod.yml:docker-compose.prod.yml' 'Caddyfile:Caddyfile'; do
  backup_name="${pair%%:*}"
  live_name="${pair#*:}"
  [[ "$(sha256_file "$verified_backup_dir/$backup_name")" == "$(sha256_file "$RELEASE_ROOT/$live_name")" ]] || {
    echo "ERROR: $live_name changed before the image switch" >&2; exit 1;
  }
done
[[ "$(image_id "epharm/backend:$candidate_backend_tag")" == "$candidate_backend_id" \
   && "$(image_id "epharm/frontend:$candidate_frontend_tag")" == "$candidate_frontend_id" ]] || {
  echo 'ERROR: candidate image tag changed before the image switch' >&2; exit 1;
}
switched=true
mv -f "$staged_env" "$RELEASE_ROOT/.release.env"
compose_prod up -d --no-build --no-deps --force-recreate backend frontend
"$SCRIPT_DIR/smoke.sh" "$candidate_backend_tag" "$candidate_frontend_tag"
[[ "$(docker inspect --format '{{.Image}}' epharm-backend)" == "$candidate_backend_id" \
   && "$(docker inspect --format '{{.Image}}' epharm-frontend)" == "$candidate_frontend_id" \
   && "$(docker inspect --format '{{.Id}}' epharm-caddy)" == "$running_caddy_container" ]] || {
  echo 'ERROR: running images/Caddy differ from the approved transaction' >&2; exit 1;
}
[[ "$(sha256_file "$RELEASE_ROOT/.env.prod")" == "$original_env_sha" \
   && "$(sha256_file "$RELEASE_ROOT/Caddyfile")" == "$original_caddy_sha" ]] || {
  echo 'ERROR: production secrets or Caddyfile changed during the transaction' >&2; exit 1;
}
active_tmp="$(mktemp "$RELEASE_ROOT/releases/archive-two-image/.active-transaction.XXXXXX")"
printf '%s\n' "$transaction_dir" > "$active_tmp"
mv -f "$active_tmp" "$RELEASE_ROOT/releases/archive-two-image/active-transaction"
switched=false
rm -f -- "$rollback_ready"
echo "Archive two-image release verified: $candidate_backend_tag/$candidate_frontend_tag"
