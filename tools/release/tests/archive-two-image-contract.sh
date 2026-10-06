#!/usr/bin/env bash
# Exercises the real archive-host release script against a fake Docker daemon.
set -euo pipefail
umask 077

source_root="$(cd "$(dirname "$0")/../../.." && pwd)"
test_root="$(mktemp -d "${TMPDIR:-/tmp}/epharm-archive-release.XXXXXXXX")"
trap 'rm -rf -- "$test_root"' EXIT
mkdir -p "$test_root/tools/release" "$test_root/tools/ops" "$test_root/bin" "$test_root/backup"
cp "$source_root/tools/release/deploy-archive-two-image.sh" "$source_root/tools/release/lib.sh" \
  "$source_root/tools/release/verify-image-archive.py" "$test_root/tools/release/"
cp "$source_root/tools/ops/lib.sh" "$test_root/tools/ops/lib.sh"
printf '# reviewed Compose fixture\n' > "$test_root/docker-compose.prod.yml"
printf '# reviewed Caddy fixture\n' > "$test_root/Caddyfile"
printf 'JWT_SECRET=fixture-never-log\nMERCH_TASKS_ENABLED=true\nSTANDARDN_DIRECTORY_ENABLED=true\n' > "$test_root/.env.prod"
# This deliberately disagrees with the running backend: the real archive host
# had generic v0.1.23 metadata but was running backend v0.1.24.
printf 'RELEASE_ID=v0.1.23\nRELEASE_COMMIT=stale-marker\n' > "$test_root/.release.env"
printf 'fixture custom pg dump\n' > "$test_root/backup/epharm.dump"
cp "$test_root/.env.prod" "$test_root/backup/env.prod"
cp "$test_root/.release.env" "$test_root/backup/release.env"
cp "$test_root/docker-compose.prod.yml" "$test_root/backup/docker-compose.prod.yml"
cp "$test_root/Caddyfile" "$test_root/backup/Caddyfile"
python3 - "$test_root/backup" <<'PY'
import hashlib
import io
import json
import pathlib
import sys
import tarfile

root = pathlib.Path(sys.argv[1])
for component, tag, revision in (
    ('backend', 'v0.1.24', 'retail-price-fallback'),
    ('frontend', 'v0.1.23', 'certificate-editor-modern-ribbon'),
):
    layer = f'fixture {component} layer'.encode()
    layer_digest = hashlib.sha256(layer).hexdigest()
    config = {
        'architecture': 'amd64', 'os': 'linux',
        'rootfs': {'type': 'layers', 'diff_ids': [f'sha256:{layer_digest}']},
        'config': {'Labels': {
            'org.opencontainers.image.version': tag,
            'org.opencontainers.image.revision': revision,
        }},
    }
    config_bytes = json.dumps(config, separators=(',', ':')).encode()
    config_digest = hashlib.sha256(config_bytes).hexdigest()
    manifest = [{
        'Config': f'blobs/sha256/{config_digest}',
        'RepoTags': [f'epharm/{component}:{tag}'],
        'Layers': [f'blobs/sha256/{layer_digest}'],
    }]
    with tarfile.open(root / f'{component}-{tag}.tar.gz', 'w:gz') as archive:
        for name, payload in (
            ('manifest.json', json.dumps(manifest).encode()),
            (f'blobs/sha256/{config_digest}', config_bytes),
            (f'blobs/sha256/{layer_digest}', layer),
        ):
            info = tarfile.TarInfo(name)
            info.size = len(payload)
            archive.addfile(info, io.BytesIO(payload))
PY
(cd "$test_root/backup" && shasum -a 256 epharm.dump env.prod release.env docker-compose.prod.yml Caddyfile \
  backend-v0.1.24.tar.gz frontend-v0.1.23.tar.gz > SHA256SUMS)

old_backend_id="sha256:$(printf '%064d' 2)"
old_frontend_id="sha256:$(printf '%064d' 3)"
candidate_backend_id="sha256:$(printf '%064d' 4)"
candidate_frontend_id="sha256:$(printf '%064d' 5)"
printf '%s\n' "$old_backend_id" > "$test_root/backend-image"
printf '%s\n' "$old_frontend_id" > "$test_root/frontend-image"
printf '%s\n' "$old_backend_id" > "$test_root/backend-tag-image"
printf '%s\n' "$old_frontend_id" > "$test_root/frontend-tag-image"

cat > "$test_root/bin/docker" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
old_backend_id="sha256:$(printf '%064d' 2)"
old_frontend_id="sha256:$(printf '%064d' 3)"
candidate_backend_id="sha256:$(printf '%064d' 4)"
candidate_frontend_id="sha256:$(printf '%064d' 5)"
loaded_backend_id="sha256:$(printf '%064d' 6)"
loaded_frontend_id="sha256:$(printf '%064d' 7)"
commit="$(printf 'a%.0s' {1..40})"
case "${1:-}:${2:-}" in
  inspect:--format)
    case "${4:-}" in
      epharm-backend)
        [[ "${3:-}" == '{{.Image}}' ]] && cat "$TEST_BACKEND_IMAGE_FILE" ;;
      epharm-frontend)
        [[ "${3:-}" == '{{.Image}}' ]] && cat "$TEST_FRONTEND_IMAGE_FILE" ;;
      epharm-caddy)
        [[ "${3:-}" == '{{.Id}}' ]] && printf 'stable-caddy-container\n' ;;
      *) exit 1 ;;
    esac
    ;;
  image:inspect)
    template="${4:-}"
    image="${5:-}"
    case "$template" in
      '{{json .}}')
        python3 - "$image" <<'PY'
import hashlib
import json
import os
import sys
component, tag = sys.argv[1].split('/', 1)[1].split(':', 1)
revision = {
    'backend': 'retail-price-fallback',
    'frontend': 'certificate-editor-modern-ribbon',
}[component]
layer_digest = hashlib.sha256(f'fixture {component} layer'.encode()).hexdigest()
if os.environ.get('TEST_WRONG_ARCHIVE_ROOTFS') == 'true' and component == 'backend':
    layer_digest = '0' * 64
print(json.dumps({
    'Architecture': 'amd64', 'Os': 'linux',
    'RootFS': {'Layers': [f'sha256:{layer_digest}']},
    'Config': {'Labels': {
        'org.opencontainers.image.version': tag,
        'org.opencontainers.image.revision': revision,
    }},
}))
PY
        ;;
      '{{.Id}}')
        case "$image" in
          epharm/backend:v0.1.24) [[ -s "$TEST_BACKEND_TAG_IMAGE_FILE" ]] && cat "$TEST_BACKEND_TAG_IMAGE_FILE" ;;
          epharm/frontend:v0.1.23) [[ -s "$TEST_FRONTEND_TAG_IMAGE_FILE" ]] && cat "$TEST_FRONTEND_TAG_IMAGE_FILE" ;;
          epharm/backend:v0.1.25) echo "$candidate_backend_id" ;;
          epharm/frontend:v0.1.25) echo "$candidate_frontend_id" ;;
          *) exit 1 ;;
        esac
        ;;
      *org.opencontainers.image.version*) echo "${image##*:}" ;;
      *org.opencontainers.image.revision*)
        case "$image" in
          epharm/backend:v0.1.24) echo retail-price-fallback ;;
          epharm/frontend:v0.1.23) echo certificate-editor-modern-ribbon ;;
          epharm/*:v0.1.25) echo "$commit" ;;
          *) exit 1 ;;
        esac
        ;;
      *) exit 1 ;;
    esac
    ;;
  exec:-i)
    [[ "${3:-}" == epharm-postgres && "${4:-}" == pg_restore && "${5:-}" == --list ]]
    cat > /dev/null
    ;;
  compose:*)
    action=''
    release_env=''
    previous=''
    for argument in "$@"; do
      if [[ "$previous" == env ]]; then release_env="$argument"; previous=''; continue; fi
      case "$argument" in
        --env-file) previous=env ;;
        config|up) action="$argument" ;;
      esac
    done
    case "$action" in
      config)
        python3 - "$release_env" <<'PY'
import json, sys
env = dict(line.strip().split('=', 1) for line in open(sys.argv[1]) if '=' in line)
print(json.dumps({'services': {
    'backend': {'image': 'epharm/backend:' + env['BACKEND_RELEASE_ID'], 'environment': {
        'RELEASE_ID': env['BACKEND_RELEASE_ID'],
        'RELEASE_COMMIT': env['BACKEND_RELEASE_COMMIT']}},
    'frontend': {'image': 'epharm/frontend:' + env['FRONTEND_RELEASE_ID'], 'build': {'args': {
        'VITE_RELEASE_ID': env['FRONTEND_RELEASE_ID'],
        'VITE_RELEASE_COMMIT': env['FRONTEND_RELEASE_COMMIT']}}},
    'caddy': {'image': 'caddy:2-alpine'},
}}))
PY
        ;;
      up)
        [[ " $* " == *' --no-build --no-deps --force-recreate backend frontend '* ]]
        [[ " $* " != *' caddy '* ]]
        backend_tag="$(sed -n 's/^BACKEND_RELEASE_ID=//p' "$release_env")"
        frontend_tag="$(sed -n 's/^FRONTEND_RELEASE_ID=//p' "$release_env")"
        case "$backend_tag/$frontend_tag" in
          v0.1.24/v0.1.23)
            cat "$TEST_BACKEND_TAG_IMAGE_FILE" > "$TEST_BACKEND_IMAGE_FILE"
            cat "$TEST_FRONTEND_TAG_IMAGE_FILE" > "$TEST_FRONTEND_IMAGE_FILE" ;;
          v0.1.25/v0.1.25)
            echo "$candidate_backend_id" > "$TEST_BACKEND_IMAGE_FILE"
            if [[ "${TEST_SIMULATE_OLD_TAG_PRUNE:-false}" == true ]]; then
              : > "$TEST_BACKEND_TAG_IMAGE_FILE"
              : > "$TEST_FRONTEND_TAG_IMAGE_FILE"
            fi
            if [[ "${TEST_FAIL_PARTIAL_UP:-false}" == true ]]; then
              printf 'partial-up:%s/%s\n' "$backend_tag" "$frontend_tag" >> "$TEST_EVENTS"
              exit 1
            fi
            echo "$candidate_frontend_id" > "$TEST_FRONTEND_IMAGE_FILE" ;;
          *) exit 1 ;;
        esac
        printf 'up:%s/%s\n' "$backend_tag" "$frontend_tag" >> "$TEST_EVENTS"
        ;;
      *) exit 1 ;;
    esac
    ;;
  image:load)
    tag="$(tar -xOf - manifest.json | python3 -c 'import json,sys; print(json.load(sys.stdin)[0]["RepoTags"][0])')"
    case "$tag" in
      epharm/backend:v0.1.24) echo "$loaded_backend_id" > "$TEST_BACKEND_TAG_IMAGE_FILE" ;;
      epharm/frontend:v0.1.23) echo "$loaded_frontend_id" > "$TEST_FRONTEND_TAG_IMAGE_FILE" ;;
      *) exit 1 ;;
    esac
    ;;
  *) echo "Unexpected Docker call: $1 $2" >&2; exit 1 ;;
esac
STUB
cat > "$test_root/bin/flock" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1" == -n && "$2" == 9 ]]
STUB
cat > "$test_root/tools/release/smoke.sh" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
expected_backend="$1"
expected_frontend="$2"
case "$expected_backend/$expected_frontend" in
  v0.1.24/v0.1.23)
    [[ "$(cat "$TEST_BACKEND_IMAGE_FILE")" == "$(cat "$TEST_BACKEND_TAG_IMAGE_FILE")" ]]
    [[ "$(cat "$TEST_FRONTEND_IMAGE_FILE")" == "$(cat "$TEST_FRONTEND_TAG_IMAGE_FILE")" ]] ;;
  v0.1.25/v0.1.25)
    [[ "$(cat "$TEST_BACKEND_IMAGE_FILE")" == "sha256:$(printf '%064d' 4)" ]]
    [[ "$(cat "$TEST_FRONTEND_IMAGE_FILE")" == "sha256:$(printf '%064d' 5)" ]]
    if [[ "${TEST_FAIL_CANDIDATE_SMOKE:-false}" == true ]]; then exit 1; fi ;;
  *) exit 1 ;;
esac
printf 'smoke:%s/%s\n' "$expected_backend" "$expected_frontend" >> "$TEST_EVENTS"
STUB
chmod +x "$test_root/bin/docker" "$test_root/bin/flock" "$test_root/tools/release/smoke.sh" \
  "$test_root/tools/release/deploy-archive-two-image.sh"
export TEST_BACKEND_IMAGE_FILE="$test_root/backend-image"
export TEST_FRONTEND_IMAGE_FILE="$test_root/frontend-image"
export TEST_BACKEND_TAG_IMAGE_FILE="$test_root/backend-tag-image"
export TEST_FRONTEND_TAG_IMAGE_FILE="$test_root/frontend-tag-image"
export TEST_EVENTS="$test_root/events"
export PATH="$test_root/bin:$PATH"
: > "$TEST_EVENTS"
commit="$(printf 'a%.0s' {1..40})"
compose_sha="$(shasum -a 256 "$test_root/docker-compose.prod.yml" | awk '{print $1}')"
deploy=("$test_root/tools/release/deploy-archive-two-image.sh" v0.1.25 v0.1.25 "$commit" \
  "$candidate_backend_id" "$candidate_frontend_id" v0.1.24 v0.1.23 "$test_root/backup" "$compose_sha")
secret_hash="$(shasum -a 256 "$test_root/.env.prod" | awk '{print $1}')"
caddy_hash="$(shasum -a 256 "$test_root/Caddyfile" | awk '{print $1}')"

bad=("${deploy[@]}")
bad[3]="sha256:$(printf '%064d' 9)"
if "${bad[@]}" >/dev/null 2>&1; then
  echo 'Accepted incorrect candidate backend image ID' >&2; exit 1
fi
[[ ! -s "$TEST_EVENTS" ]]
if TEST_WRONG_ARCHIVE_ROOTFS=true "${deploy[@]}" >/dev/null 2>&1; then
  echo 'Accepted rollback archive with a different rootfs' >&2; exit 1
fi
[[ ! -s "$TEST_EVENTS" ]]

"${deploy[@]}" --dry-run
[[ ! -s "$TEST_EVENTS" || "$(cat "$TEST_EVENTS")" == smoke:v0.1.24/v0.1.23 ]]
grep -Fxq 'RELEASE_ID=v0.1.23' "$test_root/.release.env"
[[ "$(cat "$test_root/backend-image")" == "$old_backend_id" ]]
[[ "$(cat "$test_root/frontend-image")" == "$old_frontend_id" ]]

: > "$TEST_EVENTS"
"${deploy[@]}"
grep -Fxq 'BACKEND_RELEASE_ID=v0.1.25' "$test_root/.release.env"
grep -Fxq 'FRONTEND_RELEASE_ID=v0.1.25' "$test_root/.release.env"
[[ "$(cat "$test_root/backend-image")" == "$candidate_backend_id" ]]
[[ "$(cat "$test_root/frontend-image")" == "$candidate_frontend_id" ]]
grep -Fxq 'up:v0.1.25/v0.1.25' "$TEST_EVENTS"
[[ "$(shasum -a 256 "$test_root/.env.prod" | awk '{print $1}')" == "$secret_hash" ]]
[[ "$(shasum -a 256 "$test_root/Caddyfile" | awk '{print $1}')" == "$caddy_hash" ]]

# A later candidate smoke failure must restore the exact previous image pins,
# not the stale generic release id from the original .release.env.
cp "$test_root/backup/release.env" "$test_root/.release.env"
echo "$old_backend_id" > "$test_root/backend-image"
echo "$old_frontend_id" > "$test_root/frontend-image"
: > "$TEST_EVENTS"
if TEST_FAIL_CANDIDATE_SMOKE=true "${deploy[@]}" >/dev/null 2>&1; then
  echo 'Failed candidate smoke did not trigger rollback' >&2; exit 1
fi
grep -Fxq 'BACKEND_RELEASE_ID=v0.1.24' "$test_root/.release.env"
grep -Fxq 'FRONTEND_RELEASE_ID=v0.1.23' "$test_root/.release.env"
[[ "$(cat "$test_root/backend-image")" == "$old_backend_id" ]]
[[ "$(cat "$test_root/frontend-image")" == "$old_frontend_id" ]]
grep -Fxq 'up:v0.1.25/v0.1.25' "$TEST_EVENTS"
grep -Fxq 'up:v0.1.24/v0.1.23' "$TEST_EVENTS"

# Even if Compose replaces only the backend before failing, rollback must
# recreate both known-good images and persist both old pins.
cp "$test_root/backup/release.env" "$test_root/.release.env"
echo "$old_backend_id" > "$test_root/backend-image"
echo "$old_frontend_id" > "$test_root/frontend-image"
: > "$TEST_EVENTS"
if TEST_FAIL_PARTIAL_UP=true "${deploy[@]}" >/dev/null 2>&1; then
  echo 'Partial candidate switch did not trigger rollback' >&2; exit 1
fi
grep -Fxq 'BACKEND_RELEASE_ID=v0.1.24' "$test_root/.release.env"
grep -Fxq 'FRONTEND_RELEASE_ID=v0.1.23' "$test_root/.release.env"
[[ "$(cat "$test_root/backend-image")" == "$old_backend_id" ]]
[[ "$(cat "$test_root/frontend-image")" == "$old_frontend_id" ]]
grep -Fxq 'partial-up:v0.1.25/v0.1.25' "$TEST_EVENTS"
grep -Fxq 'up:v0.1.24/v0.1.23' "$TEST_EVENTS"

# Docker 29 may identify the live OCI manifest, while a loaded archive gets a
# different ID on another engine. After simulated tag pruning, the archive is
# reloaded; identical rootfs/runtime config must permit a verified rollback.
cp "$test_root/backup/release.env" "$test_root/.release.env"
echo "$old_backend_id" > "$test_root/backend-image"
echo "$old_frontend_id" > "$test_root/frontend-image"
echo "$old_backend_id" > "$test_root/backend-tag-image"
echo "$old_frontend_id" > "$test_root/frontend-tag-image"
: > "$TEST_EVENTS"
if TEST_FAIL_CANDIDATE_SMOKE=true TEST_SIMULATE_OLD_TAG_PRUNE=true \
  "${deploy[@]}" >/dev/null 2>&1; then
  echo 'Archive reload regression did not trigger rollback' >&2; exit 1
fi
grep -Fxq 'BACKEND_RELEASE_ID=v0.1.24' "$test_root/.release.env"
grep -Fxq 'FRONTEND_RELEASE_ID=v0.1.23' "$test_root/.release.env"
[[ "$(cat "$test_root/backend-image")" == "sha256:$(printf '%064d' 6)" ]]
[[ "$(cat "$test_root/frontend-image")" == "sha256:$(printf '%064d' 7)" ]]
grep -Fxq 'up:v0.1.24/v0.1.23' "$TEST_EVENTS"

cp "$test_root/backup/release.env" "$test_root/.release.env"
echo "$old_backend_id" > "$test_root/backend-image"
echo "$old_frontend_id" > "$test_root/frontend-image"
echo "$old_backend_id" > "$test_root/backend-tag-image"
echo "$old_frontend_id" > "$test_root/frontend-tag-image"
printf 'tamper\n' >> "$test_root/backup/epharm.dump"
before_events="$(wc -l < "$TEST_EVENTS")"
if "${deploy[@]}" >/dev/null 2>&1; then
  echo 'Accepted tampered backup' >&2; exit 1
fi
[[ "$(wc -l < "$TEST_EVENTS")" -eq "$before_events" ]]
echo 'Archive two-image transaction contract OK'
