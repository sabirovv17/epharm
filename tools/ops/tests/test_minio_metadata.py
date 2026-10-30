import copy
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch

OPS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(OPS))
import minio_metadata as m
SPEC = importlib.util.spec_from_file_location('metadata_retention', OPS / 'prune-backups.py')
retention = importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(retention)


def fixture(body=b'synthetic body'):
    return {'schemaVersion': 1, 'capturedAt': '2026-10-30T00:00:00Z', 'sourceContainerId': 'a' * 64,
            'buckets': ['fixture'], 'objectCount': 1, 'totalBytes': len(body), 'objects': [
                {'bucket': 'fixture', 'key': 'nested/object.jpg', 'bytes': len(body),
                 'sha256': hashlib.sha256(body).hexdigest(), 'etag': '"source-witness"',
                 'lastModified': '2026-10-30T00:00:00.000Z', 'metadata': {
                     'content-type': 'application/octet-stream; version=one',
                     'content-disposition': 'inline; filename="object.jpg"', 'content-encoding': 'identity',
                     'cache-control': 'max-age=61, private', 'content-language': 'kk',
                     'expires': 'Fri, 01 Jan 2038 00:00:00 GMT', 'x-amz-meta-source': 'fixture; exact'},
                 'tags': [{'key': 'purpose', 'value': 'test + /;'}]}]}


def write_stage(stage, value=None, body=b'synthetic body'):
    value = value or fixture(body)
    (stage / 'data/fixture/nested').mkdir(parents=True)
    (stage / 'data/fixture/nested/object.jpg').write_bytes(body)
    (stage / 'policies').mkdir(); (stage / 'policies/fixture.json').write_text('{}')
    (stage / 'manifest.txt').write_text('buckets=fixture\nminio_policy_format=1\nminio_metadata_format=1\n')
    (stage / m.NAME).write_text(json.dumps(value))
    return value


class MetadataTest(unittest.TestCase):
    def test_strict_roundtrip_and_sha_inventory(self):
        with tempfile.TemporaryDirectory() as temp:
            stage = Path(temp); value = write_stage(stage)
            self.assertEqual(m.validate_stage(stage), value)
            (stage / 'data/fixture/nested/object.jpg').write_bytes(b'drift')
            with self.assertRaisesRegex(ValueError, 'hash mismatch'): m.validate_stage(stage)

    def test_invalid_schema_duplicate_path_header_control_and_count(self):
        changes = [lambda v: v.update(schemaVersion=2), lambda v: v.update(objectCount=2),
                   lambda v: v['objects'].append(v['objects'][0]),
                   lambda v: v['objects'][0].update(key='../escape'),
                   lambda v: v['objects'][0].update(key='a//b'),
                   lambda v: v['objects'][0].update(key='/absolute'),
                   lambda v: v['objects'][0].update(key='a\x00b'),
                   lambda v: v['objects'][0]['metadata'].update({'authorization': 'bad'}),
                   lambda v: v['objects'][0]['metadata'].update({'content-type': 'a\r\nbad'}),
                   lambda v: v['objects'][0]['tags'].append(v['objects'][0]['tags'][0])]
        for change in changes:
            value = fixture(); change(value)
            with self.subTest(change=change), self.assertRaises(ValueError): m.metadata_value(json.dumps(value).encode())
        with self.assertRaises(ValueError): m.metadata_value(b'{"schemaVersion":1,"schemaVersion":1}')
        with self.assertRaises(ValueError): m.metadata_value(b' ' * (m.MAX_MANIFEST + 1))

    def test_legacy_requires_explicit_permission_and_never_claims_metadata(self):
        with tempfile.TemporaryDirectory() as temp:
            stage = Path(temp); (stage / 'manifest.txt').write_text('buckets=fixture\n')
            with self.assertRaises(ValueError): m.validate_stage(stage)
            self.assertIsNone(m.validate_stage(stage, allow_legacy=True))
            (stage / m.NAME).write_text('{}')
            with self.assertRaises(ValueError): m.validate_stage(stage, allow_legacy=True)

    def test_archive_validates_body_before_extraction_and_requires_metadata_for_native(self):
        with tempfile.TemporaryDirectory() as temp:
            stage = Path(temp); write_stage(stage)
            def archive():
                result = io.BytesIO()
                with tarfile.open(fileobj=result, mode='w') as tar:
                    for name in ('manifest.txt', 'data', 'policies', m.NAME): tar.add(stage / name, arcname=name)
                result.seek(0); return result
            retention.validate_minio(archive(), require_metadata=True)
            (stage / 'data/fixture/nested/object.jpg').write_bytes(b'corrupted')
            with self.assertRaises(ValueError): retention.validate_minio(archive(), require_metadata=True)
            (stage / m.NAME).unlink()
            (stage / 'manifest.txt').write_text('buckets=fixture\nminio_policy_format=1\n')
            result = io.BytesIO()
            with tarfile.open(fileobj=result, mode='w') as tar:
                for name in ('manifest.txt', 'data', 'policies'): tar.add(stage / name, arcname=name)
            result.seek(0)
            with self.assertRaisesRegex(ValueError, 'Required object'): retention.validate_minio(result, require_metadata=True)
            result.seek(0); retention.validate_minio(result)

    def test_symlink_or_extra_body_refused(self):
        with tempfile.TemporaryDirectory() as temp:
            stage = Path(temp); write_stage(stage)
            (stage / 'data/fixture/extra').write_bytes(b'not captured')
            with self.assertRaises(ValueError): m.validate_stage(stage)
            (stage / 'data/fixture/extra').unlink(); (stage / 'data/fixture/nested/object.jpg').unlink()
            (stage / 'data/fixture/nested/object.jpg').symlink_to(stage / 'manifest.txt')
            with self.assertRaises(ValueError): m.validate_stage(stage)

    def test_capture_if_match_and_late_metadata_drift_prevents_publication(self):
        from unittest.mock import Mock
        with tempfile.TemporaryDirectory() as temp:
            stage = Path(temp); value = write_stage(stage); (stage / m.NAME).unlink()
            row = {k: value['objects'][0][k] for k in ('bucket', 'key', 'bytes', 'etag', 'lastModified')}
            s3 = Mock(); s3.api.return_value = (b'<VersioningConfiguration/>', {}); s3.list_objects.return_value = [row]
            s3.inspect.side_effect = [value['objects'][0], {**value['objects'][0], 'metadata': {'content-type': 'changed'}}]
            with self.assertRaisesRegex(ValueError, 'metadata/tag drift'): m.capture(stage, s3, 'a' * 64)
            self.assertFalse((stage / m.NAME).exists())
            # HTTP capture itself sends If-Match and compares the returned digest/headers.
            api = Mock(side_effect=[(b'', {'content-length': str(row['bytes']), 'etag': row['etag'], 'content-type': 'image/jpeg'}),
                                   (b'<Tagging><TagSet/></Tagging>', {}),
                                   ((row['bytes'], 'b' * 64), {'etag': '"changed"', 'content-type': 'image/jpeg'})])
            client = m.S3('http://127.0.0.1:9000', 'fixture', 'fixture-secret'); client.api = api
            with self.assertRaisesRegex(ValueError, 'GET drift'): client.inspect(row)
            self.assertEqual(api.call_args.kwargs['headers']['if-match'], row['etag'])

    def test_capture_refuses_local_mc_drift_and_versioned_source_without_publishing(self):
        from unittest.mock import Mock
        with tempfile.TemporaryDirectory() as temp:
            stage = Path(temp); value = write_stage(stage); (stage / m.NAME).unlink()
            obj = value['objects'][0]
            row = {k: obj[k] for k in ('bucket', 'key', 'bytes', 'etag', 'lastModified')}
            s3 = Mock(); s3.api.return_value = (b'<VersioningConfiguration/>', {})
            s3.list_objects.return_value = [row]
            s3.inspect.side_effect = [obj, {k: v for k, v in obj.items() if k != 'sha256'}]
            (stage / 'data/fixture/nested/object.jpg').write_bytes(b'stale mc body')
            with self.assertRaisesRegex(ValueError, 'mc body differs'): m.capture(stage, s3, 'a' * 64)
            self.assertFalse((stage / m.NAME).exists())
            s3.api.return_value = (b'<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>', {})
            with self.assertRaisesRegex(ValueError, 'version-aware backup'): m.capture(stage, s3, 'a' * 64)
            self.assertFalse((stage / m.NAME).exists())

    def test_wrong_target_or_pin_or_network_refused_before_s3_write(self):
        target = 'epharm-restore-minio-20261030T000000Z-12'
        def inspection(name=target, identity='b' * 64, ports=None, network='none'):
            return ('\n'.join(json.dumps(v) for v in [identity, '/' + name, True, {}, ports or {}, network]) + '\n').encode()
        for name, pin, response in [('epharm-minio', 'b' * 64, inspection(name='epharm-minio')),
                                    (target, 'c' * 64, inspection()),
                                    (target, 'b' * 64, inspection(ports={'9000/tcp': [{'HostPort': '9000'}]})),
                                    (target, 'b' * 64, inspection(network='bridge'))]:
            with patch.object(m.subprocess, 'check_output', return_value=response), self.assertRaises(ValueError):
                m.inspect_container(name, pin, writable=True)
        with patch.object(m.subprocess, 'check_output', return_value=inspection()):
            self.assertEqual(m.inspect_container(target, 'b' * 64, True), ('b' * 64, None))
        owned_network = 'epharm-restore-20261030T000000Z-12'
        bridge = ('\n'.join(json.dumps(v) for v in ['b' * 64, '/' + target, True,
                  {owned_network: {'IPAddress': '172.19.0.2'}}, {}, owned_network]) + '\n').encode()
        with patch.object(m.subprocess, 'check_output', side_effect=[bridge, b'false\n']), self.assertRaises(ValueError):
            m.inspect_container(target, 'b' * 64, True)
        with patch.object(m.subprocess, 'check_output', side_effect=[bridge, b'true\n']):
            self.assertEqual(m.inspect_container(target, 'b' * 64, True), ('b' * 64, 'http://172.19.0.2:9000'))
        for endpoint in ['http://example.com:9000', 'http://8.8.8.8:9000', 'http://127.0.0.1:9000/x',
                         'https://127.0.0.1:9000', 'http://user@127.0.0.1:9000']:
            with self.assertRaises(ValueError): m.S3(endpoint, 'fixture', 'secret')

    def test_cli_invalid_manifest_fails_before_any_docker_calls_or_traceback(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); stage = root / 'stage'; stage.mkdir(); write_stage(stage)
            (stage / m.NAME).write_text('{"bad":"no secret logging"}')
            binary = root / 'bin'; binary.mkdir(); fake = binary / 'docker'
            fake.write_text('#!/bin/sh\necho called > "' + str(root / 'called') + '"\nexit 99\n'); fake.chmod(0o700)
            result = subprocess.run([sys.executable, str(OPS / 'minio_metadata.py'), 'restore', '--stage', str(stage),
                                     '--container', 'epharm-minio', '--target-id', 'b' * 64],
                                    env={**os.environ, 'PATH': str(binary) + ':' + os.environ['PATH']}, capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0); self.assertFalse((root / 'called').exists())
            self.assertNotIn('Traceback', result.stderr); self.assertNotIn('no secret logging', result.stderr)


if __name__ == '__main__': unittest.main()
