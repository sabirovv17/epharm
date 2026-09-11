import json
import tempfile
import unittest
from datetime import datetime
from pathlib import Path
from load_files import TABLES, publication, recover_interrupted_publication, recover_all_interrupted, cleanup_terminal_tables, exclusive_lock, VALID_WARE_SQL
from pim_validation import ValidationError, durable_json


class FakeDatabase:
    def __init__(self, fail_at=None, fail_after_commit=False):
        self.counts={name:(42 if name=='fact_pharmacy_daily' else 0) for name in TABLES}
        self.counts.update({name+'__stage_x':100 for name in TABLES})
        self.commands=[];self.fail_at=fail_at;self.fail_after_commit=fail_after_commit
    def table(self,name):return name
    def count(self,table,date):return self.counts.get(table,0)
    def query(self,sql):
        self.commands.append(sql);parts=sql.split()
        if sql.startswith('CREATE TABLE'):self.counts[parts[2]]=0;return ''
        if sql.startswith('DROP TABLE IF EXISTS'):self.counts.pop(parts[-1],None);return ''
        if sql.startswith('ALTER TABLE'):
            target=parts[2]
            should_fail=self.fail_at==target and 'REPLACE PARTITION' in sql and '__stage_x' in sql
            if should_fail and not self.fail_after_commit:raise RuntimeError('simulated publication failure')
            if 'FROM' in parts:self.counts[target]=self.counts[parts[-1]]
            elif 'DROP' in parts:self.counts[target]=0
            if should_fail:raise RuntimeError('simulated timeout after commit')
        return ''


class PublicationTests(unittest.TestCase):
    def test_strict_uuid_sql_not_permissive_cast(self):
        self.assertNotIn('toUUIDOrNull',VALID_WARE_SQL);self.assertIn('{8}',VALID_WARE_SQL)
    def test_atomic_json_replaces_whole_document(self):
        with tempfile.TemporaryDirectory() as temp:
            p=Path(temp)/'journal.json';durable_json(p,{'state':'one'});durable_json(p,{'state':'two'})
            self.assertEqual(json.loads(p.read_text())['state'],'two');self.assertFalse(list(Path(temp).glob('*.tmp.*')))
    def test_old_day_recovery_before_new_day(self):
        with tempfile.TemporaryDirectory() as temp:
            artifact=Path(temp)/'2026-09-05-old';artifact.mkdir()
            db=FakeDatabase();db.counts[TABLES[0]]=100;db.counts['old_backup']=42
            durable_json(artifact/'publication.json',{'state':'publishing','snapshot_date':'2026-09-05','replaced':[TABLES[0]],'backups':{TABLES[0]:{'table':'old_backup','rows':42}}})
            recover_all_interrupted(db,Path(temp));self.assertEqual(db.counts[TABLES[0]],42)
    def test_partition_backups_and_publish(self):
        with tempfile.TemporaryDirectory() as temp:
            db=FakeDatabase();tables={name:name+'__stage_x' for name in TABLES}
            result=publication(db,tables,'2026-09-06','test',Path(temp))
            self.assertEqual(result['state'],'complete');self.assertEqual(db.counts['fact_pharmacy_daily__backup_test'],42)
            self.assertTrue(all(db.counts[name]==100 for name in TABLES))
    def test_mid_publication_rollback(self):
        with tempfile.TemporaryDirectory() as temp:
            db=FakeDatabase(fail_at=TABLES[1]);tables={name:name+'__stage_x' for name in TABLES}
            with self.assertRaises(RuntimeError):publication(db,tables,'2026-09-06','test',Path(temp))
            self.assertEqual(db.counts[TABLES[0]],42);self.assertEqual(db.counts[TABLES[1]],0)
            self.assertEqual(json.loads((Path(temp)/'publication.json').read_text())['state'],'rolled_back')
    def test_timeout_after_commit_rollback(self):
        with tempfile.TemporaryDirectory() as temp:
            db=FakeDatabase(fail_at=TABLES[1],fail_after_commit=True);tables={name:name+'__stage_x' for name in TABLES}
            with self.assertRaises(RuntimeError):publication(db,tables,'2026-09-06','test',Path(temp))
            self.assertEqual(db.counts[TABLES[0]],42);self.assertEqual(db.counts[TABLES[1]],0)
    def test_callback_before_first_replace(self):
        with tempfile.TemporaryDirectory() as temp:
            db=FakeDatabase();tables={name:name+'__stage_x' for name in TABLES}
            def callback(backups):
                self.assertEqual(db.counts[TABLES[0]],42)
                self.assertEqual(backups[TABLES[0]]['rows'],42)
                raise RuntimeError('approval stop')
            with self.assertRaises(RuntimeError):publication(db,tables,'2026-09-06','test',Path(temp),callback)
            self.assertEqual(db.counts[TABLES[0]],42)
    def test_recovery_after_hard_interruption(self):
        with tempfile.TemporaryDirectory() as temp:
            db=FakeDatabase();db.counts[TABLES[0]]=100;db.counts['old_backup']=42
            journal={'state':'publishing','snapshot_date':'2026-09-06','replaced':[TABLES[0]],'backups':{TABLES[0]:{'table':'old_backup','rows':42}}}
            (Path(temp)/'publication.json').write_text(json.dumps(journal))
            recover_interrupted_publication(db,Path(temp));self.assertEqual(db.counts[TABLES[0]],42)
    def test_completed_snapshot_not_rolled_back(self):
        with tempfile.TemporaryDirectory() as temp:
            db=FakeDatabase();(Path(temp)/'publication.json').write_text(json.dumps({'state':'complete'}))
            recover_interrupted_publication(db,Path(temp));self.assertEqual(db.commands,[])
    def test_terminal_cleanup_drops_stage_and_retains_recent_backup(self):
        with tempfile.TemporaryDirectory() as temp:
            artifact=Path(temp);db=FakeDatabase();stage=TABLES[0]+'__stage_test';backup=TABLES[0]+'__backup_test';db.counts[stage]=100;db.counts[backup]=42
            durable_json(artifact/'stage.json',{'tables':{TABLES[0]:stage}})
            durable_json(artifact/'publication.json',{'state':'complete','snapshot_date':'2026-09-06','published_at':'2026-09-07T12:00:00+00:00','backups':{TABLES[0]:{'table':backup,'rows':42}}})
            cleanup_terminal_tables(db,artifact,datetime.fromisoformat('2026-09-08T12:00:00+00:00'),72)
            self.assertNotIn(stage,db.counts);self.assertIn(backup,db.counts)
    def test_expired_backup_cleanup_is_idempotent_and_journalled(self):
        with tempfile.TemporaryDirectory() as temp:
            artifact=Path(temp);db=FakeDatabase();stage=TABLES[0]+'__stage_test';backup=TABLES[0]+'__backup_test';db.counts[stage]=100;db.counts[backup]=42
            durable_json(artifact/'stage.json',{'tables':{TABLES[0]:stage}})
            durable_json(artifact/'publication.json',{'state':'complete','snapshot_date':'2026-09-06','published_at':'2026-09-07T12:00:00+00:00','backups':{TABLES[0]:{'table':backup,'rows':42}}})
            clock=datetime.fromisoformat('2026-09-11T12:00:00+00:00')
            cleanup_terminal_tables(db,artifact,clock,72);cleanup_terminal_tables(db,artifact,clock,72)
            self.assertNotIn(backup,db.counts)
            cleanup=json.loads((artifact/'publication.json').read_text())['cleanup']
            self.assertEqual(cleanup['dropped_stages'],[stage]);self.assertEqual(cleanup['dropped_backups'],[backup])
    def test_cleanup_never_accepts_a_live_table_name(self):
        with tempfile.TemporaryDirectory() as temp:
            artifact=Path(temp);db=FakeDatabase()
            durable_json(artifact/'stage.json',{'tables':{TABLES[0]:TABLES[0]}})
            durable_json(artifact/'publication.json',{'state':'complete','snapshot_date':'2026-09-06','published_at':'2026-09-07T12:00:00+00:00','backups':{}})
            with self.assertRaises(ValidationError):cleanup_terminal_tables(db,artifact,datetime.fromisoformat('2026-09-11T12:00:00+00:00'),72)
            self.assertFalse(any(command.startswith('DROP TABLE') for command in db.commands))
    @unittest.skipUnless(__import__('sys').platform!='win32','POSIX lock checked on deployment host')
    def test_overlapping_runs_blocked(self):
        with tempfile.TemporaryDirectory() as temp:
            p=Path(temp)/'etl.lock'
            with exclusive_lock(p):
                with self.assertRaises(RuntimeError):
                    with exclusive_lock(p):pass
            with exclusive_lock(p):pass


if __name__=='__main__':unittest.main()
