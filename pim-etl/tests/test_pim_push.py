import json
import os
import tempfile
import unittest
import tarfile
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
import pim_medusa_push as push
from pim_validation import sha256


class PushTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name);self.artifact=self.root/'snapshot';self.artifact.mkdir()
        self.oldroot=push.ARTIFACT_ROOT;push.ARTIFACT_ROOT=self.root
        self.environment=patch.dict(os.environ,{'PIM_MEDUSA_ALLOWED_HOST':'receiver.example.invalid','PIM_MEDUSA_ALLOWED_USER':'standardn-ingest','PIM_MEDUSA_ALLOWED_PORT':'22'})
        self.environment.start()
        self.manifest={'snapshot_id':'a'*64,'complete':True,'source_date':'2026-09-06'}
        for key,name in (('products','products.ndjson'),('offers','pharmacy-offers.ndjson'),('pharmacies','pharmacies.ndjson')):
            p=self.artifact/name;p.write_text('{}\n');self.manifest[key]={'file':name,'sha256':sha256(p),'count':1}
        (self.artifact/'catalog-manifest.json').write_text(json.dumps(self.manifest))
        (self.artifact/'publication.json').write_text(json.dumps({'state':'complete','snapshot_date':'2026-09-06'}))
        known_hosts=self.root/'known_hosts';known_hosts.write_text('receiver key\n');known_hosts.chmod(0o644)
        identity=self.root/'identity';identity.write_text('private key\n');identity.chmod(0o600)
        self.config={'host':'receiver.example.invalid','user':'standardn-ingest','port':22,'known_hosts':str(known_hosts),'identity_file':str(identity)}
    def tearDown(self):self.environment.stop();push.ARTIFACT_ROOT=self.oldroot;self.temp.cleanup()
    def ack(self,**changes):
        value={'accepted':True,'snapshot_id':'a'*64,'manifest_sha256':sha256(self.artifact/'catalog-manifest.json')};value.update(changes)
        return SimpleNamespace(returncode=0,stdout=json.dumps(value).encode(),stderr=b'')
    def test_only_four_flat_regular_archive_members(self):
        archive=push.make_archive(self.artifact)
        with tarfile.open(archive) as f:
            self.assertEqual(set(f.getnames()),set(push.FILES));self.assertTrue(all(m.isfile() and '/' not in m.name for m in f.getmembers()))
    def test_tampered_member_refused(self):
        (self.artifact/'products.ndjson').write_text('tampered')
        with self.assertRaises(RuntimeError):push.validate_artifact(self.artifact)
    def test_unpublished_snapshot_refused(self):
        (self.artifact/'publication.json').write_text(json.dumps({'state':'publishing'}))
        with self.assertRaises(RuntimeError):push.validate_artifact(self.artifact)
    def test_date_mismatch_refused(self):
        (self.artifact/'publication.json').write_text(json.dumps({'state':'complete','snapshot_date':'2026-09-05'}))
        with self.assertRaises(RuntimeError):push.validate_artifact(self.artifact)
    def test_strict_host_key_and_no_interactive_auth(self):
        with patch.object(push.subprocess,'run',return_value=self.ack()) as run:
            result=push.deliver(self.artifact,self.config)
            args=run.call_args[0][0]
            self.assertIn('StrictHostKeyChecking=yes',args);self.assertIn('BatchMode=yes',args);self.assertTrue(result['accepted'])
    def test_bad_ack_refused(self):
        with patch.object(push.subprocess,'run',return_value=self.ack(manifest_sha256='b'*64)):
            with self.assertRaises(RuntimeError):push.deliver(self.artifact,self.config)
        self.assertFalse((self.artifact/'delivery.json').exists())
    def test_receiver_failure_not_acknowledged(self):
        with patch.object(push.subprocess,'run',return_value=SimpleNamespace(returncode=1,stderr=b'refused')):
            with self.assertRaises(RuntimeError):push.deliver(self.artifact,self.config)
        self.assertFalse((self.artifact/'delivery.json').exists())
    def test_accepted_snapshot_not_reuploaded(self):
        with patch.object(push.subprocess,'run',return_value=self.ack()):push.deliver(self.artifact,self.config)
        with patch.object(push.subprocess,'run',side_effect=RuntimeError('network must not be called')):
            self.assertTrue(push.deliver(self.artifact,self.config)['already_delivered'])
    def test_other_host_refused(self):
        with self.assertRaises(RuntimeError):push.deliver(self.artifact,{**self.config,'host':'other.invalid'})
    def test_receiver_requires_explicit_host_allowlist(self):
        with patch.dict(os.environ,{},clear=True):
            with self.assertRaises(RuntimeError):push.deliver(self.artifact,self.config)
    def test_group_readable_identity_refused(self):
        Path(self.config['identity_file']).chmod(0o640)
        with self.assertRaises(RuntimeError):push.deliver(self.artifact,self.config)
    def test_symlink_archive_output_refused(self):
        (self.artifact/'medusa-push.tar.gz.partial').symlink_to(self.artifact/'products.ndjson')
        with self.assertRaises(RuntimeError):push.make_archive(self.artifact)


if __name__=='__main__':unittest.main()
