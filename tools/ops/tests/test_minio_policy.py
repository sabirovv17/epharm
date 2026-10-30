import importlib.util
import io
import json
from pathlib import Path
import sys
import tarfile
import tempfile
import unittest

OPS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(OPS))
from minio_policy import policy_value, validate_stage

SPEC = importlib.util.spec_from_file_location('minio_retention', OPS / 'prune-backups.py')
retention = importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(retention)
POLICY = {'Version': '2012-10-17', 'Statement': [{'Effect': 'Allow', 'Principal': {'AWS': ['*']},
          'Action': ['s3:GetObject'], 'Resource': ['arn:aws:s3:::public/allowed/*']}]}


class MinioPolicyTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(); self.root = Path(self.temporary.name)
        self.stage = self.root / 'stage'; self.stage.mkdir()
        (self.stage / 'policies').mkdir(); self.actual = self.root / 'actual'; self.actual.mkdir()
        (self.stage / 'manifest.txt').write_text('buckets=private,public\nminio_policy_format=1\n')
        for name, policy in [('private', {}), ('public', POLICY)]:
            for base in [self.stage / 'policies', self.actual]:
                (base / (name + '.json')).write_text(json.dumps(policy))

    def tearDown(self): self.temporary.cleanup()

    def test_exact_custom_and_private_policy_match_without_added_grants(self):
        self.assertEqual(validate_stage(self.stage, self.actual)['policies'], 2)
        self.assertTrue(validate_stage(self.stage, self.actual)['exactRestoredMatch'])
        (self.actual / 'private.json').write_text(json.dumps(POLICY))
        with self.assertRaises(ValueError): validate_stage(self.stage, self.actual)

    def test_changed_prefix_or_action_is_not_accepted_as_equivalent(self):
        changed = json.loads(json.dumps(POLICY)); changed['Statement'][0]['Resource'] = ['arn:aws:s3:::public/*']
        (self.actual / 'public.json').write_text(json.dumps(changed))
        with self.assertRaises(ValueError): validate_stage(self.stage, self.actual)

    def test_missing_extra_or_symlink_policy_fails_closed(self):
        path = self.stage / 'policies/private.json'; path.unlink()
        with self.assertRaises(ValueError): validate_stage(self.stage)
        path.symlink_to(self.actual / 'private.json')
        with self.assertRaises(ValueError): validate_stage(self.stage)
        path.unlink(); path.write_text('{}'); (self.actual / 'extra.json').write_text('{}')
        with self.assertRaises(ValueError): validate_stage(self.stage, self.actual)

    def test_legacy_without_policies_requires_explicit_compatibility(self):
        (self.stage / 'manifest.txt').write_text('created_at=fixture\nbuckets=private,public\n')
        with self.assertRaises(ValueError): validate_stage(self.stage)
        self.assertEqual(validate_stage(self.stage, allow_legacy=True)['policyFormat'], 'legacy_not_captured')

    def test_malformed_duplicate_or_nonfinite_json_refused(self):
        for body in [b'[]', b'null', b'{"Statement":[],"Version":"x","Version":"y"}',
                     b'{"Statement":[],"Version":NaN}', b'{"foo":"bar"}', b' ' * (1024 * 1024 + 1)]:
            with self.subTest(body=body[:70]), self.assertRaises(ValueError): policy_value(body)

    def archive(self, missing=False, traversal=False):
        output = io.BytesIO()
        with tarfile.open(fileobj=output, mode='w') as tar:
            for name in ['data', 'data/private', 'data/public', 'policies']:
                entry = tarfile.TarInfo(name); entry.type = tarfile.DIRTYPE; tar.addfile(entry)
            rows = [('manifest.txt', b'buckets=private,public\nminio_policy_format=1\n'),
                    ('policies/private.json', b'{}')]
            if not missing: rows.append(('policies/public.json', json.dumps(POLICY).encode()))
            if traversal: rows.append(('policies/../../outside', b'{}'))
            for name, data in rows:
                entry = tarfile.TarInfo(name); entry.size = len(data); tar.addfile(entry, io.BytesIO(data))
        output.seek(0); return output

    def test_archive_policy_inventory_and_path_safety_before_extraction(self):
        retention.validate_minio(self.archive())
        for bad in [self.archive(missing=True), self.archive(traversal=True)]:
            with self.assertRaises(ValueError): retention.validate_minio(bad)


if __name__ == '__main__': unittest.main()
