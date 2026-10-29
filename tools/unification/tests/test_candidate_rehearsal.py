import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
from types import SimpleNamespace
import sys
import tempfile
import unittest
from unittest import mock


TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
SPEC = importlib.util.spec_from_file_location("candidate_rehearsal", TOOLS / "candidate_rehearsal.py")
candidate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(candidate)


class CandidateRehearsalTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.root.chmod(0o700)

    def test_private_new_location_is_required_without_reusing_existing_paths(self):
        path = candidate.location(self.root, "candidate-test", "epharm-unify-candidate-test", "epharm_test_candidate")
        self.assertEqual(self.root / "candidate-test", path)
        path.mkdir()
        marker = path / "keep.txt"
        marker.write_text("owned-by-someone")
        with self.assertRaisesRegex(candidate.stage.StageError, "already exists"):
            candidate.location(self.root, "candidate-test", "epharm-unify-candidate-test", "epharm_test_candidate")
        self.assertTrue(marker.exists())

    def test_unknown_names_and_path_traversal_fail_before_mutation(self):
        for run, container, database in [("../other", "epharm-unify-candidate-test", "epharm_test_candidate"),
                                         ("candidate-test", "production", "epharm_test_candidate"),
                                         ("candidate-test", "epharm-unify-candidate-test", "epharm")]:
            with self.assertRaises(candidate.stage.StageError):
                candidate.location(self.root, run, container, database)
        self.assertEqual([], list(self.root.iterdir()))

    def test_symlink_run_path_and_open_private_parent_fail(self):
        (self.root / "candidate-test").symlink_to(self.root / "missing")
        with self.assertRaises(candidate.stage.StageError):
            candidate.location(self.root, "candidate-test", "epharm-unify-candidate-test", "epharm_test_candidate")
        self.root.chmod(0o755)
        with self.assertRaisesRegex(candidate.stage.StageError, "private parent"):
            candidate.location(self.root, "candidate-other", "epharm-unify-candidate-test", "epharm_test_candidate")

    def test_capacity_requires_three_copies_plus_one_gib(self):
        source = 1790172183
        required = 3 * source + candidate.stage.MIN_HEADROOM
        stat = SimpleNamespace(f_bavail=required, f_frsize=1)
        with mock.patch.object(candidate.os, "statvfs", return_value=stat):
            self.assertEqual((required, required), candidate.host_capacity(self.root, source))
        stat.f_bavail -= 1
        with mock.patch.object(candidate.os, "statvfs", return_value=stat):
            with self.assertRaisesRegex(candidate.stage.StageError, "capacity guard failed"):
                candidate.host_capacity(self.root, source)

    def test_only_same_major_custom_archive_is_accepted(self):
        toc = '; Format: CUSTOM\n; Dumped from database version: 16.14\n; Dumped by pg_dump version: 16.14\n1; 0 1 TABLE DATA public synthetic user\n'
        self.assertEqual(1, candidate.parse_archive_toc(toc)["tableDataEntries"])
        for bad in (toc.replace('16.14', '18.6'), toc.replace('CUSTOM', 'PLAIN'), toc.replace('Dumped by pg_dump version: 16', 'Dumped by pg_dump version: 18')):
            with self.assertRaises(candidate.stage.StageError):
                candidate.parse_archive_toc(bad)

    def test_random_password_is_private_and_never_a_command_argument(self):
        env = self.root / "postgres.env"
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            candidate.environment(env, "synthetic", "epharm_test_candidate")
        self.assertEqual('', out.getvalue())
        self.assertEqual(0o600, env.stat().st_mode & 0o777)
        values = dict(line.split('=', 1) for line in env.read_text().splitlines())
        self.assertGreaterEqual(len(values['POSTGRES_PASSWORD']), 48)
        command = candidate.run_command('sha256:' + 'a' * 64, 'epharm-unify-candidate-test', env, self.root / 'pgdata')
        self.assertIn('--pull=never', command)
        self.assertIn('--network=none', command)
        self.assertIn('--mount', command)
        self.assertNotIn('-p', command)
        self.assertNotIn('--rm', command)
        self.assertNotIn(values['POSTGRES_PASSWORD'], ' '.join(command))

    def test_full_original_column_and_primary_key_checksums_use_readonly_transaction(self):
        metadata = {"tables": [{"name": "source_table", "columns": [{"name": "id"}, {"name": "password_hash"}],
                                "constraints": [{"kind": "p", "columns": ["id"]}]}]}
        db = mock.Mock()
        db.readonly.return_value = json.dumps({"table": "source_table", "rowCount": 1, "rowSha256": "a" * 64, "primaryKeySha256": "b" * 64})
        facts = candidate.original_facts(db, metadata)
        self.assertEqual(1, facts[0]['rowCount'])
        sql = db.readonly.call_args.args[0]
        self.assertIn('"password_hash"', sql)
        self.assertIn("rowSha256", sql)
        self.assertIn("primaryKeySha256", sql)
        self.assertNotIn("INSERT", sql)
        db.run.assert_not_called()

    def test_existing_container_refuses_before_backup_read_or_directory_creation(self):
        args = SimpleNamespace(private_root=self.root, run_name='candidate-test', container='epharm-unify-candidate-test',
                               database='epharm_test_candidate', user='synthetic', image_id='sha256:'+'a'*64,
                               report=self.root/'report.json')
        with mock.patch.object(candidate, "checked", side_effect=[json.dumps(args.image_id), args.container]):
            with self.assertRaisesRegex(candidate.stage.StageError, "already exists"):
                candidate.create(args)
        self.assertEqual([], list(self.root.iterdir()))

    def test_restore_is_single_transaction_and_errors_stay_in_private_log(self):
        producer = mock.Mock()
        producer.wait.return_value = 0
        producer.poll.return_value = 0
        consumer = mock.Mock()
        consumer.wait.return_value = 0
        log = self.root / 'restore.log'
        with mock.patch.object(candidate.subprocess, "Popen", side_effect=[producer, consumer]) as popen:
            candidate.restore_backup('epharm-unify-candidate-test', 'synthetic', 'epharm_test_candidate', self.root/'source.gz', log)
        self.assertEqual(0o600, log.stat().st_mode & 0o777)
        command = popen.call_args_list[1].args[0]
        for flag in ('--single-transaction', '--exit-on-error', '--no-owner', '--no-privileges'):
            self.assertIn(flag, command)
        self.assertFalse(any('password' in arg.lower() for arg in command))


if __name__ == "__main__":
    unittest.main()
