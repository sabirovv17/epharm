#!/usr/bin/env bash
# Exercise the real archive rollback and fresh-backup helper without Git/API/Docker access.
set -euo pipefail
umask 077
source_root="$(cd "$(dirname "$0")/../../.." && pwd)"
test_root="$(mktemp -d "${TMPDIR:-/tmp}/epharm-archive-rollback.XXXXXXXX")"
test_root="$(cd "$test_root" && pwd -P)"
trap 'rm -rf -- "$test_root"' EXIT
transaction_dir="$test_root/releases/archive-two-image/saved"
mkdir -p "$test_root/tools/release" "$test_root/tools/ops" "$test_root/bin" "$transaction_dir"
cp "$source_root/tools/release/"{rollback-archive-two-image.sh,prepare-backend-only-backup.sh,lib.sh} "$test_root/tools/release/"
cp "$source_root/tools/ops/lib.sh" "$test_root/tools/ops/lib.sh"
cp "$source_root/tools/release/tests/fixtures/fake-release-source.py" "$test_root/tools/release/verify-release-source.py"
cp "$source_root/tools/release/tests/fixtures/fake-financial-transition.py" "$test_root/tools/release/verify-financial-transition.py"
cp "$source_root/tools/release/tests/fixtures/fake-admin-artifact.py" "$test_root/tools/release/verify-admin-artifact.py"
printf 'POSTGRES_PASSWORD=fixture-never-log\n' > "$test_root/.env.prod"
printf '# protected Compose\n' > "$test_root/docker-compose.prod.yml"
printf '# protected Caddy\n' > "$test_root/Caddyfile"
cat > "$test_root/bin/docker" <<'PY'
#!/usr/bin/env python3
import json, os, pathlib, sys
root = pathlib.Path(os.environ['TEST_ARCHIVE_ROOT'])
args = sys.argv[1:]
def identity(number): return 'sha256:' + str(number).zfill(64)
images = {'epharm/backend:v0.1.33': (identity(2), 'backend-old'),
          'epharm/frontend:v0.1.34': (identity(3), 'frontend-old'),
          'epharm/backend:v0.1.35': (identity(4), 'a' * 40),
          'epharm/frontend:v0.1.35': (identity(5), 'a' * 40)}
def pins(file): return dict(line.split('=', 1) for line in file.read_text().splitlines())
def event(value):
    with (root / 'events').open('a') as output: output.write(value + '\n')
if args[:2] == ['inspect', '--format']:
    if args[-1] == 'epharm-caddy': print('c' * 64)
    else: print(json.loads((root / 'running.json').read_text())[args[-1]])
elif args[:2] == ['image', 'inspect']:
    image = args[-1]
    if os.environ.get('TEST_OLD_IMAGE_MISSING') == 'true' and image.endswith('v0.1.33'): sys.exit(1)
    digest, revision = images[image]
    template = args[3]
    if template == '{{.Id}}': print(digest)
    elif 'version' in template: print(image.split(':')[-1])
    elif 'revision' in template: print(revision)
    else: sys.exit('Unexpected inspect template')
elif args[:2] == ['image', 'save']:
    event('backup-image:' + args[-1])
    print('fixture saved image')
elif args[:1] == ['exec']:
    if 'pg_restore' in args:
        assert '--list' in args, 'Database restore must never execute during application rollback'
    else:
        assert 'pg_dump' in args[-1]
        event('backup-dump')
        if os.environ.get('TEST_CHANGE_SAVED_STATE') == 'true':
            with (root / 'releases/archive-two-image/saved/rollback.release.env').open('a') as output:
                output.write('UNEXPECTED=changed\n')
        print('fixture PostgreSQL archive')
elif args[:1] == ['compose']:
    selected = [args[index + 1] for index, value in enumerate(args) if value == '--env-file'][-1]
    env = pins(pathlib.Path(selected))
    backend, frontend = env['BACKEND_RELEASE_ID'], env['FRONTEND_RELEASE_ID']
    if 'config' in args:
        print(json.dumps({'services': {
            'backend': {'image': 'epharm/backend:' + backend, 'environment': {
                'RELEASE_ID': backend, 'RELEASE_COMMIT': env['BACKEND_RELEASE_COMMIT']}},
            'frontend': {'image': 'epharm/frontend:' + frontend, 'build': {'args': {
                'VITE_RELEASE_ID': frontend, 'VITE_RELEASE_COMMIT': env['FRONTEND_RELEASE_COMMIT']}}},
        }}))
    else:
        assert args[args.index('up'):] == ['up', '-d', '--no-build', '--no-deps', '--force-recreate', 'backend', 'frontend']
        (root / 'running.json').write_text(json.dumps({
            'epharm-backend': images['epharm/backend:' + backend][0],
            'epharm-frontend': images['epharm/frontend:' + frontend][0]}))
        event('up:' + backend + '/' + frontend)
else: sys.exit('Unexpected Docker command')
PY
cat > "$test_root/bin/flock" <<'SH'
#!/usr/bin/env bash
[[ "$1" == -n && "$2" == 9 && "${TEST_LOCKED:-}" != true ]]
SH
export TEST_REAL_MV
TEST_REAL_MV="$(command -v mv)"
cat > "$test_root/bin/mv" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${TEST_FAIL_FORWARD_PINS:-}" == true && "$1" == -f \
  && "$2" == "$TEST_ARCHIVE_ROOT/.release.archive-forward."* \
  && "$3" == "$TEST_ARCHIVE_ROOT/.release.env" ]]; then exit 1; fi
exec "$TEST_REAL_MV" "$@"
SH
cat > "$test_root/tools/release/smoke.sh" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
[[ -z "${SMOKE_ADMIN_IMAGE_ID:-}" && -z "${SMOKE_BACKEND_COMMIT:-}" && -z "${SMOKE_FRONTEND_COMMIT:-}" ]]
grep -Fxq "BACKEND_RELEASE_ID=$1" "$TEST_ARCHIVE_ROOT/.release.env"
grep -Fxq "FRONTEND_RELEASE_ID=$2" "$TEST_ARCHIVE_ROOT/.release.env"
printf 'smoke:%s/%s\n' "$1" "$2" >> "$TEST_ARCHIVE_ROOT/events"
[[ "${TEST_FAIL_ROLLBACK_SMOKE:-}" != true || "$1" == v0.1.35 ]]
SH
chmod +x "$test_root/bin/docker" "$test_root/bin/flock" "$test_root/bin/mv" "$test_root/tools/release/smoke.sh"
export TEST_ARCHIVE_ROOT="$test_root"
export TEST_BAD_RELEASE_SOURCE=true TEST_BAD_ADMIN_ARTIFACT=true
export PATH="$test_root/bin:$PATH"
python3 - "$test_root" "$transaction_dir" <<'PY'
import json, pathlib, sys
root, state = map(pathlib.Path, sys.argv[1:])
def identity(number): return 'sha256:' + str(number).zfill(64)
manifest = {'previous_backend_tag': 'v0.1.33', 'previous_backend_id': identity(2),
            'previous_frontend_tag': 'v0.1.34', 'previous_frontend_id': identity(3),
            'previous_caddy_container': 'c' * 64, 'candidate_backend_tag': 'v0.1.35',
            'candidate_backend_id': identity(4), 'candidate_frontend_tag': 'v0.1.35',
            'candidate_frontend_id': identity(5), 'backup_dir': str(root / 'original-backup')}
(state / 'manifest').write_text(''.join(f'{key}={value}\n' for key, value in manifest.items()))
def env(backend, frontend, backend_commit, frontend_commit):
    return (f'RELEASE_ID={backend}\nRELEASE_COMMIT={backend_commit}\n'
            f'BACKEND_RELEASE_ID={backend}\nBACKEND_RELEASE_COMMIT={backend_commit}\n'
            f'FRONTEND_RELEASE_ID={frontend}\nFRONTEND_RELEASE_COMMIT={frontend_commit}\n')
(state / 'rollback.release.env').write_text(env('v0.1.33', 'v0.1.34', 'backend-old', 'frontend-old'))
(root / 'candidate.env').write_text(env('v0.1.35', 'v0.1.35', 'a' * 40, 'a' * 40))
(root / 'candidate.json').write_text(json.dumps({'epharm-backend': identity(4), 'epharm-frontend': identity(5)}))
PY
cp "$transaction_dir/rollback.release.env" "$test_root/original-rollback.env"
reset_candidate() {
  cp "$test_root/candidate.env" "$test_root/.release.env"
  cp "$test_root/candidate.json" "$test_root/running.json"
  cp "$test_root/original-rollback.env" "$transaction_dir/rollback.release.env"
  rm -f -- "$transaction_dir/rolled-back"
  : > "$test_root/events"
}
assert_current() {
  cmp -s "$test_root/candidate.env" "$test_root/.release.env"
  cmp -s "$test_root/candidate.json" "$test_root/running.json"
}
rollback=("$test_root/tools/release/rollback-archive-two-image.sh" "$transaction_dir" "$test_root/epharm-backend-only-backups")
reset_candidate
if TEST_FINANCE_DENY_IMAGE="sha256:$(printf '%064d' 2)" "${rollback[@]}" > "$test_root/unsafe-explicit.log" 2>&1; then
  echo 'Explicit archive rollback accepted a financially incompatible predecessor' >&2; exit 1
fi
assert_current
[[ ! -s "$test_root/events" ]]
reset_candidate
printf '{"epharm-backend":"wrong-image","epharm-frontend":"wrong-image"}' > "$test_root/running.json"
if "${rollback[@]}" > "$test_root/rejected.log" 2>&1; then echo 'Accepted mismatched current images' >&2; exit 1; fi
grep -Fq 'not the saved candidate' "$test_root/rejected.log"
[[ ! -s "$test_root/events" ]]
reset_candidate
printf 'POSM_DEVICE_KEY=must-not-override\n' >> "$transaction_dir/rollback.release.env"
if "${rollback[@]}" > "$test_root/rejected.log" 2>&1; then echo 'Accepted altered rollback pins' >&2; exit 1; fi
grep -Fq 'unexpected keys' "$test_root/rejected.log"
assert_current
reset_candidate
mv "$transaction_dir/rollback.release.env" "$transaction_dir/real-pins"
ln -s "$transaction_dir/real-pins" "$transaction_dir/rollback.release.env"
if "${rollback[@]}" > "$test_root/rejected.log" 2>&1; then echo 'Accepted symlink rollback state' >&2; exit 1; fi
grep -Fq '0700/0600' "$test_root/rejected.log"
rm "$transaction_dir/rollback.release.env"
mv "$transaction_dir/real-pins" "$transaction_dir/rollback.release.env"
for failure in TEST_OLD_IMAGE_MISSING TEST_LOCKED TEST_CHANGE_SAVED_STATE; do
  reset_candidate
  if env "$failure=true" "${rollback[@]}" > "$test_root/rejected.log" 2>&1; then echo "Accepted $failure" >&2; exit 1; fi
  assert_current
  if [[ "$failure" == TEST_OLD_IMAGE_MISSING ]]; then grep -Fq 'restore its exact tag' "$test_root/rejected.log"; fi
  if grep -q '^up:' "$test_root/events"; then echo 'Rejected rollback changed containers' >&2; exit 1; fi
done
reset_candidate
env_hash="$(shasum -a 256 "$test_root/.env.prod")"
caddy_hash="$(shasum -a 256 "$test_root/Caddyfile")"
SMOKE_ADMIN_IMAGE_ID=stale-candidate SMOKE_BACKEND_COMMIT=stale SMOKE_FRONTEND_COMMIT=stale "${rollback[@]}"
cmp -s "$transaction_dir/rollback.release.env" "$test_root/.release.env"
grep -Fxq 'up:v0.1.33/v0.1.34' "$test_root/events"
grep -Fxq 'smoke:v0.1.33/v0.1.34' "$test_root/events"
[[ -s "$transaction_dir/rolled-back" ]]
fresh_backup="$(cat "$transaction_dir/pre-rollback-backup")"
(cd "$fresh_backup" && sha256sum --status -c SHA256SUMS)
[[ -s "$fresh_backup/backend-v0.1.35.tar.gz" && -s "$fresh_backup/frontend-v0.1.35.tar.gz" ]]
reset_candidate
if TEST_FAIL_ROLLBACK_SMOKE=true "${rollback[@]}" > "$test_root/failed.log" 2>&1; then echo 'Ignored failed rollback smoke' >&2; exit 1; fi
assert_current
grep -Fxq 'up:v0.1.33/v0.1.34' "$test_root/events"
grep -Fxq 'up:v0.1.35/v0.1.35' "$test_root/events"
grep -Fxq 'smoke:v0.1.35/v0.1.35' "$test_root/events"
[[ ! -e "$transaction_dir/rolled-back" ]]
[[ "$(shasum -a 256 "$test_root/.env.prod")" == "$env_hash" && "$(shasum -a 256 "$test_root/Caddyfile")" == "$caddy_hash" ]]
if grep -q 'fixture-never-log' "$test_root/failed.log"; then echo 'Rollback exposed a secret' >&2; exit 1; fi
reset_candidate
if TEST_FAIL_ROLLBACK_SMOKE=true TEST_FAIL_FORWARD_PINS=true "${rollback[@]}" > "$test_root/failed-forward.log" 2>&1; then
  echo 'Ignored failed candidate pins restoration' >&2; exit 1
fi
grep -Fq 'CRITICAL: candidate restoration failed' "$test_root/failed-forward.log"
[[ "$(grep -c '^up:' "$test_root/events")" -eq 1 ]]
grep -Fxq 'up:v0.1.33/v0.1.34' "$test_root/events"
cmp -s "$transaction_dir/rollback.release.env" "$test_root/.release.env"
[[ ! -e "$transaction_dir/rolled-back" ]]
[[ "$(shasum -a 256 "$test_root/.env.prod")" == "$env_hash" && "$(shasum -a 256 "$test_root/Caddyfile")" == "$caddy_hash" ]]
reset_candidate
if TEST_FAIL_ROLLBACK_SMOKE=true TEST_FINANCE_DENY_IMAGE="sha256:$(printf '%064d' 4)" \
  "${rollback[@]}" > "$test_root/unsafe-restore.log" 2>&1; then
  echo 'Archive rollback ignored failure before unsafe candidate restoration' >&2; exit 1
fi
grep -Fq 'CRITICAL: unsafe financial candidate restoration denied' "$test_root/unsafe-restore.log"
[[ "$(grep -c '^up:' "$test_root/events")" -eq 1 ]]
grep -Fxq 'up:v0.1.33/v0.1.34' "$test_root/events"
cmp -s "$transaction_dir/rollback.release.env" "$test_root/.release.env"
[[ ! -e "$transaction_dir/rolled-back" ]]
if grep -Fq 'Rollback verified:' "$test_root/unsafe-restore.log"; then
  echo 'Archive rollback claimed an unsafe restoration was verified' >&2; exit 1
fi
echo 'Explicit archive rollback, protected state, fresh backup and candidate restoration OK'
