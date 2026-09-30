#!/usr/bin/env bash
# Transactional backend-only release for a production source archive without .git.
# The frontend image/container remains pinned and is never part of Compose up.
set -euo pipefail
umask 077

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=tools/release/lib.sh
source "$SCRIPT_DIR/lib.sh"

candidate_tag="${1:-}"
candidate_commit="${2:-}"
candidate_image_id="${3:-}"
frontend_tag="${4:-}"
verified_backup_dir="${5:-}"
expected_compose_sha="${6:-}"
[[ $# -eq 6 ]] || {
  echo 'Usage: deploy-backend-only.sh vX.Y.Z <40/64-hex-commit> <sha256:image-id> <frontend-tag> <verified-backup-dir> <compose-sha256>' >&2
  exit 2
}
assert_release_id "$candidate_tag"
assert_release_id "$frontend_tag"
[[ "$candidate_commit" =~ ^[a-f0-9]{40}([a-f0-9]{24})?$ ]] || {
  echo 'ERROR: expected an exact Git commit hash' >&2; exit 2;
}
[[ "$candidate_image_id" =~ ^sha256:[a-f0-9]{64}$ ]] || {
  echo 'ERROR: expected an exact Docker image ID' >&2; exit 2;
}
[[ "$expected_compose_sha" =~ ^[a-f0-9]{64}$ ]] || {
  echo 'ERROR: expected an exact Compose source SHA-256' >&2; exit 2;
}
[[ "$(sha256_file "$RELEASE_ROOT/docker-compose.prod.yml")" == "$expected_compose_sha" ]] || {
  echo 'ERROR: active Compose file differs from approved merged source' >&2; exit 1;
}
require_command docker
require_command python3
require_command flock
require_command gzip
require_command curl
[[ -r "$RELEASE_ROOT/.env.prod" && -r "$RELEASE_ROOT/.release.env" ]] || {
  echo 'ERROR: production env/release metadata is missing' >&2; exit 1;
}
[[ -d "$verified_backup_dir" && -r "$verified_backup_dir/SHA256SUMS" ]] || {
  echo 'ERROR: verified pre-deploy backup is missing' >&2; exit 1;
}
for required in epharm.dump env.prod docker-compose.prod.yml Caddyfile; do
  [[ -s "$verified_backup_dir/$required" ]] || {
    echo "ERROR: backup is incomplete ($required)" >&2; exit 1;
  }
done
backup_age="$(python3 - "$verified_backup_dir/SHA256SUMS" <<'PY'
import os, sys, time
print(int(time.time() - os.stat(sys.argv[1]).st_mtime))
PY
)"
(( backup_age >= 0 && backup_age <= 86400 )) || {
  echo 'ERROR: verified backup is older than 24 hours' >&2; exit 1;
}
python3 - "$verified_backup_dir" <<'PY' || {
import hashlib
import os
import sys

root = sys.argv[1]
with open(os.path.join(root, 'SHA256SUMS'), encoding='ascii') as manifest:
    rows = [line.rstrip('\n').split('  ', 1) for line in manifest if line.strip()]
if not rows or any(len(row) != 2 for row in rows):
    raise SystemExit(1)
if not {'epharm.dump', 'env.prod', 'docker-compose.prod.yml', 'Caddyfile'}.issubset(
        {name for _, name in rows}):
    raise SystemExit(1)
names = {name for _, name in rows}
if not any(name.startswith('backend-') and name.endswith('.tar.gz') for name in names):
    raise SystemExit(1)
if not any(name.startswith('frontend-') and name.endswith('.tar.gz') for name in names):
    raise SystemExit(1)
for expected, name in rows:
    if os.path.basename(name) != name or not name or len(expected) != 64:
        raise SystemExit(1)
    if os.path.islink(os.path.join(root, name)):
        raise SystemExit(1)
    digest = hashlib.sha256()
    with open(os.path.join(root, name), 'rb') as artifact:
        for block in iter(lambda: artifact.read(1024 * 1024), b''):
            digest.update(block)
    if digest.hexdigest() != expected:
        raise SystemExit(1)
PY
  echo 'ERROR: backup checksum verification failed' >&2; exit 1;
}

mkdir -p "$RELEASE_ROOT/releases/backend-only"
chmod 700 "$RELEASE_ROOT/releases/backend-only"
exec 9>"$RELEASE_ROOT/releases/backend-only/.deploy.lock"
flock -n 9 || { echo 'ERROR: another backend-only deploy is active' >&2; exit 1; }
for config_pair in 'env.prod:.env.prod' 'docker-compose.prod.yml:docker-compose.prod.yml' 'Caddyfile:Caddyfile'; do
  backup_name="${config_pair%%:*}"
  live_name="${config_pair#*:}"
  [[ "$(sha256_file "$verified_backup_dir/$backup_name")" == "$(sha256_file "$RELEASE_ROOT/$live_name")" ]] || {
    echo "ERROR: live $live_name changed after the verified backup" >&2; exit 1;
  }
done

previous_tag="$(read_release_env_value "$RELEASE_ROOT/.release.env" BACKEND_RELEASE_ID || true)"
previous_tag="${previous_tag:-$(read_release_env_value "$RELEASE_ROOT/.release.env" RELEASE_ID || true)}"
previous_commit="$(read_release_env_value "$RELEASE_ROOT/.release.env" BACKEND_RELEASE_COMMIT || true)"
previous_commit="${previous_commit:-$(read_release_env_value "$RELEASE_ROOT/.release.env" RELEASE_COMMIT || true)}"
assert_release_id "$previous_tag"
[[ "$previous_tag" != "$candidate_tag" ]] || {
  echo 'ERROR: candidate backend tag is already active' >&2; exit 1;
}
for archive in "backend-${previous_tag}.tar.gz" "frontend-${frontend_tag}.tar.gz"; do
  [[ -s "$verified_backup_dir/$archive" ]] || {
    echo "ERROR: verified backup has no exact rollback image $archive" >&2; exit 1;
  }
  grep -Eq "^[a-f0-9]{64}  ${archive//./\\.}$" "$verified_backup_dir/SHA256SUMS" || {
    echo "ERROR: rollback image $archive is not in the verified manifest" >&2; exit 1;
  }
  gzip -t "$verified_backup_dir/$archive" || {
    echo "ERROR: rollback image archive $archive is corrupt" >&2; exit 1;
  }
done

current_backend_id="$(docker inspect --format '{{.Image}}' epharm-backend)"
current_frontend_id="$(docker inspect --format '{{.Image}}' epharm-frontend)"
current_frontend_container_id="$(docker inspect --format '{{.Id}}' epharm-frontend)"
previous_image_id="$(docker image inspect --format '{{.Id}}' "epharm/backend:$previous_tag")"
pinned_frontend_id="$(docker image inspect --format '{{.Id}}' "epharm/frontend:$frontend_tag")"
[[ "$current_backend_id" == "$previous_image_id" && "$current_frontend_id" == "$pinned_frontend_id" ]] || {
  echo 'ERROR: running images do not match the rollback/pinned tags' >&2; exit 1;
}
actual_candidate_id="$(docker image inspect --format '{{.Id}}' "epharm/backend:$candidate_tag")"
actual_version="$(docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.version"}}' "epharm/backend:$candidate_tag")"
actual_commit="$(docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "epharm/backend:$candidate_tag")"
[[ "$actual_candidate_id" == "$candidate_image_id" && "$actual_version" == "$candidate_tag" && "$actual_commit" == "$candidate_commit" ]] || {
  echo 'ERROR: candidate image ID/version/commit does not match approved artifact' >&2; exit 1;
}
frontend_commit="$(docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "epharm/frontend:$frontend_tag")"
[[ "$frontend_commit" =~ ^[A-Za-z0-9_.-]+$ ]] || {
  echo 'ERROR: pinned frontend image has no safe revision label' >&2; exit 1;
}

transaction_dir="$(mktemp -d "$RELEASE_ROOT/releases/backend-only/${candidate_tag}-$(date -u +%Y%m%dT%H%M%SZ)-XXXXXXXX")"
cp -p "$RELEASE_ROOT/.release.env" "$transaction_dir/previous.release.env"
cp -p "$RELEASE_ROOT/docker-compose.prod.yml" "$transaction_dir/previous.docker-compose.prod.yml"
printf 'RELEASE_ID=%s\nRELEASE_COMMIT=%s\nBACKEND_RELEASE_ID=%s\nBACKEND_RELEASE_COMMIT=%s\nFRONTEND_RELEASE_ID=%s\nFRONTEND_RELEASE_COMMIT=%s\n' \
  "$previous_tag" "$previous_commit" "$previous_tag" "$previous_commit" \
  "$frontend_tag" "$frontend_commit" > "$transaction_dir/rollback.release.env"
chmod 600 "$transaction_dir/rollback.release.env"
printf 'previous_backend_tag=%s\nprevious_backend_image=%s\nprevious_backend_commit=%s\nfrontend_tag=%s\nfrontend_image=%s\nfrontend_commit=%s\ncandidate_backend_tag=%s\ncandidate_backend_image=%s\ncandidate_backend_commit=%s\nbackup_dir=%s\n' \
  "$previous_tag" "$previous_image_id" "$previous_commit" \
  "$frontend_tag" "$pinned_frontend_id" "$frontend_commit" \
  "$candidate_tag" "$candidate_image_id" "$candidate_commit" "$verified_backup_dir" \
  > "$transaction_dir/manifest"
chmod 600 "$transaction_dir/manifest"

staged_env="$(mktemp "$RELEASE_ROOT/.release.backend-only.XXXXXX")"
printf 'RELEASE_ID=%s\nRELEASE_COMMIT=%s\nBACKEND_RELEASE_ID=%s\nBACKEND_RELEASE_COMMIT=%s\nFRONTEND_RELEASE_ID=%s\nFRONTEND_RELEASE_COMMIT=%s\n' \
  "$candidate_tag" "$candidate_commit" "$candidate_tag" "$candidate_commit" \
  "$frontend_tag" "$frontend_commit" > "$staged_env"
chmod 600 "$staged_env"

# Render into a parser without ever printing the secret-bearing Compose model.
verify_rendered_compose() {
  local release_env="$1"
  local expected_backend="$2"
  local expected_backend_commit="$3"
  docker compose --env-file "$RELEASE_ROOT/.env.prod" --env-file "$release_env" \
    -f "$RELEASE_ROOT/docker-compose.prod.yml" config --format json \
    | python3 -c 'import json,sys; c=json.load(sys.stdin)["services"]; b=c["backend"]; f=c["frontend"]; expected=sys.argv[1:5]; env=b["environment"]; caddy=c["caddy"]["environment"]; allowed=("10.10.1.80:8080", "https://crm.inkar.kz"); ok=(b["image"]=="epharm/backend:"+expected[0] and env["RELEASE_ID"]==expected[0] and env["RELEASE_COMMIT"]==expected[1] and env.get("MERCH_TASKS_ENABLED")=="true" and env.get("MERCH_TASKS_STAFF_URL")=="https://crm.inkar.kz/staff" and env.get("MERCH_TASKS_BASE_URL") in ("http://10.10.1.80:8080", "https://crm.inkar.kz") and env.get("MERCH_TASKS_INTEGRATION_KEY") and 1 <= int(env.get("MERCH_TASKS_MAX_CONCURRENT", "0")) <= 16 and caddy.get("MERCH_PORTAL_UPSTREAM") in allowed and f["image"]=="epharm/frontend:"+expected[2] and f["build"]["args"]["VITE_RELEASE_COMMIT"]==expected[3]); sys.exit(0 if ok else 1)' \
    "$expected_backend" "$expected_backend_commit" "$frontend_tag" "$frontend_commit"
}
verify_rendered_compose "$staged_env" "$candidate_tag" "$candidate_commit" || {
    echo 'ERROR: rendered Compose release identities differ from the candidate/pinned frontend' >&2
    exit 1
  }
verify_rendered_compose "$transaction_dir/rollback.release.env" "$previous_tag" "$previous_commit" || {
    echo 'ERROR: rendered rollback identities differ from the previous backend/pinned frontend' >&2
    exit 1
  }
running_caddy_upstream="$(docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' epharm-caddy \
  | sed -n 's/^MERCH_PORTAL_UPSTREAM=//p' | head -n 1)"
[[ "$running_caddy_upstream" == '10.10.1.80:8080' \
   || "$running_caddy_upstream" == 'https://crm.inkar.kz' ]] || {
  echo 'ERROR: running Caddy still targets an obsolete merchandising upstream' >&2; exit 1;
}
# Verify the *running* Caddy route, not only the on-disk config. An old proxy
# would return HTML with root-absolute CRM assets under the ePharm host, breaking
# the legacy QR page even though /staff itself responds 200.
legacy_staff_headers="$(curl --silent --show-error --connect-timeout 3 --max-time 10 \
  --dump-header - --output /dev/null \
  --header 'Host: epharm.inkar.kz' --header 'X-Forwarded-Proto: https' \
  'http://127.0.0.1:8060/merch/staff?task=bridge-probe')" || {
  echo 'ERROR: running Caddy legacy staff route is unreachable' >&2; exit 1;
}
python3 - "$legacy_staff_headers" <<'PY' || {
import sys

headers = sys.argv[1].replace('\r\n', '\n').split('\n')
status = next((line for line in headers if line.startswith('HTTP/')), '')
fields = {}
for line in headers:
    if ':' in line:
        key, value = line.split(':', 1)
        fields[key.lower()] = value.strip()
expected = 'https://crm.inkar.kz/staff?task=bridge-probe'
if not status.startswith(('HTTP/1.1 302 ', 'HTTP/2 302 ')):
    raise SystemExit(1)
if fields.get('location') != expected:
    raise SystemExit(1)
if fields.get('referrer-policy') != 'no-referrer':
    raise SystemExit(1)
if 'no-store' not in fields.get('cache-control', '').lower():
    raise SystemExit(1)
PY
  echo 'ERROR: running Caddy does not redirect legacy staff QR to fixed CRM origin' >&2
  exit 1
}
rollback_ready="$(mktemp "$RELEASE_ROOT/.release.rollback-ready.XXXXXX")"
cp -p "$transaction_dir/rollback.release.env" "$rollback_ready"
chmod 600 "$rollback_ready"

switched=false
rollback_on_exit() {
  local status=$?
  trap - EXIT
  if [[ "$switched" == true ]]; then
    echo "ERROR: backend-only deployment failed; restoring $previous_tag" >&2
    local rollback_ok=true
    if mv -f "$rollback_ready" "$RELEASE_ROOT/.release.env"; then
      compose_prod up -d --no-build --no-deps backend || rollback_ok=false
    else
      rollback_ok=false
    fi
    if [[ "$(docker inspect --format '{{.Image}}' epharm-backend 2>/dev/null || true)" != "$previous_image_id" \
       || "$(docker inspect --format '{{.Image}}' epharm-frontend 2>/dev/null || true)" != "$current_frontend_id" \
       || "$(docker inspect --format '{{.Id}}' epharm-frontend 2>/dev/null || true)" != "$current_frontend_container_id" ]]; then
      rollback_ok=false
    fi
    "$SCRIPT_DIR/smoke.sh" "$previous_tag" "$frontend_tag" || rollback_ok=false
    if [[ "$rollback_ok" != true ]]; then
      echo "CRITICAL: rollback verification failed; inspect $transaction_dir before further deployment" >&2
    else
      echo "Rollback verified: backend $previous_tag; frontend $frontend_tag unchanged" >&2
    fi
  fi
  rm -f -- "$staged_env"
  rm -f -- "$rollback_ready"
  exit "$status"
}
trap rollback_on_exit EXIT
trap 'exit 1' INT TERM

switched=true
mv -f "$staged_env" "$RELEASE_ROOT/.release.env"
compose_prod up -d --no-build --no-deps backend
[[ "$(docker inspect --format '{{.Image}}' epharm-frontend)" == "$current_frontend_id" \
   && "$(docker inspect --format '{{.Id}}' epharm-frontend)" == "$current_frontend_container_id" ]] || {
  echo 'ERROR: pinned frontend container was replaced' >&2; exit 1;
}
"$SCRIPT_DIR/smoke.sh" "$candidate_tag" "$frontend_tag"
[[ "$(docker inspect --format '{{.Image}}' epharm-backend)" == "$candidate_image_id" ]] || {
  echo 'ERROR: running backend image differs from candidate artifact' >&2; exit 1;
}
active_transaction_tmp="$(mktemp "$RELEASE_ROOT/releases/backend-only/.active-transaction.XXXXXX")"
printf '%s\n' "$transaction_dir" > "$active_transaction_tmp"
mv -f "$active_transaction_tmp" "$RELEASE_ROOT/releases/backend-only/active-transaction"
switched=false
rm -f -- "$rollback_ready"
echo "Backend-only deployment verified: $candidate_tag; frontend remains $frontend_tag"
