import gzip
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parents[3]


@unittest.skipUnless(sys.platform.startswith('linux'), 'Production shell orchestration requires Linux bash/flock')
class PrivateBackupChainTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name).resolve(); self.root.chmod(0o700)
        self.backups = self.root / 'backups'; self.backups.mkdir(mode=0o700)
        self.bin = self.root / 'bin'; self.bin.mkdir(mode=0o700)
        self.events = self.root / 'events'
        self.env = {**os.environ, 'PATH': f'{self.bin}:{os.environ["PATH"]}', 'BACKUP_ROOT': str(self.backups),
                    'BACKUP_PRIVATE_FILES_REQUIRED': 'true', 'RESTIC_REPOSITORY': 'fixture:no-network',
                    'RESTIC_PASSWORD': 'fixture-only-no-key', 'TEST_EVENTS': str(self.events),
                    'EPHARM_ENV_FILE': str(self.root / 'fixture.env'),
                    'MINIO_METADATA_PYTHON_IMAGE': 'sha256:' + 'f' * 64}
        self.write(self.root / 'fixture.env', b'MINIO_ROOT_USER=fixture\nMINIO_ROOT_PASSWORD=fixture-only\n')
        for label, variable in [('merch', 'MERCH_EVIDENCE_HOST_ROOT'), ('merch-legacy', 'MERCH_LEGACY_FILES_HOST_ROOT'),
                                ('learner-credentials', 'LEARNER_CREDENTIALS_HOST_KEY_DIRECTORY')]:
            path = self.root / label; path.mkdir(mode=0o700); self.env[variable] = str(path)
            self.write(path / ('keys.json' if label == 'learner-credentials' else 'fixture.bin'),
                       b'never-print-synthetic-key' if label == 'learner-credentials' else label.encode())
        self.program('restic', '''import json,os,sys
with open(os.environ['TEST_EVENTS'],'a') as out: out.write(json.dumps(sys.argv[1:])+'\\n')
''')
        self.program('docker', '''import os,sys,pathlib
args=sys.argv[1:]
with open(os.environ['TEST_EVENTS'],'a') as out: out.write('docker\\n')
if args[0]=='inspect':
    import json
    if args[2]=='{{.Id}}': print('b'*64)
    else: print('\\n'.join(json.dumps(v) for v in ['b'*64,'/'+args[-1],True,{}, {},'none']))
if args[0]=='exec' and 'pg_restore' in args: sys.stdin.buffer.read()
if args[0]=='exec' and 'psql' in args:
    print('87' if 'flyway_schema_history' in args[-1] else '3')
if '--entrypoint' in args:
    mount=args[args.index('-v')+1].split(':')[0]
    stage=pathlib.Path(mount).parent
    assert (stage/'native-files/merch/fixture.bin').read_bytes()==b'merch'
    assert (stage/'native-files/merch-legacy/fixture.bin').read_bytes()==b'merch-legacy'
    assert (stage/'credential-keys/learner-credentials/keys.json').read_bytes()==b'never-print-synthetic-key'
    import shutil
    shutil.copytree(stage/'policies',stage/'restored-policies',dirs_exist_ok=True)
    if os.environ.get('REPLACE_RESTORE_STAGE')=='true':
        stage.rename(str(stage)+'.original-owned')
        stage.mkdir(mode=0o700)
        (stage/'replacement-marker').write_bytes(b'concurrent-stage-preserved')
''')
        self.run_script('private-files-backup.sh')

    def tearDown(self): self.temporary.cleanup()

    def write(self, path, body): path.write_bytes(body); path.chmod(0o600)

    def program(self, name, source):
        path = self.bin / name; path.write_text(f'#!{sys.executable}\n' + source); path.chmod(0o700)

    def run_script(self, name, success=True):
        result = subprocess.run(['bash', str(SOURCE / 'tools' / name)], env=self.env, capture_output=True, text=True)
        self.assertEqual(result.returncode == 0, success, result.stdout + result.stderr)
        self.assertNotIn('never-print-synthetic-key', result.stdout + result.stderr)
        return result

    def classic_backups(self):
        pg = self.backups / 'postgres'; pg.mkdir(mode=0o700)
        dump = pg / 'epharm-postgres-20261030T010000Z.dump.gz'; self.write(dump, gzip.compress(b'PGDMP fixture'))
        minio = self.backups / 'minio'; minio.mkdir(mode=0o700)
        archive = minio / 'epharm-minio-20261030T010000Z.tar.gz'
        metadata = {'schemaVersion': 1, 'capturedAt': '2026-10-30T00:00:00Z', 'sourceContainerId': 'a' * 64,
                    'buckets': ['epharm-receipts'], 'objectCount': 1, 'totalBytes': 7, 'objects': [{
                    'bucket': 'epharm-receipts', 'key': 'object', 'bytes': 7,
                    'sha256': hashlib.sha256(b'fixture').hexdigest(), 'etag': '"source"',
                    'lastModified': '2026-10-30T00:00:00Z', 'metadata': {'content-type': 'application/octet-stream'}, 'tags': []}]}
        with tarfile.open(archive, 'w:gz') as output:
            for name in ['data', 'data/epharm-receipts', 'policies']:
                entry = tarfile.TarInfo(name); entry.type = tarfile.DIRTYPE; entry.mode = 0o700; output.addfile(entry)
            for name, body in [('manifest.txt', b'buckets=epharm-receipts\nminio_policy_format=1\nminio_metadata_format=1\n'),
                               ('data/epharm-receipts/object', b'fixture'), ('policies/epharm-receipts.json', b'{}'),
                               ('object-metadata.json', json.dumps(metadata).encode())]:
                entry = tarfile.TarInfo(name); entry.size = len(body); entry.mode = 0o600; output.addfile(entry, io.BytesIO(body))
        archive.chmod(0o600)
        for path in (dump, archive):
            self.write(Path(str(path) + '.sha256'), f'{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n'.encode())

    def test_real_private_producer_offsite_covers_four_separate_roots(self):
        self.classic_backups()
        self.run_script('offsite-backup.sh')
        calls = [json.loads(value) for value in self.events.read_text().splitlines()]
        backup = next(value for value in calls if value[0] == 'backup')
        for kind in ('postgres', 'minio', 'native-files', 'credential-keys'):
            self.assertIn(str(self.backups / kind), backup)
        self.assertEqual(len(list((self.backups / 'credential-keys').glob('*.tar.gz'))), 1)

    def test_missing_or_corrupt_private_artifact_prevents_all_offsite_calls(self):
        self.classic_backups()
        key = next((self.backups / 'credential-keys').glob('*.tar.gz')); self.write(key, b'corrupt')
        self.run_script('offsite-backup.sh', False)
        self.assertFalse(self.events.exists())

    def test_isolated_restore_verifies_private_files_before_success(self):
        self.classic_backups()
        self.run_script('restore-test.sh')
        metric = self.backups / 'metrics/epharm_restore_test.prom'
        self.assertIn('epharm_restore_test_last_success_timestamp_seconds', metric.read_text())
        self.assertFalse(list(self.backups.glob('.restore-*')))
        self.assertTrue((self.root / 'merch/fixture.bin').exists())

    def test_required_missing_key_fails_restore_before_docker(self):
        self.classic_backups()
        shutil.rmtree(self.backups / 'credential-keys')
        self.run_script('restore-test.sh', False)
        self.assertFalse(self.events.exists())
        self.assertFalse((self.backups / 'metrics/epharm_restore_test.prom').exists())

    def test_restore_ignores_preexisting_stages_and_preserves_concurrent_replacement(self):
        self.classic_backups()
        old = self.backups / '.restore-preexisting'; old.mkdir(mode=0o700)
        self.write(old / 'keep', b'preexisting-stage-preserved')
        link = self.backups / '.restore-symlink'; link.symlink_to(old, target_is_directory=True)
        self.env['REPLACE_RESTORE_STAGE'] = 'true'
        self.run_script('restore-test.sh', False)
        self.assertEqual((old / 'keep').read_bytes(), b'preexisting-stage-preserved')
        self.assertTrue(link.is_symlink())
        replacements = list(self.backups.glob('.restore-*/replacement-marker'))
        self.assertEqual(len(replacements), 1)
        self.assertEqual(replacements[0].read_bytes(), b'concurrent-stage-preserved')
        self.assertFalse((self.backups / 'metrics/epharm_restore_test.prom').exists())


if __name__ == '__main__': unittest.main()
