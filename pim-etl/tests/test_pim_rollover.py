import json
import hashlib
import tempfile
import unittest
from datetime import datetime
from pathlib import Path
from unittest.mock import Mock,patch
from load_files import refresh_published_catalog


class RolloverTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name);self.folder=self.root/'snapshot';self.folder.mkdir()
        (self.folder/'publication.json').write_text(json.dumps({'state':'complete','snapshot_date':'2026-09-06','published_at':'2026-09-07T13:00:00Z'}))
        (self.folder/'manifest.json').write_text(json.dumps({'snapshot_date':'2026-09-06','rows':100,'source_sha256':'a'*64}))
        members={}
        for group,filename in (('products','products.ndjson'),('offers','pharmacy-offers.ndjson'),('pharmacies','pharmacies.ndjson')):
            data=b'{}\n';(self.folder/filename).write_bytes(data)
            members[group]={'file':filename,'sha256':hashlib.sha256(data).hexdigest()}
        (self.folder/'catalog-manifest.json').write_text(json.dumps({'sellability_date':'2026-09-07',
            'export_policy_version':3,'source_date':'2026-09-06','source_sha256':'a'*64,
            'complete':True,**members}))
        self.db=Mock();self.db.count.return_value=100
    def tearDown(self):self.temp.cleanup()
    def test_same_day_no_export(self):
        with patch('load_files.export_catalog') as export:
            refresh_published_catalog(self.db,self.root,datetime.fromisoformat('2026-09-07T23:50:00+05:00'))
            export.assert_not_called()
    def test_midnight_reprojects_without_reimport(self):
        with patch('load_files.export_catalog') as export:
            refresh_published_catalog(self.db,self.root,datetime.fromisoformat('2026-09-08T00:01:00+05:00'))
            self.assertEqual(export.call_count,1)
            self.assertEqual(export.call_args.args[2]['snapshot_date'],'2026-09-06')
    def test_old_export_policy_reprojects_same_day(self):
        (self.folder/'catalog-manifest.json').write_text(json.dumps({'sellability_date':'2026-09-07','export_policy_version':2}))
        with patch('load_files.export_catalog') as export:
            refresh_published_catalog(self.db,self.root,datetime.fromisoformat('2026-09-07T23:50:00+05:00'))
            export.assert_called_once()
    def test_tampered_export_reprojects_same_day(self):
        (self.folder/'pharmacy-offers.ndjson').write_text('tampered\n')
        with patch('load_files.export_catalog') as export:
            refresh_published_catalog(self.db,self.root,datetime.fromisoformat('2026-09-07T23:50:00+05:00'))
            export.assert_called_once()
    def test_source_older_than48h_fails_closed(self):
        with patch('load_files.export_catalog') as export:
            with self.assertRaises(RuntimeError):refresh_published_catalog(self.db,self.root,datetime.fromisoformat('2026-09-09T00:01:00+05:00'))
            export.assert_not_called()
    def test_changed_published_data_blocks_projection(self):
        self.db.count.return_value=50
        with self.assertRaises(RuntimeError):refresh_published_catalog(self.db,self.root,datetime.fromisoformat('2026-09-08T00:01:00+05:00'))


if __name__=='__main__':unittest.main()
