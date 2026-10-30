"""Opt-in real MinIO contract; cached images only, owned no-network containers.

RUN_MINIO_METADATA_INTEGRATION=1 python3 -m unittest discover -s tools/ops/tests \
    -p test_minio_metadata_integration.py -v
"""
import datetime
from email.header import decode_header
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest

OPS = Path(__file__).resolve().parents[1]
MINIO = 'sha256:8f08aee614800a237906bd48114d733e5ac5bfac4ccdf731f141b0e880d7a253'
MC = 'sha256:5dee113ef037d349ac22ab6c20193ade5c4701e2a38e3777fa1c1bec1c063ad1'
PYTHON = 'sha256:8630ab77c5adf06e1f914483db4dd70e3fa59118160daab9b0ee75e685344221'


@unittest.skipUnless(os.environ.get('RUN_MINIO_METADATA_INTEGRATION') == '1', 'Opt-in isolated cached MinIO test')
class RealMinioMetadataTest(unittest.TestCase):
    def test_old_mirror_loses_metadata_then_exact_replay_preserves_all_fields_and_tags(self):
        stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
        prefix = 'epharm-restore-minio-' + stamp + '-' + str(os.getpid())
        source, target = prefix + '-source', prefix + '-target'
        env = {**os.environ, 'MINIO_METADATA_ACCESS_KEY': 'restore', 'MINIO_METADATA_SECRET_KEY': 'restore-only-password'}
        def docker(*args, **kwargs):
            result = subprocess.run(['docker', *args], capture_output=True, **kwargs)
            if result.returncode: raise AssertionError(result.stderr.decode(errors='replace')[-3000:])
            return result.stdout
        def python(container, code):
            return docker('run', '--rm', '--pull=never', '--network', 'container:' + container,
                          '-v', str(OPS) + ':/helper:ro', '-v', str(stage) + ':/stage',
                          PYTHON, 'python3', '-c', 'import sys;sys.path.insert(0,"/helper");import minio_metadata as m;' + code)
        def mc(container, command):
            return docker('run', '--rm', '--pull=never', '--network', 'container:' + container,
                          '-v', str(stage) + ':/stage', '--entrypoint', '/bin/sh', MC, '-eu', '-c',
                          'mc alias set s http://127.0.0.1:9000 restore restore-only-password >/dev/null; ' + command)
        def cli(action, container, pin=None, success=True):
            args = [sys.executable, str(OPS / 'minio_metadata.py'), action, '--stage', str(stage),
                    '--container', container, '--python-image', PYTHON]
            if pin: args += ['--target-id', pin]
            result = subprocess.run(args, env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode == 0, success, result.stdout + result.stderr)
            return result
        with tempfile.TemporaryDirectory(prefix='epharm-minio-metadata-') as temp:
            stage = Path(temp).resolve(); stage.chmod(0o700)
            (stage / 'data').mkdir(); (stage / 'policies').mkdir()
            (stage / 'policies/fixture.json').write_text('{}')
            (stage / 'manifest.txt').write_text('buckets=fixture\nminio_policy_format=1\nminio_metadata_format=1\n')
            try:
                for name in (source, target):
                    docker('run', '-d', '--pull=never', '--name', name, '--network', 'none',
                           '-e', 'MINIO_ROOT_USER=restore', '-e', 'MINIO_ROOT_PASSWORD=restore-only-password',
                           MINIO, 'server', '/data')
                for _ in range(30):
                    try:
                        python(source, 's=m.S3("http://127.0.0.1:9000","restore","restore-only-password");s.api("/")')
                        break
                    except AssertionError:
                        time.sleep(0.2)
                else: self.fail('Synthetic MinIO not ready')
                python(source, '''s=m.S3("http://127.0.0.1:9000","restore","restore-only-password");s.api("/fixture",method="PUT",body=b"");
for key,ct in [("opaque.jpg","application/octet-stream"),("opaque.mp4","application/octet-stream"),("nested/semi colon.txt","text/plain; charset=utf-8"),("empty.bin","application/octet-stream"),("latin.bin","application/octet-stream"),("utf8.bin","application/octet-stream")]:
 headers={"content-type":ct,"content-disposition":'inline; filename="example; one.bin"',"content-encoding":"identity","cache-control":"max-age=61, private","content-language":"kk","expires":"Fri, 01 Jan 2038 00:00:00 GMT","x-amz-meta-custom":"one;two=three","x-amz-meta-empty":"","x-amz-tagging":"purpose=demo%20%2B%20%2F&realm=synthetic"}
 if key=="empty.bin": headers.pop("x-amz-tagging")
 if key=="latin.bin":
  headers["content-disposition"]='attachment; filename="caf\xe9.png"'
  headers["x-amz-meta-custom"]='caf\xe9'
 if key=="utf8.bin":
  headers["content-disposition"]='attachment; filename="Құжат.png"'.encode("utf8").decode("latin1")
  headers["x-amz-meta-custom"]='дәрі'.encode("utf8").decode("latin1")
 s.api("/fixture/"+m.urllib.parse.quote(key,safe="/"),method="PUT",headers=headers,body=("SYNTHETIC "+key).encode())
''')
                mc(source, 'mc mirror --preserve s/fixture /stage/data/fixture >/dev/null')
                cli('capture', source)
                value = json.loads((stage / 'object-metadata.json').read_text())
                self.assertEqual(value['objectCount'], 6)
                self.assertEqual((stage / 'object-metadata.json').stat().st_mode & 0o777, 0o600)
                mc(target, 'mc mb s/fixture >/dev/null; mc mirror /stage/data/fixture s/fixture >/dev/null')
                # Meaningful RED: original body-only workflow has inferred MIME and lost headers/tags.
                actual = json.loads(python(target, 's=m.S3("http://127.0.0.1:9000","restore","restore-only-password");print(m.json.dumps([s.inspect(r) for r in s.list_objects("fixture")]))'))
                old = {r['key']: r for r in actual}; expected = {r['key']: r for r in value['objects']}
                # urllib exposes header octets as Latin-1; neither original Latin-1
                # nor UTF-8 wire octets may be re-encoded as UTF-8 during signing.
                self.assertEqual(expected['latin.bin']['metadata']['content-disposition'].encode('latin1'),
                                 b'attachment; filename="caf\xe9.png"')
                # MinIO MIME-encodes non-ASCII user metadata on HEAD. The exact
                # captured RFC 2047 header must survive replay; its payload still
                # represents the original wire octets (without guessing charset).
                def metadata_octets(value):
                    return b''.join(v if isinstance(v, bytes) else v.encode('latin1') for v, _ in decode_header(value))
                self.assertEqual(metadata_octets(expected['latin.bin']['metadata']['x-amz-meta-custom']), b'caf\xe9')
                self.assertEqual(expected['utf8.bin']['metadata']['content-disposition'].encode('latin1'),
                                 'attachment; filename="Құжат.png"'.encode('utf8'))
                self.assertEqual(metadata_octets(expected['utf8.bin']['metadata']['x-amz-meta-custom']), 'дәрі'.encode('utf8'))
                self.assertNotEqual(old['opaque.jpg']['metadata']['content-type'], 'application/octet-stream')
                self.assertNotEqual(old['opaque.mp4']['metadata']['content-type'], 'application/octet-stream')
                self.assertEqual(old['opaque.jpg']['tags'], [])
                self.assertEqual({k: r['sha256'] for k, r in old.items()}, {k: r['sha256'] for k, r in expected.items()})
                pin = docker('inspect', '--format', '{{.Id}}', target).decode().strip()
                cli('restore', target, 'f' * 64, success=False)
                cli('restore', source, value['sourceContainerId'], success=False)
                # Body drift fails BEFORE copy/metadata writes, even though local tar/body are valid.
                python(target, 's=m.S3("http://127.0.0.1:9000","restore","restore-only-password");s.api("/fixture/opaque.jpg",method="PUT",headers={"content-type":"image/jpeg"},body=b"WRONG BODY")')
                cli('restore', target, pin, success=False)
                remain = json.loads(python(target, 's=m.S3("http://127.0.0.1:9000","restore","restore-only-password");print(m.json.dumps(s.inspect(next(r for r in s.list_objects("fixture") if r["key"]=="opaque.mp4"))))'))
                self.assertEqual(remain['tags'], [])
                mc(target, 'mc mirror --overwrite /stage/data/fixture s/fixture >/dev/null')
                python(target, 's=m.S3("http://127.0.0.1:9000","restore","restore-only-password");s.api("/fixture/empty.bin",{"tagging":""},method="PUT",body=b"<Tagging><TagSet><Tag><Key>mustDisappear</Key><Value>true</Value></Tag></TagSet></Tagging>")')
                cli('restore', target, pin)
                actual = json.loads(python(target, 's=m.S3("http://127.0.0.1:9000","restore","restore-only-password");print(m.json.dumps([s.inspect(r) for r in s.list_objects("fixture")]))'))
                for row in actual:
                    for field in ['bytes', 'sha256', 'metadata', 'tags']:
                        self.assertEqual(row[field], expected[row['key']][field], (row['key'], field))
                # Replaying the same manifest is safe and exact; old source remains untouched.
                cli('restore', target, pin)
                source_actual = json.loads(python(source, 's=m.S3("http://127.0.0.1:9000","restore","restore-only-password");print(m.json.dumps([s.inspect(r) for r in s.list_objects("fixture")]))'))
                self.assertEqual(source_actual, value['objects'])
            finally:
                for name in (target, source):
                    subprocess.run(['docker', 'rm', '-fv', name], check=False, capture_output=True)


if __name__ == '__main__': unittest.main()
