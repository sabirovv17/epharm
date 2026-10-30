"""Real release/preparer/recovery shell wiring with an inert Docker daemon.

This does not claim a real main-image rollout or a Windows acceptance result.
"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile
import unittest


SOURCE = Path(__file__).resolve().parents[3]
OLD_BE, OLD_FE, CAND_BE, CAND_FE, NEXT_BE, NEXT_FE = ["sha256:" + f"{i:064x}" for i in (2, 3, 4, 5, 8, 9)]
COMMIT, NEXT_COMMIT = "a" * 40, "b" * 40

DOCKER = r'''#!/usr/bin/env python3
import hashlib, io, json, os, pathlib, sys, tarfile, zipfile
root=pathlib.Path(os.environ['TEST_ROOT']); path=root/'docker-state.json'; state=json.loads(path.read_text()); args=sys.argv[1:]
def save(): path.write_text(json.dumps(state))
def event(value):
    with (root/'events').open('a') as out: out.write(value+'\n')
def image_config(component, meta):
    layer=f'fixture {component} layer'.encode()
    return layer, {'architecture':'amd64','os':'linux','rootfs':{'type':'layers','diff_ids':['sha256:'+hashlib.sha256(layer).hexdigest()]},'config':{'Labels':{'org.opencontainers.image.version':meta['tag'],'org.opencontainers.image.revision':meta['commit']}}}
if args and args[0]=='create':
    assert args[1:3]==['--network','none']; print(hashlib.sha256(args[3].encode()).hexdigest())
elif args and args[0]=='cp':
    container=args[1].split(':')[0]
    meta=next(row for image,row in state['images'].items() if image.startswith('epharm/backend:') and hashlib.sha256(row['id'].encode()).hexdigest()==container)
    count=3 if meta['tag']=='v0.2.1' else 2
    with zipfile.ZipFile(args[2],'w') as jar:
        jar.writestr('BOOT-INF/lib/flyway-core-10.20.1.jar',b'inert fixture')
        for item in state['migrations'][:count]:
            body=item['body']+'-- unreviewed' if os.environ.get('TEST_BAD_MIGRATION_TARGET')=='true' else item['body']
            jar.writestr('BOOT-INF/classes/db/migration/'+item['row']['script'],body)
elif args and args[0]=='rm': pass
elif args[:2]==['inspect','--format']:
    fmt,name=args[2:]; item=state['containers'][name]
    print(item['image'] if fmt=='{{.Image}}' else item['id'])
elif args[:2]==['image','inspect']:
    fmt,image=args[3:]; meta=state['images'][image]
    if fmt=='{{.Id}}': print(meta['id'])
    elif fmt=='{{json .}}':
        component=image.split('/')[1].split(':')[0]; layer,config=image_config(component,meta)
        print(json.dumps({'Architecture':'amd64','Os':'linux','RootFS':{'Layers':config['rootfs']['diff_ids']},'Config':config['config']}))
    elif 'image.version' in fmt: print(meta['tag'])
    elif 'image.revision' in fmt: print(meta['commit'])
    else: raise SystemExit(2)
elif args[:2]==['image','save']:
    image=args[2]; meta=state['images'][image]; component=image.split('/')[1].split(':')[0]
    layer,config=image_config(component,meta); raw=json.dumps(config,separators=(',',':')).encode(); config_id=hashlib.sha256(raw).hexdigest(); layer_id=hashlib.sha256(layer).hexdigest()
    manifest=[{'Config':'blobs/sha256/'+config_id,'RepoTags':[image],'Layers':['blobs/sha256/'+layer_id]}]
    with tarfile.open(fileobj=sys.stdout.buffer,mode='w|') as archive:
        for name,body in [('manifest.json',json.dumps(manifest).encode()),('blobs/sha256/'+config_id,raw),('blobs/sha256/'+layer_id,layer)]:
            item=tarfile.TarInfo(name); item.size=len(body); archive.addfile(item,io.BytesIO(body))
elif args and args[0]=='exec':
    if args[1]=='-i':
        assert args[2:]==['epharm-postgres','pg_restore','--list']; sys.stdin.buffer.read(); event('pg_restore:list')
    else:
        assert args[1:3]==['epharm-postgres','sh']
        if 'pg_dump' in ' '.join(args):
            event('fresh-backup'); sys.stdout.buffer.write(b'PGDMPfixture custom pg dump\n')
            if os.environ.get('TEST_DB_DRIFT_DURING_BACKUP')=='true': state['history'][0]['checksum']+=1; save()
            if os.environ.get('TEST_CONFIG_DRIFT_DURING_BACKUP')=='true':
                with (root/'.env.prod').open('a') as out: out.write('UNREVIEWED_CHANGE=true\n')
        elif 'psql' in ' '.join(args):
            # PostgreSQL's native oid has JSON string representation. The
            # production fingerprint must request bigint for numeric parsing.
            oid=16384 if 'oid::bigint' in args[-1] else '16384'
            print(json.dumps({'system_identifier':'731234567890','database_oid':oid,'history':state['history']}))
        else: raise SystemExit(2)
elif args and args[0]=='compose':
    files=[args[i+1] for i,v in enumerate(args) if v=='--env-file']; pins={}
    for filename in files:
        for line in pathlib.Path(filename).read_text().splitlines():
            if '=' in line and not line.startswith('#'): key,value=line.split('=',1); pins[key]=value
    for key in ['RELEASE_ID','RELEASE_COMMIT','BACKEND_RELEASE_ID','BACKEND_RELEASE_COMMIT','FRONTEND_RELEASE_ID','FRONTEND_RELEASE_COMMIT']:
        if key in os.environ: pins[key]=os.environ[key]
    if 'config' in args:
        print(json.dumps({'services':{'backend':{'image':'epharm/backend:'+pins['BACKEND_RELEASE_ID'],'environment':{'RELEASE_ID':pins['BACKEND_RELEASE_ID'],'RELEASE_COMMIT':pins['BACKEND_RELEASE_COMMIT']}},'frontend':{'image':'epharm/frontend:'+pins['FRONTEND_RELEASE_ID'],'build':{'args':{'VITE_RELEASE_ID':pins['FRONTEND_RELEASE_ID'],'VITE_RELEASE_COMMIT':pins['FRONTEND_RELEASE_COMMIT']}}}}}))
    elif 'up' in args:
        assert args[-7:]==['up','-d','--no-build','--no-deps','--force-recreate','backend','frontend']
        event('up:'+pins['BACKEND_RELEASE_ID']+'/'+pins['FRONTEND_RELEASE_ID']); state['sequence']+=1
        for index,component in enumerate(['backend','frontend']):
            meta=state['images']['epharm/'+component+':'+pins[component.upper()+'_RELEASE_ID']]
            if component=='frontend' and os.environ.get('TEST_PARTIAL_UP')=='true': save(); raise SystemExit(1)
            state['containers']['epharm-'+component]={'image':meta['id'],'id':f"{state['sequence']*10+index:064x}"}
            if component=='backend' and pins['BACKEND_RELEASE_ID']!='v0.1.47':
                count=3 if pins['BACKEND_RELEASE_ID']=='v0.2.1' else 2
                if os.environ.get('TEST_PARTIAL_MIGRATION')=='true': count=1
                old=len(state['history'])
                state['history'] += [item['row'] for item in state['migrations'][old:count]]
                if os.environ.get('TEST_REWRITE_HISTORY_DURING_UP')=='true': state['history'][0]['installed_by']='tampered'
            if os.environ.get('TEST_RELOAD_OLD')=='true': state['images']['epharm/backend:v0.1.47']['id']='sha256:'+f'{6:064x}'
        save()
    else: raise SystemExit(2)
elif args[:2]==['image','load']:
    sys.stdin.buffer.read(); state['images']['epharm/backend:v0.1.47']['id']='sha256:'+f'{6:064x}'; save()
else: raise SystemExit('Unexpected inert Docker call: '+repr(args[:3]))
'''


class ArchiveForwardRecoveryTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="epharm-forward-contract-")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        for name in ("tools/release", "tools/ops", "bin"):
            (self.root / name).mkdir(parents=True)
        for name in ("deploy-archive-two-image.sh", "recover-archive-two-image.sh", "prepare-backend-only-backup.sh", "lib.sh", "verify-image-archive.py"):
            shutil.copy2(SOURCE / "tools/release" / name, self.root / "tools/release" / name)
        shutil.copy2(SOURCE / "tools/ops/lib.sh", self.root / "tools/ops/lib.sh")
        shutil.copy2(SOURCE / "tools/ops/prune-release-backups.py", self.root / "tools/ops/prune-release-backups.py")
        for name in ("merch-artifact", "admin-artifact", "release-source", "financial-transition"):
            shutil.copy2(SOURCE / f"tools/release/tests/fixtures/fake-{name}.py", self.root / f"tools/release/verify-{name}.py")
        self.write(".env.prod", "JWT_SECRET=synthetic-never-output\nMEDUSA_ENABLED=false\nSTOREFRONT_CATALOG_ENABLED=true\nSTOREFRONT_CATALOG_BASE_URL=https://www.aptekasosklada.kz\n")
        self.write(".release.env", self.pins("v0.1.47", "legacy47", "v0.1.53", "legacy53"))
        self.write("docker-compose.prod.yml", "# inert reviewed Compose\n")
        self.write("Caddyfile", "# inert reviewed Caddy\n")
        self.write("bin/docker", DOCKER, 0o700)
        self.write("bin/curl", "#!/usr/bin/env bash\nexit 2\n", 0o700)
        self.write("tools/release/smoke.sh", '''#!/usr/bin/env bash
set -euo pipefail
printf 'smoke:%s/%s\n' "$1" "$2" >> "$TEST_ROOT/events"
[[ "$1" == v0.1.47 || "${TEST_FAIL_CANDIDATE_SMOKE:-false}" != true ]]
if [[ "$1" == v0.1.47 && "${TEST_FAIL_RELOADED_SMOKE:-false}" == true ]]; then
  python3 -c 'import json,os,pathlib; assert json.loads((pathlib.Path(os.environ["TEST_ROOT"])/"docker-state.json").read_text())["sequence"]==100'
fi
''', 0o700)
        images = {}
        for component, tag, identity, commit in (("backend", "v0.1.47", OLD_BE, "legacy47"), ("frontend", "v0.1.53", OLD_FE, "legacy53"), ("backend", "v0.2.0", CAND_BE, COMMIT), ("frontend", "v0.2.0", CAND_FE, COMMIT), ("backend", "v0.2.1", NEXT_BE, NEXT_COMMIT), ("frontend", "v0.2.1", NEXT_FE, NEXT_COMMIT)):
            images[f"epharm/{component}:{tag}"] = {"tag": tag, "id": identity, "commit": commit}
        migrations=[]
        for number, body, checksum in ((1, "\ufeff-- fixture α\r\nSELECT 1;\r\n", -761964677), (2, "-- fixture 2\nSELECT 2;\n", 136109105), (3, "SELECT 3;\n", 914641374)):
            migrations.append({"body":body,"row":{"installed_rank":number,"version":f"{number:03}","type":"SQL","script":f"V{number:03}__fixture.sql","checksum":checksum,"success":True,"installed_by":"synthetic","installed_on":"2026-10-30T00:00:00Z","execution_time":5}})
        state = {"images": images, "migrations":migrations,"history":[migrations[0]['row']], "sequence": 100, "containers": {
            "epharm-backend": {"image": OLD_BE, "id": "1" * 64},
            "epharm-frontend": {"image": OLD_FE, "id": "2" * 64},
            "epharm-caddy": {"image": "unused", "id": "3" * 64},
            "epharm-postgres": {"image": "unused", "id": "4" * 64}}}
        self.write("docker-state.json", json.dumps(state))
        self.write("events", "")
        # A controlled original sale is never restored, deleted or rewritten.
        self.write("original-sales", "sale-after-capture=12500\n")
        self.env = dict(os.environ, TEST_ROOT=str(self.root), PATH=str(self.root / "bin") + ":" + os.environ["PATH"])
        for key in tuple(self.env):
            if key.startswith("TEST_") and key != "TEST_ROOT": self.env.pop(key)
        self.backup_root = self.root / "epharm-backend-only-backups"
        self.compose_sha = self.sha(self.root / "docker-compose.prod.yml")
        self.original_hashes = {name: self.sha(self.root / name) for name in ("original-sales", ".env.prod", "Caddyfile", "docker-compose.prod.yml")}

    @staticmethod
    def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()

    @staticmethod
    def pins(be, bc, fe, fc):
        return f"RELEASE_ID={be}\nRELEASE_COMMIT={bc}\nBACKEND_RELEASE_ID={be}\nBACKEND_RELEASE_COMMIT={bc}\nFRONTEND_RELEASE_ID={fe}\nFRONTEND_RELEASE_COMMIT={fc}\n"

    def write(self, name, value, mode=0o600):
        path = self.root / name
        path.write_text(value)
        path.chmod(mode)

    def run_script(self, script, args, success=True, **extra):
        result = subprocess.run(["bash", str(self.root / "tools/release" / script), *map(str, args)], env=dict(self.env, **extra), capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode == 0, success, result.stdout + result.stderr)
        self.assertNotIn("synthetic-never-output", result.stdout + result.stderr)
        return result

    def create_failure(self, partial=False, **extra):
        self.run_script("prepare-backend-only-backup.sh", ["v0.1.47", "v0.1.53", self.backup_root])
        bundle = next(self.backup_root.glob("pre-backend-only-*"))
        options=dict(TEST_FINANCE_DENY_IMAGE=OLD_BE, TEST_FAIL_CANDIDATE_SMOKE="true", TEST_PARTIAL_UP=str(partial).lower())
        options.update(extra)
        result = self.run_script("deploy-archive-two-image.sh", ["v0.2.0", "v0.2.0", COMMIT, CAND_BE, CAND_FE, "v0.1.47", "v0.1.53", bundle, self.compose_sha], success=False, **options)
        failures = list((self.root / "releases/archive-two-image").glob("*/failed-state.json"))
        self.assertEqual(len(failures), 1, result.stdout + result.stderr)
        self.failure = failures[0]
        self.failure_sha = self.sha(self.failure)
        self.assertEqual(stat.S_IMODE(self.failure.stat().st_mode), 0o600)
        self.immutable = {p.name: self.sha(p) for p in self.failure.parent.iterdir() if p.is_file()}
        self.assertEqual((self.failure.parent / "candidate.release.env").read_text(), self.pins("v0.2.0", COMMIT, "v0.2.0", COMMIT))

    def recovery(self, success=True, next_pair=False, dry=False, **extra):
        args = [self.failure, self.failure_sha, "v0.2.1" if next_pair else "v0.2.0", "v0.2.1" if next_pair else "v0.2.0", NEXT_COMMIT if next_pair else COMMIT,
                NEXT_BE if next_pair else CAND_BE, NEXT_FE if next_pair else CAND_FE, self.backup_root, self.compose_sha]
        if dry: args.append("--dry-run")
        return self.run_script("recover-archive-two-image.sh", args, success=success, **extra)

    def assert_preserved(self):
        for name, digest in self.original_hashes.items(): self.assertEqual(self.sha(self.root / name), digest, name)
        for name, digest in self.immutable.items(): self.assertEqual(self.sha(self.failure.parent / name), digest, name)

    def test_same_pair_retry_has_fresh_backup_and_retains_original_transaction(self):
        self.create_failure()
        before = len(list(self.backup_root.iterdir()))
        self.recovery()
        self.assertEqual(len(list(self.backup_root.iterdir())), before + 1)
        attempt = next(self.failure.parent.glob("recovery-*"))
        self.assertTrue((attempt / "recovered").is_file())
        pointer=self.root/'releases/archive-two-image/recovery-backup'
        audit=json.loads((attempt/'attempt.json').read_text())
        self.assertEqual(pointer.read_text(),audit['fresh_backup']+'\n')
        self.assertEqual(stat.S_IMODE(pointer.stat().st_mode),0o600)
        self.assertFalse((attempt / "failed-state.json").exists())
        self.assert_preserved()
        # The failure is observed once; changed container identities prevent replay.
        self.recovery(success=False)

    def test_known_partial_switch_can_move_to_new_verified_main_pair(self):
        self.create_failure(partial=True)
        record = json.loads(self.failure.read_text())
        self.assertEqual(record["containers"]["backend"]["image_id"], CAND_BE)
        self.assertEqual(record["containers"]["frontend"]["image_id"], OLD_FE)
        self.recovery(next_pair=True)
        self.assertEqual((self.root / ".release.env").read_text(), self.pins("v0.2.1", NEXT_COMMIT, "v0.2.1", NEXT_COMMIT))
        self.assert_preserved()

    def test_failed_forward_records_actual_partial_state_and_never_rolls_back(self):
        self.create_failure()
        self.recovery(success=False, next_pair=True, TEST_PARTIAL_UP="true")
        failed = next(self.failure.parent.glob("recovery-*/failed-state.json"))
        record = json.loads(failed.read_text())
        self.assertEqual(record["containers"]["backend"]["image_id"], NEXT_BE)
        self.assertEqual(record["containers"]["frontend"]["image_id"], CAND_FE)
        self.assertIn("BACKEND_RELEASE_ID=v0.2.1", (self.root / ".release.env").read_text())
        self.assertNotIn("up:v0.1.47/v0.1.53", (self.root / "events").read_text())
        self.assert_preserved()
        self.failure, self.failure_sha = failed, self.sha(failed)
        previous=(self.root/'releases/archive-two-image/recovery-backup').read_bytes()
        self.recovery(next_pair=True)
        attempts=list(self.failure.parent.parent.glob('recovery-*'))
        self.assertTrue(any((p/'previous.recovery-backup').exists() and (p/'previous.recovery-backup').read_bytes()==previous for p in attempts))

    def test_dry_run_is_read_only_and_does_not_require_a_healthy_baseline(self):
        self.create_failure()
        before = {str(p.relative_to(self.root)): self.sha(p) for p in self.root.rglob("*") if p.is_file()}
        self.recovery(dry=True, TEST_FAIL_CANDIDATE_SMOKE="true")
        after = {str(p.relative_to(self.root)): self.sha(p) for p in self.root.rglob("*") if p.is_file()}
        self.assertEqual(before, after)

    def test_marker_hash_symlink_config_pins_and_unknown_container_drift_fail_closed(self):
        self.create_failure()
        original = self.failure.read_bytes()
        self.failure.write_bytes(original + b" ")
        self.recovery(success=False)
        self.failure.write_bytes(original)
        self.failure.rename(self.failure.with_suffix(".saved"))
        self.failure.symlink_to(self.failure.with_suffix(".saved"))
        self.recovery(success=False)
        self.failure.unlink(); self.failure.with_suffix(".saved").rename(self.failure)
        for name in (".env.prod", ".release.env", "Caddyfile", "docker-compose.prod.yml"):
            path=self.root/name; saved=path.read_bytes(); path.write_bytes(saved+b"# drift\n")
            self.recovery(success=False); path.write_bytes(saved)
        path=self.root/'docker-state.json'; saved=path.read_text(); state=json.loads(saved)
        state['containers']['epharm-backend']['image']='sha256:'+'f'*64; path.write_text(json.dumps(state))
        self.recovery(success=False); path.write_text(saved)
        del state['containers']['epharm-backend']; path.write_text(json.dumps(state))
        self.recovery(success=False)

    def test_source_artifact_and_rbac_finance_guards_are_not_bypassed(self):
        self.create_failure()
        for flag in ("TEST_BAD_RELEASE_SOURCE", "TEST_BAD_MERCH_ARTIFACT", "TEST_BAD_ADMIN_ARTIFACT"):
            self.recovery(success=False, **{flag: "true"})
        self.recovery(success=False, TEST_FINANCE_DENY_IMAGE=CAND_BE)
        before = len(list(self.backup_root.iterdir()))
        self.assertEqual(before, 1)
        self.assert_preserved()

    def test_backup_time_db_or_config_drift_denies_switch(self):
        self.create_failure()
        before=(self.root/'events').read_text().count('up:')
        self.recovery(success=False, TEST_DB_DRIFT_DURING_BACKUP="true")
        self.assertEqual((self.root/'events').read_text().count('up:'), before)
        self.assert_preserved()

    def test_config_change_during_fresh_backup_is_not_adopted_as_a_new_baseline(self):
        self.create_failure()
        before=(self.root/'events').read_text().count('up:')
        self.recovery(success=False, TEST_CONFIG_DRIFT_DURING_BACKUP="true")
        self.assertEqual((self.root/'events').read_text().count('up:'), before)
        self.assertFalse(list(self.failure.parent.glob('recovery-*')))
        self.assertEqual(self.sha(self.root/'original-sales'), self.original_hashes['original-sales'])

    def test_caddy_postgres_identity_and_target_label_changes_are_not_guessed(self):
        self.create_failure()
        path=self.root/'docker-state.json'; saved=path.read_text()
        for component in ('epharm-caddy','epharm-postgres'):
            state=json.loads(saved); state['containers'][component]['id']='f'*64
            path.write_text(json.dumps(state)); self.recovery(success=False); path.write_text(saved)
        state=json.loads(saved); state['images']['epharm/backend:v0.2.0']['commit']=NEXT_COMMIT
        path.write_text(json.dumps(state)); self.recovery(success=False); path.write_text(saved)
        self.assert_preserved()

    def test_failure_record_cannot_be_copied_outside_the_transaction_or_made_public(self):
        self.create_failure()
        original=self.failure
        outside=self.root/'failed-state.json'; shutil.copy2(original,outside)
        self.failure=outside; self.recovery(success=False)
        self.failure=original; original.chmod(0o644); self.recovery(success=False); original.chmod(0o600)
        self.assert_preserved()

    def test_busy_shared_deploy_lock_denies_even_dry_run(self):
        import fcntl
        self.create_failure()
        with (self.root/'releases/backend-only/.deploy.lock').open('r') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            self.recovery(success=False, dry=True)
        self.assert_preserved()

    def test_reviewed_flyway_suffix_can_complete_a_partially_migrated_failure(self):
        self.create_failure(TEST_PARTIAL_MIGRATION="true")
        before=json.loads((self.root/'docker-state.json').read_text())['history']
        self.assertEqual(len(before),1)
        self.recovery()
        after=json.loads((self.root/'docker-state.json').read_text())['history']
        self.assertEqual(len(after),2)
        self.assertEqual(after[:len(before)],before)
        self.assert_preserved()

    def test_migration_resource_mismatch_is_denied_before_switch(self):
        self.create_failure()
        before=(self.root/'events').read_text().count('up:')
        self.recovery(success=False, TEST_BAD_MIGRATION_TARGET="true")
        self.assertEqual((self.root/'events').read_text().count('up:'),before)
        self.assert_preserved()

    def test_rewritten_existing_flyway_row_is_not_accepted_as_completion(self):
        self.create_failure(TEST_PARTIAL_MIGRATION="true")
        self.recovery(success=False, TEST_REWRITE_HISTORY_DURING_UP="true")
        self.assertTrue(list(self.failure.parent.glob('recovery-*/failed-state.json')))
        self.assertNotIn('up:v0.1.47/v0.1.53',(self.root/'events').read_text())
        self.assert_preserved()

    def test_verified_content_equivalent_previous_image_id_is_recorded_not_guessed(self):
        self.create_failure(TEST_FINANCE_DENY_IMAGE="", TEST_RELOAD_OLD="true", TEST_FAIL_RELOADED_SMOKE="true")
        record=json.loads(self.failure.read_text())
        self.assertEqual(record['containers']['backend']['image_id'],'sha256:'+f'{6:064x}')
        self.assertTrue(any(row['image_id']=='sha256:'+f'{6:064x}' for row in record['known_images']['backend']))
        self.recovery()
        self.assert_preserved()

    def test_corrupt_stale_or_escaping_incident_reference_denies_before_new_backup(self):
        self.create_failure()
        pointer=self.root/'releases/archive-two-image/recovery-backup'
        before=len(list(self.backup_root.iterdir()))
        for value in ('not-an-absolute-path\n',str(self.backup_root/'pre-backend-only-20261001T000000Z-ABCDEFG1')+'\n',str(self.root/'outside')+'\n'):
            pointer.write_text(value);pointer.chmod(0o600)
            self.recovery(success=False)
            self.assertEqual(len(list(self.backup_root.iterdir())),before)
        pointer.unlink()

    def test_symlinked_entry_directory_resolves_to_the_same_physical_deploy_root(self):
        self.create_failure()
        alias=self.root/'entry-current';alias.symlink_to(self.root,target_is_directory=True)
        self.run_script(str(alias/'tools/release/recover-archive-two-image.sh'),[
            self.failure,self.failure_sha,'v0.2.0','v0.2.0',COMMIT,CAND_BE,CAND_FE,
            self.backup_root,self.compose_sha,'--dry-run'])
        self.assert_preserved()

    def test_native_oid_wire_string_is_explicitly_cast_for_numeric_fingerprint(self):
        self.create_failure()
        native=json.loads(subprocess.check_output(['docker','exec','epharm-postgres','sh','-c','psql','sh','SELECT oid FROM pg_database'],env=self.env,text=True))
        self.assertIsInstance(native['database_oid'],str)
        self.recovery(dry=True)
        self.assert_preserved()


if __name__ == '__main__': unittest.main()
