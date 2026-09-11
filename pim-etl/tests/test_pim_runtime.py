import os
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import pim_runtime as runtime


class RuntimeTests(unittest.TestCase):
    def test_clickhouse_secret_is_not_in_process_arguments(self):
        with patch.dict(os.environ, {'CLICKHOUSE_USER': 'ci-user', 'CLICKHOUSE_PASSWORD': 'never-in-argv'}):
            command=runtime.client_command('SELECT 1')
        self.assertNotIn('ci-user',command)
        self.assertNotIn('never-in-argv',command)
        self.assertIn('CLICKHOUSE_USER',command)
        self.assertIn('CLICKHOUSE_PASSWORD',command)

    def test_clickhouse_credentials_are_required_at_execution(self):
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaises(RuntimeError):runtime.client_command('SELECT 1')

    def test_invalid_source_name_is_refused(self):
        with self.assertRaises(RuntimeError):runtime._validate_source_name('../2026-09-06.csv')
        with self.assertRaises(RuntimeError):runtime._validate_source_name('2026-02-30.csv')

    def test_remote_listing_requires_private_credentials(self):
        with tempfile.TemporaryDirectory() as temp:
            credentials=Path(temp)/'smb-credentials';credentials.write_text('username=x\n');credentials.chmod(0o644)
            with patch.object(runtime,'SMB_CREDENTIALS',credentials):
                with self.assertRaises(RuntimeError):runtime.remote_files()

    def test_remote_listing_parses_only_bounded_daily_files(self):
        with tempfile.TemporaryDirectory() as temp:
            credentials=Path(temp)/'smb-credentials';credentials.write_text('username=x\n');credentials.chmod(0o600)
            listing='  2026-09-06.csv A 123456 Mon Sep 7\n  unrelated.txt A 20 Mon Sep 7\n'
            result=SimpleNamespace(stdout=listing)
            with patch.object(runtime,'SMB_CREDENTIALS',credentials), patch.object(runtime.subprocess,'run',return_value=result) as run:
                self.assertEqual(runtime.remote_files(),{'2026-09-06.csv':123456})
                self.assertEqual(run.call_args.kwargs['timeout'],120)

    def test_smb_command_separator_is_refused(self):
        with tempfile.TemporaryDirectory() as temp:
            credentials=Path(temp)/'smb-credentials';credentials.write_text('username=x\n');credentials.chmod(0o600)
            with patch.object(runtime,'SMB_CREDENTIALS',credentials), patch.object(runtime,'SMB_DIR','safe; quit'):
                with self.assertRaises(RuntimeError):runtime.remote_files()

    def test_selection_validates_limit_and_dates(self):
        files={'2026-09-05.csv':1,'2026-09-06.csv':1}
        args=SimpleNamespace(file=None,from_date='2026-09-06',to_date=None,repair_rejects=False,latest=1)
        self.assertEqual(runtime.select_files(files,args),['2026-09-06.csv'])
        args.latest=0
        with self.assertRaises(RuntimeError):runtime.select_files(files,args)
        args.latest=1;args.from_date='2026-02-30'
        with self.assertRaises(RuntimeError):runtime.select_files(files,args)


if __name__=='__main__':unittest.main()
