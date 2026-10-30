#!/usr/bin/env bash
# Forward-only repair of an exactly observed, protected post-switch failure.
# No predecessor fallback, database restore, standalone SQL, or baseline bypass.
set -euo pipefail
umask 077
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd -P)"
# shellcheck source=tools/release/lib.sh
source "$SCRIPT_DIR/lib.sh"
if (( $# != 9 && $# != 10 )) || { (( $# == 10 )) && [[ "${10}" != --dry-run ]]; }; then
  echo 'Usage: recover-archive-two-image.sh <failed-state.json> <state-sha256> <backend-tag> <frontend-tag> <exact-main-commit> <backend-image-id> <frontend-image-id> <absolute-backup-root> <compose-sha256> [--dry-run]' >&2
  exit 2
fi
failure="$1"; expected_failure_sha="$2"; backend_tag="$3"; frontend_tag="$4"; commit="$5"
backend_id="$6"; frontend_id="$7"; backup_root="$8"; compose_sha="$9"; dry_run="${10:-}"
assert_release_id "$backend_tag"; assert_release_id "$frontend_tag"
[[ "$commit" =~ ^[a-f0-9]{40}([a-f0-9]{24})?$ && "$expected_failure_sha" =~ ^[a-f0-9]{64}$ && "$compose_sha" =~ ^[a-f0-9]{64}$ ]] || {
  echo 'ERROR: exact revision/state/Compose digests are required' >&2; exit 2;
}
for identity in "$backend_id" "$frontend_id"; do
  [[ "$identity" =~ ^sha256:[a-f0-9]{64}$ ]] || { echo 'ERROR: exact image IDs are required' >&2; exit 2; }
done
[[ "$backup_root" == /* && "${backup_root##*/}" == epharm-backend-only-backups ]] || {
  echo 'ERROR: a dedicated absolute epharm-backend-only-backups root is required' >&2; exit 2;
}
for command in docker python3 flock gzip; do require_command "$command"; done
python3 - "$backup_root" <<'PY'
import os, pathlib, stat, sys
path=pathlib.Path(sys.argv[1]); parent=path.parent
assert path.is_absolute() and parent.resolve(strict=True)==parent and '\n' not in str(path)
info=parent.stat()
assert info.st_uid==os.geteuid() and not stat.S_IMODE(info.st_mode)&0o022
if path.exists() or path.is_symlink():
    info=path.lstat()
    assert not path.is_symlink() and stat.S_ISDIR(info.st_mode) and info.st_uid==os.geteuid() and stat.S_IMODE(info.st_mode)==0o700
PY

# The marker is data, not shell. Missing containers, unknown/mixed images,
# changed pins, symlinked paths and resolved/replaced containers all fail closed.
inspect_failure() {
  python3 - "$RELEASE_ROOT" "$failure" "$expected_failure_sha" "$compose_sha" <<'PY'
import hashlib, json, os, pathlib, re, stat, subprocess, sys
root, path = map(pathlib.Path, sys.argv[1:3]); expected, compose_sha = sys.argv[3:]
def fail(): raise SystemExit('ERROR: protected failure state is invalid or current state has drifted')
def secure(path, directory=False):
    info = path.lstat()
    if path.is_symlink() or info.st_uid != os.geteuid() or stat.S_IMODE(info.st_mode) != (0o700 if directory else 0o600): fail()
    if not (stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode)): fail()
def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def rows(path):
    secure(path)
    if path.stat().st_size > 16384: fail()
    result = {}
    for line in path.read_text().splitlines():
        key, sep, value = line.partition('=')
        if not sep or key in result: fail()
        result[key] = value
    return result
def docker(*args): return subprocess.check_output(['docker', *args], text=True, stderr=subprocess.DEVNULL).strip()
try:
    if not path.is_absolute() or path.resolve(strict=True) != path or path.name != 'failed-state.json': fail()
    secure(path)
    if path.stat().st_size > 65536 or digest(path) != expected: fail()
    record = json.loads(path.read_text())
    if set(record) != {'format', 'transaction_dir', 'known_images', 'records', 'configs', 'containers', 'caddy_container', 'postgres_container'} or record['format'] != 1: fail()
    tx = pathlib.Path(record['transaction_dir']); protected = root / 'releases/archive-two-image'
    if tx.resolve(strict=True) != tx or tx.parent != protected.resolve(strict=True): fail()
    secure(protected, True); secure(tx, True)
    secure(root / 'releases/backend-only', True); secure(root / 'releases/backend-only/.deploy.lock')
    if path.parent != tx:
        if path.parent.parent != tx or not re.fullmatch(r'recovery-[0-9]{8}T[0-9]{6}Z-[A-Za-z0-9]{8}', path.parent.name): fail()
        secure(path.parent, True)
    if set(record['records']) != {'manifest', 'original.release.env', 'rollback.release.env', 'candidate.release.env'}: fail()
    for name, sha in record['records'].items():
        secure(tx / name)
        if digest(tx / name) != sha: fail()
    manifest = rows(tx / 'manifest')
    if record['caddy_container'] != manifest['previous_caddy_container']: fail()
    if record['postgres_container'] != manifest['previous_postgres_container']: fail()
    keys = {'RELEASE_ID', 'RELEASE_COMMIT', 'BACKEND_RELEASE_ID', 'BACKEND_RELEASE_COMMIT', 'FRONTEND_RELEASE_ID', 'FRONTEND_RELEASE_COMMIT'}
    base = {component: [] for component in ('backend', 'frontend')}
    for phase, filename in (('previous', 'rollback.release.env'), ('candidate', 'candidate.release.env')):
        pins = rows(tx / filename)
        if set(pins) != keys or pins['RELEASE_ID'] != pins['BACKEND_RELEASE_ID'] or pins['RELEASE_COMMIT'] != pins['BACKEND_RELEASE_COMMIT']: fail()
        for component in base:
            if pins[f'{component.upper()}_RELEASE_ID'] != manifest[f'{phase}_{component}_tag']: fail()
            base[component].append({'tag': manifest[f'{phase}_{component}_tag'], 'image_id': manifest[f'{phase}_{component}_id'], 'commit': pins[f'{component.upper()}_RELEASE_COMMIT']})
    if set(record['known_images']) != set(base) or set(record['containers']) != set(base): fail()
    if set(record['configs']) != {'.env.prod', '.release.env', 'docker-compose.prod.yml', 'Caddyfile'}: fail()
    for name, sha in record['configs'].items():
        live = root / name
        if live.resolve(strict=True) != live or not live.is_file() or digest(live) != sha: fail()
    if record['configs']['docker-compose.prod.yml'] != compose_sha: fail()
    current_pins = rows(root / '.release.env')
    if set(current_pins) != keys or current_pins['RELEASE_ID'] != current_pins['BACKEND_RELEASE_ID'] or current_pins['RELEASE_COMMIT'] != current_pins['BACKEND_RELEASE_COMMIT']: fail()
    observed_tags = []
    for component in base:
        known = record['known_images'][component]
        if not isinstance(known, list) or not 2 <= len(known) <= 32 or any(item not in known for item in base[component]): fail()
        for item in known:
            if set(item) != {'tag', 'image_id', 'commit'} or not re.fullmatch(r'v[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?', item['tag']) or not re.fullmatch(r'sha256:[a-f0-9]{64}', item['image_id']) or not re.fullmatch(r'[A-Za-z0-9_.-]+', item['commit']): fail()
        if not any(item['tag'] == current_pins[f'{component.upper()}_RELEASE_ID'] and item['commit'] == current_pins[f'{component.upper()}_RELEASE_COMMIT'] for item in known): fail()
        observed = record['containers'][component]
        if set(observed) != {'image_id', 'container_id'} or not re.fullmatch(r'[a-f0-9]{64}', observed['container_id']): fail()
        matches = [item for item in known if item['image_id'] == observed['image_id']]
        if not matches or any(item != matches[0] for item in matches): fail()
        selected = matches[0]; image = f'epharm/{component}:' + selected['tag']
        if docker('inspect', '--format', '{{.Image}}', 'epharm-' + component) != selected['image_id'] or docker('inspect', '--format', '{{.Id}}', 'epharm-' + component) != observed['container_id']: fail()
        if docker('image', 'inspect', '--format', '{{.Id}}', image) != selected['image_id']: fail()
        for label, value in (('version', selected['tag']), ('revision', selected['commit'])):
            if docker('image', 'inspect', '--format', '{{index .Config.Labels "org.opencontainers.image.' + label + '"}}', image) != value: fail()
        observed_tags.append(selected['tag'])
    for component, key in (('caddy', 'caddy_container'), ('postgres', 'postgres_container')):
        if not re.fullmatch(r'[a-f0-9]{64}', record[key]) or docker('inspect', '--format', '{{.Id}}', 'epharm-' + component) != record[key]: fail()
    values = [str(tx), *observed_tags, record['caddy_container'], record['postgres_container']]
    if any('|' in value or '\n' in value for value in values): fail()
    print('|'.join(values))
except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError): fail()
PY
}
coordinates="$(inspect_failure)"
IFS='|' read -r transaction_dir observed_backend_tag observed_frontend_tag caddy_id postgres_id <<< "$coordinates"
recovery_reference() {
  python3 - "$SCRIPT_DIR/../ops/prune-release-backups.py" "$RELEASE_ROOT" "$backup_root" "${1:-}" "${2:-}" "${3:-}" <<'PY'
import hashlib, importlib.util, os, pathlib, sys, tempfile
sys.dont_write_bytecode=True
spec=importlib.util.spec_from_file_location('recovery_retention',sys.argv[1]); m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
root,backups=map(pathlib.Path,sys.argv[2:4]);fresh,attempt,expected=sys.argv[4:]
parent=root/'releases/archive-two-image'; m.secure(parent,True)
pointer=parent/'recovery-backup'; previous=m.record(pointer,required=False)
digest=hashlib.sha256(previous[0]).hexdigest() if previous is not None else '-'
if previous is not None: m.verify_bundle(m.backup_reference(m.one_path(previous[0],pointer),backups))
if fresh:
    assert digest==expected
    bundle=m.backup_reference(pathlib.Path(fresh),backups);m.verify_bundle(bundle)
    audit=pathlib.Path(attempt);m.secure(audit,True)
    if previous is not None:
        fd=os.open(audit/'previous.recovery-backup',os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
        with os.fdopen(fd,'wb') as output:output.write(previous[0]);output.flush();os.fsync(output.fileno())
    fd,temp=tempfile.mkstemp(prefix='.recovery-backup.',dir=parent)
    with os.fdopen(fd,'wb') as output:output.write((str(bundle)+'\n').encode());output.flush();os.fsync(output.fileno())
    assert m.record(pointer,required=False)==previous
    os.replace(temp,pointer)
    directory=os.open(parent,os.O_RDONLY|os.O_DIRECTORY)
    try:os.fsync(directory)
    finally:os.close(directory)
else:print(digest)
PY
}
reference_before="$(recovery_reference)"
check_target() {
  verify_release_source "$commit"
  verify_merchandising_image "$backend_id"
  verify_financial_transition "$backend_id"
  verify_admin_image "$frontend_id"
  local component tag identity
  for component in backend frontend; do
    if [[ "$component" == backend ]]; then tag="$backend_tag"; identity="$backend_id";
    else tag="$frontend_tag"; identity="$frontend_id"; fi
    [[ "$(docker image inspect --format '{{.Id}}' "epharm/$component:$tag")" == "$identity" \
      && "$(docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.version"}}' "epharm/$component:$tag")" == "$tag" \
      && "$(docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "epharm/$component:$tag")" == "$commit" ]] || {
      echo 'ERROR: forward artifact tags/labels differ from the approved main artifacts' >&2; return 1;
    }
  done
}
# Extract only data from a stopped, network-isolated exact image. Flyway 10.20.1
# ChecksumCalculator (reviewed javap) CRC32s UTF-8 BufferedReader lines without
# separators, filtering a BOM from the first line; it does not strip SQL comments.
migration_contract() {
  python3 - "$backend_id" <<'PY'
import json, pathlib, re, subprocess, sys, tempfile, zipfile, zlib
container=subprocess.check_output(['docker','create','--network','none',sys.argv[1]],text=True).strip()
if not re.fullmatch('[a-f0-9]{64}',container): raise SystemExit('ERROR: invalid artifact inspection container')
try:
    with tempfile.TemporaryDirectory(prefix='epharm-recovery-migrations-') as directory:
        path=pathlib.Path(directory)/'app.jar'
        subprocess.run(['docker','cp',container+':/app/app.jar',str(path)],check=True,stdout=subprocess.DEVNULL)
        with zipfile.ZipFile(path) as jar:
            names=jar.namelist()
            assert len(names)==len(set(names))
            assert [name for name in names if name.startswith('BOOT-INF/lib/flyway-core-')]==['BOOT-INF/lib/flyway-core-10.20.1.jar']
            migrations=[]
            for name in names:
                if not name.startswith('BOOT-INF/classes/db/migration/'): continue
                if name.endswith('/'): continue
                match=re.fullmatch(r'BOOT-INF/classes/db/migration/(V([0-9]+)__[^/]+\.sql)',name)
                assert match and jar.getinfo(name).file_size<=4*1024*1024
                lines=re.split(r'\r\n|\r|\n',jar.read(name).decode('utf-8'))
                if lines and lines[0].startswith('\ufeff'): lines[0]=lines[0][1:]
                crc=0
                for line in lines: crc=zlib.crc32(line.encode('utf-8'),crc)
                migrations.append({'version':int(match[2]),'script':match[1],'checksum':crc if crc<2**31 else crc-2**32})
            migrations.sort(key=lambda row:row['version'])
            assert migrations and len({row['version'] for row in migrations})==len(migrations)
            print(json.dumps(migrations,separators=(',',':')))
finally: subprocess.run(['docker','rm',container],check=True,stdout=subprocess.DEVNULL)
PY
}
check_history() {
  python3 - "$1" "$database_before" "$2" "$migrations" <<'PY'
import json, sys
phase,before,after,target=sys.argv[1],*[json.loads(value) for value in sys.argv[2:]]
assert all(before[key]==after[key] for key in ('system_identifier','database_oid'))
def validate(database):
    rows=database['history']; assert len(rows)<=len(target)
    ranks=[]
    for row,expected in zip(rows,target):
        assert row['success'] is True and row['type']=='SQL' and str(row['version']).isdigit()
        assert int(row['version'])==expected['version'] and row['script']==expected['script'] and type(row['checksum']) is int and row['checksum']==expected['checksum']
        assert type(row['installed_rank']) is int and row['installed_rank']>0
        ranks.append(row['installed_rank'])
    assert ranks==sorted(set(ranks))
validate(before); validate(after)
if phase=='post':
    assert len(after['history'])==len(target) and after['history'][:len(before['history'])]==before['history']
else: assert after==before
PY
}
database_fingerprint() {
  local sql="SELECT json_build_object('system_identifier', (SELECT system_identifier::text FROM pg_control_system()), 'database_oid', (SELECT oid FROM pg_database WHERE datname=current_database()), 'history', (SELECT coalesce(json_agg(row_to_json(h) ORDER BY installed_rank),'[]'::json) FROM flyway_schema_history h))"
  docker exec epharm-postgres sh -c 'exec psql -X -A -t -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "$1"' sh "$sql" \
    | python3 -c 'import json,re,sys; x=json.load(sys.stdin); assert set(x)=={"system_identifier","database_oid","history"} and re.fullmatch("[0-9]+",x["system_identifier"]) and type(x["database_oid"]) is int and x["database_oid"]>0 and isinstance(x["history"],list) and len(x["history"])<=1000; print(json.dumps(x,sort_keys=True,separators=(",",":")))'
}
verify_production_catalog_source
check_target
migrations="$(migration_contract)"
database_before="$(database_fingerprint)"
check_history pre "$database_before"
# Process pin overrides are nonsecret and validated above; Compose rendering
# stays private and the live env file is not replaced during this check.
check_render() {
  RELEASE_ID="$backend_tag" RELEASE_COMMIT="$commit" \
    BACKEND_RELEASE_ID="$backend_tag" BACKEND_RELEASE_COMMIT="$commit" \
    FRONTEND_RELEASE_ID="$frontend_tag" FRONTEND_RELEASE_COMMIT="$commit" \
    compose_prod config --format json | python3 -c 'import json,sys; s=json.load(sys.stdin)["services"]; b=s["backend"]; f=s["frontend"]; a=f.get("build",{}).get("args",{}); t=sys.argv[1:]; assert b["image"]=="epharm/backend:"+t[0] and b["environment"]["RELEASE_ID"]==t[0] and b["environment"]["RELEASE_COMMIT"]==t[2] and f["image"]=="epharm/frontend:"+t[1] and a.get("VITE_RELEASE_ID")==t[1] and a.get("VITE_RELEASE_COMMIT")==t[2]' "$backend_tag" "$frontend_tag" "$commit"
}
check_render
lock="$RELEASE_ROOT/releases/backend-only/.deploy.lock"
[[ -f "$lock" && ! -L "$lock" ]] || { echo 'ERROR: existing deployment lock is required' >&2; exit 1; }
if [[ "$dry_run" == --dry-run ]]; then
  exec 9<"$lock"; flock -n 9 || { echo 'ERROR: another application backup/deployment is active' >&2; exit 1; }
  [[ "$(inspect_failure)" == "$coordinates" && "$(database_fingerprint)" == "$database_before" ]]
  [[ "$(recovery_reference)" == "$reference_before" ]]
  check_target; check_render
  printf 'Forward recovery preflight passed; no backup, pins or application containers changed\n'
  exit 0
fi
# The preparer takes the same flock, so it must finish before we acquire it.
# This bundle preserves CURRENT sales, configs and observed images; an unhealthy
# observed pair is not described as a healthy rollback baseline.
fresh_output="$("$SCRIPT_DIR/prepare-backend-only-backup.sh" "$observed_backend_tag" "$observed_frontend_tag" "$backup_root")"
fresh_backup="${fresh_output#Verified backend-only backup: }"
exec 9<"$lock"; flock -n 9 || { echo 'ERROR: another application backup/deployment is active' >&2; exit 1; }
[[ "$(inspect_failure)" == "$coordinates" && "$(database_fingerprint)" == "$database_before" ]] || {
  echo 'ERROR: failed deployment or database identity/Flyway history changed during backup' >&2; exit 1;
}
[[ "$(recovery_reference)" == "$reference_before" ]] || { echo 'ERROR: recovery protection changed during backup' >&2; exit 1; }
python3 - "$backup_root" "$fresh_backup" "$RELEASE_ROOT" "$observed_backend_tag" "$observed_frontend_tag" <<'PY'
import hashlib, os, pathlib, re, stat, sys, time
root, bundle, live = map(pathlib.Path, sys.argv[1:4]); tags = sys.argv[4:]
def secure(path, directory=False):
    info=path.lstat()
    assert not path.is_symlink() and info.st_uid==os.geteuid() and stat.S_IMODE(info.st_mode)==(0o700 if directory else 0o600)
    assert stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode)
def sha(path):
    h=hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024*1024),b''): h.update(chunk)
    return h.hexdigest()
assert root.resolve(strict=True)==root and bundle.resolve(strict=True)==bundle and bundle.parent==root
secure(root,True); secure(bundle,True); secure(bundle/'SHA256SUMS')
assert 0 <= time.time()-(bundle/'SHA256SUMS').stat().st_mtime <= 3600
required={'epharm.dump','env.prod','release.env','docker-compose.prod.yml','Caddyfile',f'backend-{tags[0]}.tar.gz',f'frontend-{tags[1]}.tar.gz'}
seen=set()
for line in (bundle/'SHA256SUMS').read_text().splitlines():
    digest, name=line.split('  ',1)
    assert re.fullmatch('[a-f0-9]{64}',digest) and pathlib.Path(name).name==name and name not in seen
    seen.add(name); secure(bundle/name)
    assert (bundle/name).stat().st_size>0 and sha(bundle/name)==digest
assert seen==required
for saved, current in [('env.prod','.env.prod'),('release.env','.release.env'),('docker-compose.prod.yml','docker-compose.prod.yml'),('Caddyfile','Caddyfile')]: assert sha(bundle/saved)==sha(live/current)
PY
docker exec -i epharm-postgres pg_restore --list < "$fresh_backup/epharm.dump" > /dev/null
for component in backend frontend; do
  if [[ "$component" == backend ]]; then tag="$observed_backend_tag"; else tag="$observed_frontend_tag"; fi
  gzip -t "$fresh_backup/$component-$tag.tar.gz"
  python3 "$SCRIPT_DIR/verify-image-archive.py" "$fresh_backup/$component-$tag.tar.gz" "epharm/$component:$tag" > /dev/null
done
check_target; check_render
attempt="$(mktemp -d "$transaction_dir/recovery-$(date -u +%Y%m%dT%H%M%SZ)-XXXXXXXX")"
printf 'RELEASE_ID=%s\nRELEASE_COMMIT=%s\nBACKEND_RELEASE_ID=%s\nBACKEND_RELEASE_COMMIT=%s\nFRONTEND_RELEASE_ID=%s\nFRONTEND_RELEASE_COMMIT=%s\n' \
  "$backend_tag" "$commit" "$backend_tag" "$commit" "$frontend_tag" "$commit" > "$attempt/target.release.env"
python3 - "$attempt" "$failure" "$expected_failure_sha" "$fresh_backup" "$backend_id" "$frontend_id" "$database_before" <<'PY'
import json, os, pathlib, sys
attempt=pathlib.Path(sys.argv[1])
record={'failure_record':sys.argv[2], 'failure_sha256':sys.argv[3], 'fresh_backup':sys.argv[4], 'backend_id':sys.argv[5], 'frontend_id':sys.argv[6], 'database':json.loads(sys.argv[7])}
with (attempt/'attempt.json').open('x') as output: json.dump(record,output,sort_keys=True); output.write('\n'); output.flush(); os.fsync(output.fileno())
PY
staged="$(mktemp "$RELEASE_ROOT/.release.archive-recovery.XXXXXXXX")"
cp -p "$attempt/target.release.env" "$staged"
switched=false
finish() {
  local status=$?
  trap - EXIT
  if [[ "$switched" == true ]]; then
    # Leave the reviewed capable target selected. Never restart the old v47 or
    # restore a database over new sales. Record ACTUAL partial-switch state.
    python3 - "$RELEASE_ROOT" "$failure" "$attempt" "$backend_tag" "$frontend_tag" "$commit" "$backend_id" "$frontend_id" <<'PY' || echo 'CRITICAL: recovery failure marker unavailable; manual inspection required' >&2
import hashlib, json, os, pathlib, subprocess, sys
root, parent, attempt=map(pathlib.Path,sys.argv[1:4]); bt,ft,commit,bi,fi=sys.argv[4:]
record=json.loads(parent.read_text())
for component,tag,image in [('backend',bt,bi),('frontend',ft,fi)]:
    value={'tag':tag,'image_id':image,'commit':commit}
    if value not in record['known_images'][component]: record['known_images'][component].append(value)
def inspect(name,field):
    p=subprocess.run(['docker','inspect','--format','{{.'+field+'}}','epharm-'+name],capture_output=True,text=True)
    return p.stdout.strip() if p.returncode==0 else ''
record['configs']['.release.env']=hashlib.sha256((root/'.release.env').read_bytes()).hexdigest()
record['containers']={name:{'image_id':inspect(name,'Image'),'container_id':inspect(name,'Id')} for name in ('backend','frontend')}
fd=os.open(attempt/'failed-state.json',os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
with os.fdopen(fd,'w') as output: json.dump(record,output,sort_keys=True); output.write('\n'); output.flush(); os.fsync(output.fileno())
PY
    echo "CRITICAL: forward recovery failed; observed state recorded at $attempt; no predecessor or DB rollback attempted" >&2
  fi
  rm -f -- "$staged"
  exit "$status"
}
trap finish EXIT
trap 'exit 1' INT TERM
[[ "$(inspect_failure)" == "$coordinates" && "$(database_fingerprint)" == "$database_before" ]]
check_target
recovery_reference "$fresh_backup" "$attempt" "$reference_before"
switched=true
mv -f "$staged" "$RELEASE_ROOT/.release.env"
compose_prod up -d --no-build --no-deps --force-recreate backend frontend
SMOKE_BACKEND_COMMIT="$commit" SMOKE_FRONTEND_COMMIT="$commit" SMOKE_ADMIN_IMAGE_ID="$frontend_id" \
  "$SCRIPT_DIR/smoke.sh" "$backend_tag" "$frontend_tag"
[[ "$(docker inspect --format '{{.Image}}' epharm-backend)" == "$backend_id" \
  && "$(docker inspect --format '{{.Image}}' epharm-frontend)" == "$frontend_id" \
  && "$(docker inspect --format '{{.Id}}' epharm-caddy)" == "$caddy_id" \
  && "$(docker inspect --format '{{.Id}}' epharm-postgres)" == "$postgres_id" ]]
database_after="$(database_fingerprint)"
check_history post "$database_after"
printf '%s\n' "$database_after" > "$attempt/database.after.json"
for pair in 'env.prod:.env.prod' 'docker-compose.prod.yml:docker-compose.prod.yml' 'Caddyfile:Caddyfile'; do
  [[ "$(sha256_file "$fresh_backup/${pair%%:*}")" == "$(sha256_file "$RELEASE_ROOT/${pair#*:}")" ]]
done
[[ "$(sha256_file "$RELEASE_ROOT/.release.env")" == "$(sha256_file "$attempt/target.release.env")" ]]
printf 'recovered_at=%s\nbackend_id=%s\nfrontend_id=%s\n' "$(date -u +%FT%TZ)" "$backend_id" "$frontend_id" > "$attempt/recovered"
switched=false
echo "Forward recovery verified: $backend_tag/$frontend_tag; protected audit: $attempt"
