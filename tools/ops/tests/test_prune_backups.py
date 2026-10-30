import contextlib
import gzip
import hashlib
import importlib.util
import io
import os
import sys
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'tools/ops'))
spec = importlib.util.spec_from_file_location('prune_backups', ROOT / 'tools/ops/prune-backups.py')
retention = importlib.util.module_from_spec(spec)
spec.loader.exec_module(retention)


class BackupRetentionTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(dir=Path(tempfile.gettempdir()).resolve())
        self.root = Path(self.temporary.name)
        self.postgres = self.root / 'postgres'
        self.minio = self.root / 'minio'
        self.postgres.mkdir(mode=0o700)
        self.minio.mkdir(mode=0o700)
        self.addCleanup(self.temporary.cleanup)

    def backup(self, number, kind='postgres', payload=None):
        directory = self.postgres if kind == 'postgres' else self.minio
        extension = 'dump.gz' if kind == 'postgres' else 'tar.gz'
        name = f'epharm-{kind}-202610{number:02d}T010203Z.{extension}'
        target = directory / name
        if kind == 'postgres':
            target.write_bytes(gzip.compress(payload if payload is not None else b'PGDMP fixture dump'))
        else:
            with tarfile.open(target, 'w:gz') as archive:
                manifest = tarfile.TarInfo('manifest.txt')
                body = b'created_at=2026-10-16T01:02:03Z\nbuckets=epharm-receipts\n'
                manifest.size = len(body)
                archive.addfile(manifest, io.BytesIO(body))
                for name in ('data', 'data/epharm-receipts'):
                    member = tarfile.TarInfo(name)
                    member.type = tarfile.DIRTYPE
                    archive.addfile(member)
                member = tarfile.TarInfo('data/epharm-receipts/test.pdf')
                member.size = 7
                archive.addfile(member, io.BytesIO(b'fixture'))
        self.checksum(target)
        return target

    def checksum(self, target, *, name=None):
        digest = hashlib.sha256(target.read_bytes()).hexdigest()
        Path(str(target) + '.sha256').write_text(f'{digest}  {name or target.name}\n')

    def snapshot(self, directory):
        return {path.name: path.read_bytes() for path in directory.iterdir() if path.is_file()}

    def prune(self, directory=None, kind='postgres', keep=2, apply=False):
        with contextlib.redirect_stdout(io.StringIO()):
            return retention.prune_backups(kind, directory or self.postgres, keep, apply)

    def test_dry_run_is_default_and_never_changes_files(self):
        for number in range(1, 6):
            self.backup(number)
        before = self.snapshot(self.postgres)
        with contextlib.redirect_stdout(io.StringIO()) as output:
            result = retention.main(['--kind', 'postgres', '--directory', str(self.postgres), '--keep', '2'])
        self.assertEqual(result, 0)
        self.assertIn('would remove pairs=3', output.getvalue())
        self.assertEqual(before, self.snapshot(self.postgres))

    def test_apply_keeps_newest_count_and_removes_sidecars_idempotently(self):
        backups = [self.backup(number) for number in range(1, 6)]
        keepers, removed, skipped = self.prune(apply=True)
        self.assertEqual(keepers, [backups[4].name, backups[3].name])
        self.assertEqual(set(removed), {path.name for path in backups[:3]})
        self.assertEqual(skipped, [])
        for path in backups:
            self.assertEqual(path.exists(), path in backups[3:])
            self.assertEqual(Path(str(path) + '.sha256').exists(), path in backups[3:])
        self.assertEqual(self.prune(apply=True)[1], [])

    def test_default_keep_is_seven_and_fewer_backups_are_never_removed(self):
        for number in range(1, 10):
            self.backup(number)
        with contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(retention.main(['--kind', 'postgres', '--directory', str(self.postgres), '--apply']), 0)
        self.assertIn('verified keepers=7', output.getvalue())
        self.assertEqual(len(list(self.postgres.glob('*.dump.gz'))), 7)
        self.backup(1, kind='minio')
        self.assertEqual(self.prune(self.minio, 'minio', apply=True)[1], [])

    def test_corrupt_newest_or_second_keeper_prevents_every_deletion(self):
        backups = [self.backup(number) for number in range(1, 5)]
        for index in (3, 2):
            original = backups[index].read_bytes()
            backups[index].write_bytes(original[:-4])
            self.checksum(backups[index])
            before = self.snapshot(self.postgres)
            with self.assertRaises((OSError, EOFError)):
                self.prune(apply=True)
            self.assertEqual(before, self.snapshot(self.postgres))
            backups[index].write_bytes(original)
            self.checksum(backups[index])

    def test_checksum_mismatch_wrong_basename_and_missing_newest_sidecar_block(self):
        backups = [self.backup(number) for number in range(1, 4)]
        newest = backups[-1]
        sidecar = Path(str(newest) + '.sha256')
        for body in ('0' * 64 + '  ' + newest.name + '\n',
                     'a' * 64 + '  ../another.dump.gz\n', 'invalid checksum\n', None):
            if body is None:
                sidecar.unlink()
            else:
                sidecar.write_text(body)
            before = self.snapshot(self.postgres)
            with self.assertRaises(ValueError):
                self.prune(apply=True)
            self.assertEqual(before, self.snapshot(self.postgres))

    def test_valid_gzip_that_is_not_postgres_dump_blocks_deletion(self):
        self.backup(1)
        self.backup(2)
        self.backup(3, payload=b'not a PostgreSQL custom dump')
        before = self.snapshot(self.postgres)
        with self.assertRaisesRegex(ValueError, 'custom dump'):
            self.prune(apply=True)
        self.assertEqual(before, self.snapshot(self.postgres))

    def test_partial_unknown_directories_and_orphan_files_are_untouched(self):
        older = self.backup(1)
        Path(str(older) + '.sha256').unlink()
        self.backup(2)
        self.backup(3)
        untouched = ('other.dump.gz', 'epharm-postgres-20261004T010203Z.dump.gz.partial',
                     'epharm-postgres-20261005T010203Z.dump.gz.sha256', 'notes.txt')
        for name in untouched:
            (self.postgres / name).write_bytes(b'leave untouched')
        matched_directory = self.postgres / 'epharm-postgres-20261006T010203Z.dump.gz'
        matched_directory.mkdir()
        result = self.prune(apply=True)
        self.assertEqual(result[1], [])
        self.assertEqual(result[2], [older.name])
        for name in untouched:
            self.assertEqual((self.postgres / name).read_bytes(), b'leave untouched')
        self.assertTrue(matched_directory.is_dir())
        self.assertTrue(older.is_file())

    def test_relative_broad_wrong_leaf_and_home_directories_rejected(self):
        for directory in (Path('/'), Path.home(), Path('postgres'), self.root, Path('/postgres')):
            with self.subTest(directory=directory), self.assertRaises((ValueError, OSError)):
                self.prune(directory, apply=True)
        with patch.object(retention.Path, 'home', return_value=self.postgres):
            with self.assertRaises(ValueError):
                self.prune(apply=True)

    def test_directory_requires_private_mode_and_effective_owner(self):
        self.backup(1)
        before = self.snapshot(self.postgres)
        for mode in (0o755, 0o777, 0o750):
            self.postgres.chmod(mode)
            with self.subTest(mode=mode), self.assertRaisesRegex(ValueError, 'mode 0700'):
                self.prune(apply=True)
            self.assertEqual(before, self.snapshot(self.postgres))
        self.postgres.chmod(0o700)
        real_stat = Path.stat

        def wrong_owner(path, *args, **kwargs):
            info = real_stat(path, *args, **kwargs)
            if path == self.postgres:
                fields = list(info)
                fields[4] = os.geteuid() + 1
                return os.stat_result(fields)
            return info

        with patch.object(Path, 'stat', autospec=True, side_effect=wrong_owner):
            with self.assertRaisesRegex(ValueError, 'owned by the effective user'):
                self.prune(apply=True)
        self.assertEqual(before, self.snapshot(self.postgres))

    def test_directory_and_matching_archive_or_sidecar_symlinks_rejected(self):
        actual = self.root / 'actual'
        actual.mkdir()
        actual_postgres = actual / 'postgres'
        actual_postgres.mkdir(mode=0o700)
        linked_parent = self.root / 'linked'
        linked_parent.symlink_to(actual, target_is_directory=True)
        with self.assertRaises(ValueError):
            self.prune(linked_parent / 'postgres', apply=True)
        target = self.backup(3)
        for path in (target, Path(str(target) + '.sha256')):
            content = path.read_bytes()
            path.unlink()
            outside = self.root / 'outside'
            outside.write_bytes(content)
            path.symlink_to(outside)
            with self.assertRaisesRegex(ValueError, 'symlink'):
                self.prune(apply=True)
            self.assertEqual(outside.read_bytes(), content)
            path.unlink()
            path.write_bytes(content)

    def test_invalid_keep_and_invalid_timestamp_fail_without_deletion(self):
        for value in (0, 1, -1, True, '2'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                self.prune(keep=value, apply=True)
        (self.postgres / 'epharm-postgres-20261399T010203Z.dump.gz').write_bytes(b'bad timestamp')
        with self.assertRaises(ValueError):
            self.prune(apply=True)

    def test_valid_minio_tar_retention_and_unsafe_tar_rejection(self):
        backups = [self.backup(number, 'minio') for number in range(1, 4)]
        newest = backups[-1]
        for bad_name, bad_type in (('../outside', tarfile.REGTYPE),
                                   ('data/link', tarfile.SYMTYPE),
                                   ('other-root', tarfile.REGTYPE)):
            with tarfile.open(newest, 'w:gz') as archive:
                member = tarfile.TarInfo(bad_name)
                member.type = bad_type
                archive.addfile(member)
            self.checksum(newest)
            before = self.snapshot(self.minio)
            with self.assertRaises(ValueError):
                self.prune(self.minio, 'minio', apply=True)
            self.assertEqual(before, self.snapshot(self.minio))
        self.backup(3, 'minio')
        self.assertEqual(self.prune(self.minio, 'minio', apply=True)[1], [backups[0].name])

    def test_minio_checks_full_gzip_trailer_after_tar_end_marker(self):
        backups = [self.backup(number, 'minio') for number in range(1, 4)]
        newest = backups[-1]
        newest.write_bytes(newest.read_bytes()[:-4])
        self.checksum(newest)
        before = self.snapshot(self.minio)
        with self.assertRaises((OSError, EOFError)):
            self.prune(self.minio, 'minio', apply=True)
        self.assertEqual(before, self.snapshot(self.minio))

    def test_older_invalid_sidecar_is_preserved_while_valid_old_pairs_are_removed(self):
        backups = [self.backup(number) for number in range(1, 6)]
        Path(str(backups[0]) + '.sha256').write_text('unrelated metadata\n')
        _, removed, skipped = self.prune(apply=True)
        self.assertEqual(set(removed), {backups[1].name, backups[2].name})
        self.assertEqual(skipped, [backups[0].name])
        self.assertTrue(backups[0].is_file())
        self.assertEqual(Path(str(backups[0]) + '.sha256').read_text(), 'unrelated metadata\n')

    def test_keeper_changed_during_validation_prevents_every_deletion(self):
        backups = [self.backup(number) for number in range(1, 5)]
        original_validator = retention.validate_archive
        count = 0

        def mutate_after_validation(*args):
            nonlocal count
            original_validator(*args)
            count += 1
            if count == 2:
                backups[-1].write_bytes(gzip.compress(b'PGDMP changed'))

        with patch.object(retention, 'validate_archive', side_effect=mutate_after_validation):
            with self.assertRaisesRegex(ValueError, 'set changed'):
                self.prune(apply=True)
        for path in backups:
            self.assertTrue(path.is_file())
            self.assertTrue(Path(str(path) + '.sha256').is_file())

    def test_directory_replaced_during_validation_prevents_deletion(self):
        backups = [self.backup(number) for number in range(1, 4)]
        original_validator = retention.validate_archive
        moved = self.root / 'old-postgres'

        def replace_directory(*args):
            original_validator(*args)
            if not moved.exists():
                self.postgres.rename(moved)
                self.postgres.mkdir(mode=0o700)

        with patch.object(retention, 'validate_archive', side_effect=replace_directory):
            with self.assertRaisesRegex(ValueError, 'directory changed'):
                self.prune(apply=True)
        self.assertEqual(len(list(moved.glob('*.dump.gz'))), len(backups))

    def test_keeper_replaced_during_deletion_stops_further_unlinks(self):
        backups = [self.backup(number) for number in range(1, 5)]
        original_unlink = retention.os.unlink
        deleted = []

        def replace_after_first_unlink(name, **kwargs):
            original_unlink(name, **kwargs)
            deleted.append(name)
            replacement = self.root / 'replacement'
            replacement.write_bytes(backups[-1].read_bytes())
            replacement.replace(backups[-1])

        with patch.object(retention.os, 'unlink', side_effect=replace_after_first_unlink):
            with self.assertRaisesRegex(ValueError, 'Backup changed'):
                self.prune(apply=True)
        self.assertEqual(deleted, [backups[1].name])
        self.assertTrue(Path(str(backups[1]) + '.sha256').is_file())
        self.assertTrue(backups[0].is_file())
        self.assertTrue(backups[-1].is_file())


if __name__ == '__main__':
    unittest.main()
