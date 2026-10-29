#!/usr/bin/env bash
# Exercise the real transaction offline; fake Docker rejects every non-frontend switch.
set -euo pipefail
umask 077
source_root="$(cd "$(dirname "$0")/../../.." && pwd)"
test_root="$(mktemp -d "${TMPDIR:-/tmp}/epharm-frontend-only.XXXXXXXX")"
test_root="$(cd "$test_root" && pwd -P)"
trap 'rm -rf -- "$test_root"' EXIT
backup_root="$test_root/epharm-backend-only-backups"
backup_dir="$backup_root/pre-backend-only-20261022T112243Z-ABCDEFGH"
mkdir -p "$test_root/tools/release" "$test_root/tools/ops" "$test_root/bin" "$backup_dir"
export TEST_REAL_MKTEMP="$(command -v mktemp)"
cp "$source_root/tools/release/"{deploy-frontend-only.sh,lib.sh} "$test_root/tools/release/"
cp "$source_root/tools/ops/lib.sh" "$test_root/tools/ops/"
cp "$source_root/tools/release/tests/fixtures/fake-financial-transition.py" "$test_root/tools/release/verify-financial-transition.py"
cat > "$test_root/tools/release/verify-release-source.py" <<'PY'
import os, pathlib, re, sys
root = pathlib.Path(os.environ['TEST_FRONTEND_ROOT'])
count_file = root / 'source-calls'
count = int(count_file.read_text()) + 1 if count_file.exists() else 1
count_file.write_text(str(count))
if os.environ.get('TEST_FRONTEND_DRIFT_BEFORE_SWITCH') == 'true' and count == 2:
    import json
    state = json.loads((root / 'state.json').read_text())
    state.update(frontendImage='sha256:'+'9'*64, frontendContainer='outside-frontend-container')
    (root / 'state.json').write_text(json.dumps(state))
if len(sys.argv) != 2 or not re.fullmatch('[a-f0-9]{40}', sys.argv[1]):
    raise SystemExit('Invalid source identity')
if os.environ.get('TEST_BAD_RELEASE_SOURCE') == 'true' or (
        os.environ.get('TEST_FAIL_SECOND_SOURCE') == 'true' and count == 2):
    raise SystemExit('Unverified release source fixture')
PY
cat > "$test_root/tools/release/verify-admin-artifact.py" <<'PY'
import os, sys
if os.environ.get('TEST_FRONTEND_DRIFT_PREFLIGHT') == 'true':
    import json, pathlib
    path = pathlib.Path(os.environ['TEST_FRONTEND_ROOT']) / 'state.json'
    state = json.loads(path.read_text())
    state.update(frontendImage='sha256:'+'9'*64, frontendContainer='outside-frontend-container')
    path.write_text(json.dumps(state))
if os.environ.get('TEST_BAD_ADMIN_ARTIFACT') == 'true':
    raise SystemExit('Obsolete admin artifact fixture')
if '--public-url' in sys.argv and os.environ.get('TEST_FAIL_PUBLIC_BYTES') == 'true':
    raise SystemExit('Public admin bytes differ fixture')
PY
cat > "$test_root/tools/release/verify-image-archive.py" <<'PY'
import os, sys
if os.environ.get('TEST_WRONG_ROLLBACK_ARCHIVE') == 'true':
    raise SystemExit('Rollback rootfs differs fixture')
assert len(sys.argv) == 3 and os.path.isfile(sys.argv[1])
print('sha256:' + '8' * 64)
PY
cat > "$test_root/bin/docker" <<'PY'
#!/usr/bin/env python3
import json, os, pathlib, sys
root = pathlib.Path(os.environ['TEST_FRONTEND_ROOT'])
args = sys.argv[1:]
state_path = root / 'state.json'
state = json.loads(state_path.read_text())
def identity(value): return 'sha256:' + str(value).zfill(64)
def event(value):
    with (root / 'events').open('a') as out: out.write(value + '\n')
def save(): state_path.write_text(json.dumps(state))
def release(path): return dict(line.strip().split('=', 1) for line in open(path) if '=' in line)
if args[:2] == ['inspect', '--format']:
    template, container = args[2:]
    assert container in ('epharm-backend', 'epharm-frontend', 'epharm-caddy')
    component = container.removeprefix('epharm-')
    if component == 'frontend' and state.get('frontendMissing'): raise SystemExit(1)
    print(state[component + ('Container' if template == '{{.Id}}' else 'Image')])
elif args[:2] == ['container', 'ls']:
    assert args == ['container','ls','--all','--filter','name=^/epharm-frontend$','--format','{{.Names}}']
    if os.environ.get('TEST_ABSENCE_LIST_FAILURE') == 'true': raise SystemExit(1)
    if not state.get('frontendMissing'): print('epharm-frontend')
elif args[:3] == ['image', 'inspect', '--format']:
    template, image = args[3:]
    if image == 'epharm/backend:v0.1.47':
        image_id, tag, commit = identity(2), 'v0.1.47', 'b' * 40
    elif image == 'epharm/frontend:v0.1.46':
        assert state['previousTagPresent']
        image_id, tag, commit = state['previousTagId'], 'v0.1.46', 'c' * 40
    elif image == 'epharm/frontend:v0.1.48':
        image_id, tag, commit = identity(5), 'v0.1.48', 'a' * 40
        if os.environ.get('TEST_BAD_CANDIDATE_LABEL') == 'true': commit = 'd' * 40
    else:
        raise SystemExit(1)
    if template == '{{.Id}}': print(image_id)
    elif 'org.opencontainers.image.version' in template: print(tag)
    elif 'org.opencontainers.image.revision' in template: print(commit)
    else: raise AssertionError('Unexpected image inspection')
elif args[:5] == ['exec', '-i', 'epharm-postgres', 'pg_restore', '--list']:
    assert len(args) == 5
    sys.stdin.buffer.read()
elif args[:2] == ['image', 'load']:
    assert sys.stdin.buffer.read() == b'frontend image fixture\n'
    state['previousTagPresent'] = True
    state['previousTagId'] = identity(7)
    save()
    event('load:frontend')
elif args[0] == 'compose':
    paths = [args[i + 1] for i, arg in enumerate(args) if arg == '--env-file']
    pins = release(paths[-1])
    assert pins['BACKEND_RELEASE_ID'] == 'v0.1.47'
    assert pins['BACKEND_RELEASE_COMMIT'] == 'b' * 40
    if 'config' in args:
        frontend = pins['FRONTEND_RELEASE_ID']
        model = {'services': {
            'backend': {'image': 'epharm/backend:' + pins['BACKEND_RELEASE_ID'], 'environment': {
                'RELEASE_ID': pins['BACKEND_RELEASE_ID'], 'RELEASE_COMMIT': pins['BACKEND_RELEASE_COMMIT'],
                'JWT_SECRET': 'fixture-never-log'}},
            'frontend': {'image': 'epharm/frontend:' + frontend, 'build': {'args': {
                'VITE_RELEASE_ID': frontend, 'VITE_RELEASE_COMMIT': pins['FRONTEND_RELEASE_COMMIT']}}},
            'caddy': {'image': 'caddy:2-alpine', 'environment': {'SECRET': 'fixture-never-log'}},
        }}
        if os.environ.get('TEST_RENDER_FROZEN_DRIFT') == 'true' and frontend == 'v0.1.48':
            model['services']['caddy']['environment']['UNEXPECTED_RELEASE_COUPLING'] = frontend
        print(json.dumps(model))
    else:
        assert args[args.index('up'):] == ['up', '-d', '--no-build', '--no-deps', '--force-recreate', 'frontend']
        frontend = pins['FRONTEND_RELEASE_ID']
        assert frontend in ('v0.1.46', 'v0.1.48')
        event('up:frontend:' + frontend)
        if frontend == 'v0.1.48':
            state['frontendImage'], state['frontendContainer'] = identity(5), 'candidate-frontend-container'
            if os.environ.get('TEST_PRUNE_PREVIOUS_TAG') == 'true': state['previousTagPresent'] = False
            if os.environ.get('TEST_BACKEND_DRIFT') == 'true': state['backendContainer'] = 'outside-backend-container'
            if os.environ.get('TEST_CADDY_DRIFT') == 'true': state['caddyContainer'] = 'outside-caddy-container'
            if os.environ.get('TEST_FRONTEND_DRIFT_ROLLBACK') == 'true':
                state.update(frontendImage=identity(9), frontendContainer='outside-frontend-container')
            if os.environ.get('TEST_CONFIG_DRIFT') == 'true':
                with (root / '.env.prod').open('a') as out: out.write('OUTSIDE_CHANGE=true\n')
            if os.environ.get('TEST_TRANSACTION_MOVED') == 'true':
                transaction = pathlib.Path((root / 'created-transaction').read_text().strip())
                moved = root / ('relocated-' + transaction.name)
                transaction.rename(moved)
                transaction.symlink_to(moved, target_is_directory=True)
            save()
            if os.environ.get('TEST_FAIL_CANDIDATE_ABSENT') == 'true':
                state['frontendMissing'] = True
                save()
                raise SystemExit(1)
            if os.environ.get('TEST_FAIL_CANDIDATE_UP') == 'true': raise SystemExit(1)
        else:
            if os.environ.get('TEST_FAIL_ROLLBACK_UP') == 'true': raise SystemExit(1)
            assert state['previousTagPresent']
            state['frontendImage'], state['frontendContainer'] = state['previousTagId'], 'restored-frontend-container'
            state['frontendMissing'] = False
            save()
            if os.environ.get('TEST_ROLLBACK_METADATA_DRIFT') == 'true':
                with pathlib.Path(paths[-1]).open('a') as out: out.write('FRONTEND_RELEASE_ID=v9.9.9\n')
else:
    raise AssertionError('Unexpected Docker operation: ' + ' '.join(args[:3]))
PY
cat > "$test_root/bin/flock" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1" == -n && "$2" == 9 && -f "$TEST_FRONTEND_ROOT/releases/backend-only/.deploy.lock" ]]
[[ "${TEST_LOCK_BUSY:-false}" != true ]]
SH
cat > "$test_root/bin/mktemp" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
result="$("$TEST_REAL_MKTEMP" "$@")"
if [[ "${1:-}" == -d && "${2:-}" == "$TEST_FRONTEND_ROOT/releases/frontend-only/"* ]]; then
  printf '%s\n' "$result" > "$TEST_FRONTEND_ROOT/created-transaction"
  case "${TEST_TRANSACTION_RESULT:-}" in
    relative) result="${result#"$TEST_FRONTEND_ROOT/"}" ;;
    noncanonical) result="${result%/*}/./${result##*/}" ;;
    outside) result="$("$TEST_REAL_MKTEMP" -d "$TEST_FRONTEND_ROOT/outside-transaction.XXXXXXXX")" ;;
  esac
fi
printf '%s\n' "$result"
SH
cat > "$test_root/tools/release/smoke.sh" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1" == v0.1.47 && "$SMOKE_BACKEND_COMMIT" == "$(printf 'b%.0s' {1..40})" ]]
if [[ "$2" == v0.1.46 ]]; then
  [[ "$SMOKE_FRONTEND_COMMIT" == "$(printf 'c%.0s' {1..40})" ]]
  if [[ "${TEST_FAIL_BASELINE_SMOKE:-false}" == true ]]; then exit 1; fi
  if [[ "${TEST_FRONTEND_DRIFT_BASELINE:-false}" == true ]]; then
    python3 - "$TEST_FRONTEND_ROOT/state.json" <<'PY'
import json, pathlib, sys
path=pathlib.Path(sys.argv[1]); state=json.loads(path.read_text())
state.update(frontendImage='sha256:'+'9'*64, frontendContainer='outside-frontend-container')
path.write_text(json.dumps(state))
PY
  fi
elif [[ "$2" == v0.1.48 ]]; then
  [[ "$SMOKE_FRONTEND_COMMIT" == "$(printf 'a%.0s' {1..40})" ]]
  [[ "$SMOKE_ADMIN_IMAGE_ID" == "sha256:$(printf '%064d' 5)" ]]
  python3 "$(dirname "$0")/verify-admin-artifact.py" --image "$SMOKE_ADMIN_IMAGE_ID" --public-url https://epharm.inkar.kz
  if [[ "${TEST_FAIL_CANDIDATE_SMOKE:-false}" == true ]]; then exit 1; fi
else
  exit 1
fi
printf 'smoke:%s/%s\n' "$1" "$2" >> "$TEST_FRONTEND_ROOT/events"
SH
chmod +x "$test_root/bin/docker" "$test_root/bin/flock" "$test_root/bin/mktemp" "$test_root/tools/release/smoke.sh"
export TEST_FRONTEND_ROOT="$test_root"
export PATH="$test_root/bin:$PATH"
commit="$(printf 'a%.0s' {1..40})"
backend_commit="$(printf 'b%.0s' {1..40})"
backend_id="sha256:$(printf '%064d' 2)"
candidate_id="sha256:$(printf '%064d' 5)"
reset_fixture() {
  python3 - "$test_root" "$backup_dir" <<'PY'
import hashlib, gzip, json, pathlib, sys
root = pathlib.Path(sys.argv[1])
(root / 'state.json').write_text(json.dumps({
    'backendImage': 'sha256:' + '2'.zfill(64), 'backendContainer': 'stable-backend-container',
    'frontendImage': 'sha256:' + '3'.zfill(64), 'frontendContainer': 'old-frontend-container',
    'caddyImage': 'sha256:' + '6'.zfill(64), 'caddyContainer': 'stable-caddy-container',
    'previousTagId': 'sha256:' + '3'.zfill(64), 'previousTagPresent': True,
}))
(root / 'events').write_text('')
(root / 'source-calls').write_text('0')
(root / '.env.prod').write_text('JWT_SECRET=fixture-never-log\nMEDUSA_ENABLED=false\n'
    'STOREFRONT_CATALOG_ENABLED=true\nSTOREFRONT_CATALOG_BASE_URL=https://www.aptekasosklada.kz\n')
# Normalize stale generic metadata, but always retain actual backend47/frontend46.
(root / '.release.env').write_text('RELEASE_ID=v0.1.46\nRELEASE_COMMIT=stale-marker\n')
(root / 'docker-compose.prod.yml').write_text('# approved Compose fixture\n')
(root / 'Caddyfile').write_text('# protected Caddy fixture\n')
backup = pathlib.Path(sys.argv[2])
backup.chmod(0o700)
(root / 'releases/frontend-only/active-transaction').unlink(missing_ok=True)
for name, source in (('env.prod','.env.prod'), ('release.env','.release.env'),
                     ('docker-compose.prod.yml','docker-compose.prod.yml'), ('Caddyfile','Caddyfile')):
    (backup / name).write_bytes((root / source).read_bytes())
(backup / 'epharm.dump').write_bytes(b'PGDMPfixture custom dump\n')
for component, tag in (('backend','v0.1.47'),('frontend','v0.1.46')):
    (backup / f'{component}-{tag}.tar.gz').write_bytes(gzip.compress(f'{component} image fixture\n'.encode()))
names = ['epharm.dump','env.prod','release.env','docker-compose.prod.yml','Caddyfile',
         'backend-v0.1.47.tar.gz','frontend-v0.1.46.tar.gz']
(backup / 'SHA256SUMS').write_text(''.join(hashlib.sha256((backup / n).read_bytes()).hexdigest()
    + '  ' + n + '\n' for n in names))
PY
}
reset_fixture
compose_sha="$(shasum -a 256 "$test_root/docker-compose.prod.yml" | awk '{print $1}')"
deploy=(bash "$test_root/tools/release/deploy-frontend-only.sh" v0.1.48 "$commit" "$candidate_id" \
  v0.1.47 "$backend_commit" "$backend_id" v0.1.46 "$backup_dir" "$compose_sha")
assert_no_switch() {
  ! grep -q '^up:' "$test_root/events"
  cmp -s "$test_root/.release.env" "$backup_dir/release.env"
  python3 - "$test_root/state.json" <<'PY'
import json, sys
s=json.load(open(sys.argv[1]))
assert s['backendContainer']=='stable-backend-container' and s['caddyContainer']=='stable-caddy-container'
assert s['frontendContainer']=='old-frontend-container'
PY
}
assert_fixed() {
  python3 - "$test_root/state.json" <<'PY'
import json, sys
s=json.load(open(sys.argv[1]))
assert s['backendContainer']=='stable-backend-container' and s['caddyContainer']=='stable-caddy-container'
assert s['backendImage']=='sha256:'+'2'.zfill(64) and s['caddyImage']=='sha256:'+'6'.zfill(64)
PY
}
assert_pointer() {
  python3 -B - "$source_root/tools/ops/prune-release-backups.py" "$test_root" "$backup_root" <<'PY'
import importlib.util, pathlib, sys
spec = importlib.util.spec_from_file_location('retention', sys.argv[1])
retention = importlib.util.module_from_spec(spec)
spec.loader.exec_module(retention)
assert retention.prune(pathlib.Path(sys.argv[3]), [pathlib.Path(sys.argv[2])], 3, False) == []
PY
}
for failure in TEST_BAD_RELEASE_SOURCE TEST_BAD_ADMIN_ARTIFACT TEST_BAD_CANDIDATE_LABEL \
  TEST_WRONG_ROLLBACK_ARCHIVE TEST_LOCK_BUSY TEST_FAIL_BASELINE_SMOKE TEST_FAIL_SECOND_SOURCE TEST_RENDER_FROZEN_DRIFT; do
  reset_fixture
  if env "$failure=true" "${deploy[@]}" > "$test_root/$failure.log" 2>&1; then
    echo "Accepted failed gate $failure" >&2; exit 1
  fi
  assert_no_switch
done
reset_fixture
if TEST_FINANCE_DENY_IMAGE="$backend_id" "${deploy[@]}" > "$test_root/finance.log" 2>&1; then exit 1; fi
assert_no_switch
reset_fixture
if MEDUSA_ENABLED=true "${deploy[@]}" > "$test_root/catalog.log" 2>&1; then exit 1; fi
assert_no_switch
reset_fixture
printf 'tamper' >> "$backup_dir/epharm.dump"
if "${deploy[@]}" > "$test_root/backup.log" 2>&1; then exit 1; fi
assert_no_switch
reset_fixture
python3 - "$backup_dir/SHA256SUMS" <<'PY'
import os, sys, time
os.utime(sys.argv[1], (time.time()-86401, time.time()-86401))
PY
if "${deploy[@]}" > "$test_root/old-backup.log" 2>&1; then exit 1; fi
assert_no_switch
reset_fixture
bad=("${deploy[@]}"); bad[4]="sha256:$(printf '%064d' 9)"
if "${bad[@]}" > "$test_root/bad-id.log" 2>&1; then exit 1; fi
assert_no_switch
for coordinate in 6 7; do
  reset_fixture
  bad=("${deploy[@]}")
  if [[ "$coordinate" == 6 ]]; then bad[6]="$(printf 'd%.0s' {1..40})";
  else bad[7]="sha256:$(printf '%064d' 9)"; fi
  if "${bad[@]}" > "$test_root/bad-backend-$coordinate.log" 2>&1; then exit 1; fi
  assert_no_switch
done
reset_fixture
printf 'OUTSIDE_CHANGE=true\n' >> "$test_root/.env.prod"
if "${deploy[@]}" > "$test_root/pre-switch-drift.log" 2>&1; then exit 1; fi
assert_no_switch
reset_fixture
"${deploy[@]}" --dry-run > "$test_root/dry-run.log" 2>&1
assert_no_switch
for result in relative noncanonical outside; do
  reset_fixture
  if TEST_TRANSACTION_RESULT="$result" "${deploy[@]}" > "$test_root/transaction-$result.log" 2>&1; then exit 1; fi
  assert_no_switch
  grep -Fq 'ERROR: unsafe frontend transaction directory:' "$test_root/transaction-$result.log"
  [[ ! -e "$test_root/releases/frontend-only/active-transaction" ]]
done
reset_fixture
"${deploy[@]}" > "$test_root/success.log" 2>&1
assert_fixed
grep -Fxq 'BACKEND_RELEASE_ID=v0.1.47' "$test_root/.release.env"
grep -Fxq "BACKEND_RELEASE_COMMIT=$backend_commit" "$test_root/.release.env"
grep -Fxq 'FRONTEND_RELEASE_ID=v0.1.48' "$test_root/.release.env"
grep -Fxq 'RELEASE_ID=v0.1.47' "$test_root/.release.env"
[[ "$(grep -c '^up:' "$test_root/events")" == 1 ]]
[[ -s "$test_root/releases/frontend-only/active-transaction" ]]
assert_pointer
reset_fixture
ln -s "$test_root" "$test_root/deploy-current"
symlink_deploy=("${deploy[@]}")
symlink_deploy[1]="$test_root/deploy-current/tools/release/deploy-frontend-only.sh"
"${symlink_deploy[@]}" > "$test_root/symlink-success.log" 2>&1
assert_fixed
assert_pointer
reset_fixture
if TEST_TRANSACTION_MOVED=true "${deploy[@]}" > "$test_root/moved-transaction.log" 2>&1; then exit 1; fi
assert_fixed
grep -Fq 'ERROR: unsafe frontend transaction directory:' "$test_root/moved-transaction.log"
grep -Fq 'Frontend rollback verified:' "$test_root/moved-transaction.log"
[[ ! -e "$test_root/releases/frontend-only/active-transaction" ]]

for failure in TEST_FAIL_CANDIDATE_SMOKE TEST_FAIL_PUBLIC_BYTES TEST_FAIL_CANDIDATE_UP TEST_FAIL_CANDIDATE_ABSENT; do
  reset_fixture
  if env "$failure=true" "${deploy[@]}" > "$test_root/$failure.log" 2>&1; then exit 1; fi
  assert_fixed
  grep -Fxq 'FRONTEND_RELEASE_ID=v0.1.46' "$test_root/.release.env"
  grep -Fxq 'BACKEND_RELEASE_ID=v0.1.47' "$test_root/.release.env"
  grep -Fxq 'up:frontend:v0.1.46' "$test_root/events"
  grep -Fq 'Frontend rollback verified:' "$test_root/$failure.log"
done
reset_fixture
if TEST_FAIL_CANDIDATE_ABSENT=true TEST_ABSENCE_LIST_FAILURE=true "${deploy[@]}" > "$test_root/unconfirmed-absence.log" 2>&1; then exit 1; fi
assert_fixed
[[ "$(grep -c '^up:' "$test_root/events")" == 1 ]]
grep -Fq 'CRITICAL:' "$test_root/unconfirmed-absence.log"
reset_fixture
if TEST_FAIL_CANDIDATE_SMOKE=true TEST_PRUNE_PREVIOUS_TAG=true "${deploy[@]}" > "$test_root/reload.log" 2>&1; then exit 1; fi
assert_fixed
grep -Fxq 'load:frontend' "$test_root/events"
grep -Fq 'Frontend rollback verified:' "$test_root/reload.log"
reset_fixture
if TEST_FAIL_CANDIDATE_SMOKE=true TEST_FAIL_ROLLBACK_UP=true "${deploy[@]}" > "$test_root/failed-recovery.log" 2>&1; then exit 1; fi
assert_fixed
grep -Fq 'CRITICAL:' "$test_root/failed-recovery.log"
! grep -Fq 'Frontend rollback verified:' "$test_root/failed-recovery.log"
reset_fixture
if TEST_FAIL_CANDIDATE_SMOKE=true TEST_ROLLBACK_METADATA_DRIFT=true "${deploy[@]}" > "$test_root/rollback-metadata-drift.log" 2>&1; then exit 1; fi
assert_fixed
grep -Fq 'CRITICAL:' "$test_root/rollback-metadata-drift.log"
! grep -Fq 'Frontend rollback verified:' "$test_root/rollback-metadata-drift.log"
for drift in TEST_BACKEND_DRIFT TEST_CADDY_DRIFT TEST_CONFIG_DRIFT; do
  reset_fixture
  if env "$drift=true" "${deploy[@]}" > "$test_root/$drift.log" 2>&1; then exit 1; fi
  [[ "$(grep -c '^up:' "$test_root/events")" == 1 ]]
  grep -Fxq 'FRONTEND_RELEASE_ID=v0.1.48' "$test_root/.release.env"
  grep -Fq 'CRITICAL:' "$test_root/$drift.log"
done
for drift in TEST_FRONTEND_DRIFT_PREFLIGHT TEST_FRONTEND_DRIFT_BASELINE TEST_FRONTEND_DRIFT_BEFORE_SWITCH; do
  reset_fixture
  if env "$drift=true" "${deploy[@]}" > "$test_root/$drift.log" 2>&1; then exit 1; fi
  assert_fixed
  ! grep -q '^up:' "$test_root/events"
  cmp -s "$test_root/.release.env" "$backup_dir/release.env"
done
reset_fixture
if TEST_FRONTEND_DRIFT_ROLLBACK=true "${deploy[@]}" > "$test_root/frontend-rollback-drift.log" 2>&1; then exit 1; fi
assert_fixed
[[ "$(grep -c '^up:' "$test_root/events")" == 1 ]]
grep -Fq 'CRITICAL:' "$test_root/frontend-rollback-drift.log"
grep -Fxq 'FRONTEND_RELEASE_ID=v0.1.48' "$test_root/.release.env"
if grep -Fq 'fixture-never-log' "$test_root/"*.log; then
  echo 'Protected Compose/environment appeared in output' >&2; exit 1
fi
[[ -z "$(find "$test_root" -maxdepth 1 -name '.release.frontend-*' -print)" ]]
echo 'Frontend-only source/backup/artifact gates, fixed backend/Caddy, byte-smoke rollback and drift refusal OK'
