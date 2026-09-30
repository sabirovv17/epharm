#!/usr/bin/env bash
# Reverts a successfully deployed backend-only transaction without touching frontend.
set -euo pipefail
umask 077

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=tools/release/lib.sh
source "$SCRIPT_DIR/lib.sh"

transaction_dir="${1:-}"
backup_root="${2:-}"
[[ $# -eq 2 && -d "$transaction_dir" ]] || {
  echo 'Usage: rollback-backend-only.sh <verified-backend-only-transaction-dir> <dedicated-epharm-backups-root>' >&2
  exit 2
}
transaction_dir="$(cd "$transaction_dir" && pwd -P)"
protected_root="$(cd "$RELEASE_ROOT/releases/backend-only" && pwd -P)"
[[ "$transaction_dir" == "$protected_root/"* \
   && -r "$transaction_dir/manifest" && -r "$transaction_dir/rollback.release.env" ]] || {
  echo 'ERROR: transaction is outside the protected backend-only release directory' >&2
  exit 2
}
manifest_value() {
  sed -n "s/^$1=//p" "$transaction_dir/manifest" | head -n 1
}
previous_tag="$(manifest_value previous_backend_tag)"
previous_id="$(manifest_value previous_backend_image)"
candidate_tag="$(manifest_value candidate_backend_tag)"
candidate_id="$(manifest_value candidate_backend_image)"
frontend_tag="$(manifest_value frontend_tag)"
frontend_id="$(manifest_value frontend_image)"
assert_release_id "$previous_tag"
assert_release_id "$candidate_tag"
assert_release_id "$frontend_tag"
for image_id in "$previous_id" "$candidate_id" "$frontend_id"; do
  [[ "$image_id" =~ ^sha256:[a-f0-9]{64}$ ]] || {
    echo 'ERROR: transaction has an invalid image ID' >&2; exit 2;
  }
done
require_command docker
require_command flock
[[ "$(read_release_env_value "$transaction_dir/rollback.release.env" BACKEND_RELEASE_ID || true)" == "$previous_tag" \
   && "$(read_release_env_value "$transaction_dir/rollback.release.env" FRONTEND_RELEASE_ID || true)" == "$frontend_tag" ]] || {
  echo 'ERROR: rollback env does not pin both component versions' >&2; exit 1;
}

[[ "$(docker image inspect --format '{{.Id}}' "epharm/backend:$previous_tag")" == "$previous_id" \
   && "$(docker image inspect --format '{{.Id}}' "epharm/frontend:$frontend_tag")" == "$frontend_id" ]] || {
  echo 'ERROR: rollback/candidate/frontend image tags no longer identify approved artifacts' >&2; exit 1;
}
[[ "$(docker inspect --format '{{.Image}}' epharm-backend)" == "$candidate_id" \
   && "$(docker inspect --format '{{.Image}}' epharm-frontend)" == "$frontend_id" ]] || {
  echo 'ERROR: running application differs from this transaction' >&2; exit 1;
}
frontend_container_id="$(docker inspect --format '{{.Id}}' epharm-frontend)"
fresh_backup_output="$("$SCRIPT_DIR/prepare-backend-only-backup.sh" \
  "$candidate_tag" "$frontend_tag" "$backup_root")"
fresh_backup_dir="${fresh_backup_output##* }"
for config_pair in 'env.prod:.env.prod' 'docker-compose.prod.yml:docker-compose.prod.yml' 'Caddyfile:Caddyfile'; do
  backup_name="${config_pair%%:*}"
  live_name="${config_pair#*:}"
  [[ "$(sha256_file "$fresh_backup_dir/$backup_name")" == "$(sha256_file "$RELEASE_ROOT/$live_name")" ]] || {
    echo "ERROR: live $live_name changed after the fresh rollback backup" >&2; exit 1;
  }
done
printf '%s\n' "$fresh_backup_dir" > "$transaction_dir/pre-rollback-backup"
exec 9>"$RELEASE_ROOT/releases/backend-only/.deploy.lock"
flock -n 9 || { echo 'ERROR: another backend-only backup/deploy is active' >&2; exit 1; }
[[ "$(docker inspect --format '{{.Image}}' epharm-backend)" == "$candidate_id" \
   && "$(docker inspect --format '{{.Image}}' epharm-frontend)" == "$frontend_id" \
   && "$(docker inspect --format '{{.Id}}' epharm-frontend)" == "$frontend_container_id" ]] || {
  echo 'ERROR: running application changed while creating the rollback backup' >&2; exit 1;
}
for config_pair in 'env.prod:.env.prod' 'docker-compose.prod.yml:docker-compose.prod.yml' 'Caddyfile:Caddyfile'; do
  backup_name="${config_pair%%:*}"
  live_name="${config_pair#*:}"
  [[ "$(sha256_file "$fresh_backup_dir/$backup_name")" == "$(sha256_file "$RELEASE_ROOT/$live_name")" ]] || {
    echo "ERROR: live $live_name changed while creating the rollback backup" >&2; exit 1;
  }
done
[[ "$(sha256_file "$fresh_backup_dir/release.env")" == "$(sha256_file "$RELEASE_ROOT/.release.env")" \
   && "$(read_release_env_value "$RELEASE_ROOT/.release.env" BACKEND_RELEASE_ID || true)" == "$candidate_tag" \
   && "$(read_release_env_value "$RELEASE_ROOT/.release.env" FRONTEND_RELEASE_ID || true)" == "$frontend_tag" ]] || {
  echo 'ERROR: release metadata changed while creating the rollback backup' >&2; exit 1;
}
rollback_backend_commit="$(read_release_env_value "$transaction_dir/rollback.release.env" BACKEND_RELEASE_COMMIT || true)"
rollback_frontend_commit="$(read_release_env_value "$transaction_dir/rollback.release.env" FRONTEND_RELEASE_COMMIT || true)"
docker compose --env-file "$RELEASE_ROOT/.env.prod" --env-file "$transaction_dir/rollback.release.env" \
  -f "$RELEASE_ROOT/docker-compose.prod.yml" config --format json \
  | python3 -c 'import json,sys; c=json.load(sys.stdin)["services"]; b=c["backend"]; f=c["frontend"]; x=sys.argv[1:5]; sys.exit(0 if b["image"]=="epharm/backend:"+x[0] and b["environment"]["RELEASE_COMMIT"]==x[1] and f["image"]=="epharm/frontend:"+x[2] and f["build"]["args"]["VITE_RELEASE_COMMIT"]==x[3] else 1)' \
    "$previous_tag" "$rollback_backend_commit" "$frontend_tag" "$rollback_frontend_commit" || {
  echo 'ERROR: rendered rollback Compose model differs from pinned images' >&2; exit 1;
}

# Preserve the currently working candidate before switching. Both files are in
# the same filesystem as .release.env, so rollback/forward restore is atomic.
forward_ready="$(mktemp "$RELEASE_ROOT/.release.forward-ready.XXXXXX")"
cp -p "$RELEASE_ROOT/.release.env" "$forward_ready"
chmod 600 "$forward_ready"
rollback_ready="$(mktemp "$RELEASE_ROOT/.release.rollback-ready.XXXXXX")"
cp -p "$transaction_dir/rollback.release.env" "$rollback_ready"
chmod 600 "$rollback_ready"
switched=false
restore_candidate_on_exit() {
  local status=$?
  trap - EXIT
  if [[ "$switched" == true ]]; then
    local restore_ok=true
    echo "ERROR: backend rollback failed; restoring candidate $candidate_tag" >&2
    if mv -f "$forward_ready" "$RELEASE_ROOT/.release.env"; then
      compose_prod up -d --no-build --no-deps backend || restore_ok=false
    else
      restore_ok=false
    fi
    if [[ "$(docker inspect --format '{{.Image}}' epharm-backend 2>/dev/null || true)" != "$candidate_id" \
       || "$(docker inspect --format '{{.Image}}' epharm-frontend 2>/dev/null || true)" != "$frontend_id" \
       || "$(docker inspect --format '{{.Id}}' epharm-frontend 2>/dev/null || true)" != "$frontend_container_id" ]]; then
      restore_ok=false
    fi
    "$SCRIPT_DIR/smoke.sh" "$candidate_tag" "$frontend_tag" || restore_ok=false
    if [[ "$restore_ok" != true ]]; then
      echo "CRITICAL: candidate restoration failed; inspect $transaction_dir immediately" >&2
    fi
  fi
  rm -f -- "$forward_ready" "$rollback_ready"
  exit "$status"
}
trap restore_candidate_on_exit EXIT
trap 'exit 1' INT TERM

switched=true
mv -f "$rollback_ready" "$RELEASE_ROOT/.release.env"
compose_prod up -d --no-build --no-deps backend
"$SCRIPT_DIR/smoke.sh" "$previous_tag" "$frontend_tag"
[[ "$(docker inspect --format '{{.Image}}' epharm-backend)" == "$previous_id" \
   && "$(docker inspect --format '{{.Image}}' epharm-frontend)" == "$frontend_id" \
   && "$(docker inspect --format '{{.Id}}' epharm-frontend)" == "$frontend_container_id" ]] || {
  echo 'ERROR: image/container identities changed unexpectedly during rollback' >&2
  exit 1
}
printf 'rolled_back_at=%s\n' "$(date -u +%FT%TZ)" > "$transaction_dir/rolled-back"
switched=false
rm -f -- "$forward_ready"
echo "Backend-only rollback verified: $candidate_tag -> $previous_tag; frontend remains $frontend_tag"
