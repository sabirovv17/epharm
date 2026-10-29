import hashlib
from datetime import datetime, timezone
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch

OPS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(OPS))
import private_backup as snapshot

SPEC = importlib.util.spec_from_file_location('retention', OPS / 'prune-backups.py')
retention = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(retention)


class PrivateBackupTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name).resolve()
        self.root.chmod(0o700)
        self.backups = self.root / 'backups'; self.backups.mkdir(mode=0o700)
        self.sources = {}
        for key in ('merch', 'merch-legacy', 'learner-credentials'):
            value = self.root / key; value.mkdir(mode=0o700); self.sources[key] = value
        self.write(self.sources['merch'] / 'photo.bin', b'private-file-fixture')
        self.write(self.sources['merch-legacy'] / 'proof.pdf', b'preserved-source-fixture')
        self.write(self.sources['learner-credentials'] / 'keys.json', b'{"synthetic":"never-print-key-fixture"}')

    def tearDown(self):
        self.temporary.cleanup()

    def write(self, path, body):
        path.write_bytes(body); path.chmod(0o600)

    def create(self, kind='native-files'):
        return snapshot.create(self.backups / kind, kind, {k: self.sources[k] for k in snapshot.ROOTS[kind]})

    def mutate_archive(self, path, mutate):
        with tarfile.open(path, 'r:gz') as source:
            entries = [(member, source.extractfile(member).read() if member.isfile() else None) for member in source]
        with tarfile.open(path, 'w:gz') as output:
            for member, body in mutate(entries):
                output.addfile(member, io.BytesIO(body) if body is not None else None)
        path.chmod(0o600)
        self.write(Path(str(path) + '.sha256'), f'{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n'.encode())

    def test_separate_artifacts_restore_exact_private_files_without_source_mutation(self):
        original = {k: [(str(p.relative_to(v)), p.read_bytes()) for p in v.rglob('*') if p.is_file()] for k, v in self.sources.items()}
        native, keys = self.create(), self.create('credential-keys')
        self.assertEqual(native['files'], 2)
        self.assertEqual(keys['files'], 1)
        with tarfile.open(native['archive'], 'r:gz') as archive:
            self.assertFalse(any('learner-credentials' in name for name in archive.getnames()))
        stage = self.root / 'restore-native'
        snapshot.verify(Path(native['archive']), 'native-files', stage)
        snapshot.verify(Path(keys['archive']), 'credential-keys', self.root / 'restore-keys')
        for key in ('merch', 'merch-legacy'):
            for relative, body in original[key]:
                value = stage / key / relative
                self.assertEqual(value.read_bytes(), body)
                self.assertEqual(stat.S_IMODE(value.stat().st_mode), 0o600)
        self.assertEqual((self.root / 'restore-keys/learner-credentials/keys.json').read_bytes(), original['learner-credentials'][0][1])
        for key, root in self.sources.items():
            self.assertEqual([(str(p.relative_to(root)), p.read_bytes()) for p in root.rglob('*') if p.is_file()], original[key])
        for artifact in (native, keys):
            self.assertEqual(stat.S_IMODE(Path(artifact['archive']).stat().st_mode), 0o600)

    def test_sources_symlinks_hardlinks_unsafe_permissions_and_missing_key_refused(self):
        link = self.sources['merch'] / 'link'; link.symlink_to(self.sources['merch-legacy'] / 'proof.pdf')
        with self.assertRaises((ValueError, OSError)): self.create()
        link.unlink(); os.link(self.sources['merch-legacy'] / 'proof.pdf', link)
        with self.assertRaises(ValueError): self.create()
        link.unlink(); self.sources['merch'].chmod(0o755)
        with self.assertRaises(ValueError): self.create()
        self.sources['merch'].chmod(0o700)
        (self.sources['learner-credentials'] / 'keys.json').unlink()
        with self.assertRaises(ValueError): self.create('credential-keys')

    def test_capacity_refusal_and_collision_preserve_previous_copies(self):
        archive = Path(self.create()['archive']); before = archive.read_bytes()
        with patch.object(snapshot.shutil, 'disk_usage', return_value=type('Disk', (), {'free': 1})()):
            with self.assertRaises(ValueError): self.create()
        with self.assertRaises(FileExistsError): self.create()
        self.assertEqual(before, archive.read_bytes())
        self.assertFalse(list(archive.parent.glob('*.partial')))

    def test_preexisting_or_replaced_partial_is_not_removed_by_failure_cleanup(self):
        directory = self.backups / 'native-files'; directory.mkdir(mode=0o700)
        partial = directory / 'epharm-native-files-20261030T000000Z.tar.gz.partial'
        with patch.object(snapshot, 'datetime') as clock:
            clock.now.return_value = datetime(2026, 10, 30, tzinfo=timezone.utc)
            self.write(partial, b'previous-attempt-preserved')
            with self.assertRaises(FileExistsError): self.create()
            self.assertEqual(partial.read_bytes(), b'previous-attempt-preserved')
            partial.unlink()
            def replace_then_fail(*args, **kwargs):
                partial.unlink(); self.write(partial, b'concurrent-replacement-preserved')
                raise ValueError('fixture archive writer failure')
            with patch.object(snapshot.tarfile, 'open', side_effect=replace_then_fail):
                with self.assertRaises(ValueError): self.create()
            self.assertEqual(partial.read_bytes(), b'concurrent-replacement-preserved')

    def test_corruption_or_missing_sidecar_never_restores(self):
        archive = Path(self.create()['archive'])
        sidecar = Path(str(archive) + '.sha256'); original = sidecar.read_bytes(); sidecar.unlink()
        with self.assertRaises(OSError): snapshot.verify(archive, 'native-files', self.root / 'restore')
        self.write(sidecar, original)
        archive.write_bytes(archive.read_bytes()[:-8])
        with self.assertRaises(ValueError): snapshot.verify(archive, 'native-files', self.root / 'restore')
        self.assertFalse((self.root / 'restore').exists())

    def test_checksum_valid_tar_traversal_symlink_missing_file_and_bad_inner_hash_refused(self):
        for alteration in ('traversal', 'symlink', 'missing', 'hash'):
            with self.subTest(alteration=alteration):
                archive = Path(self.create()['archive'])
                def mutate(entries):
                    if alteration == 'missing': return entries[:-1]
                    if alteration == 'hash':
                        member, data = entries[-1]; data = bytes([data[0] ^ 1]) + data[1:]
                        return entries[:-1] + [(member, data)]
                    member = tarfile.TarInfo('../outside' if alteration == 'traversal' else 'merch/evil')
                    member.type = tarfile.SYMTYPE if alteration == 'symlink' else tarfile.REGTYPE
                    member.linkname = '/etc/passwd'; member.size = 0
                    return entries + [(member, None)]
                self.mutate_archive(archive, mutate)
                with self.assertRaises(ValueError): snapshot.verify(archive, 'native-files', self.root / 'restore')
                archive.unlink(); Path(str(archive) + '.sha256').unlink()
                self.assertFalse((self.root / 'restore').exists())

    def test_existing_restore_directory_never_overwritten(self):
        archive = Path(self.create()['archive'])
        target = self.root / 'restore'; target.mkdir(mode=0o700)
        marker = target / 'keep'; self.write(marker, b'untouched')
        with self.assertRaises(ValueError): snapshot.verify(archive, 'native-files', target)
        self.assertEqual(marker.read_bytes(), b'untouched')

    def test_native_rotation_reuses_bounded_count_and_preserves_key_history_unknown_files(self):
        original = Path(self.create()['archive']); key_archive = Path(self.create('credential-keys')['archive'])
        archives = []
        for day in range(1, 6):
            archive = original.parent / f'epharm-native-files-202610{day:02}T010000Z.tar.gz'
            self.write(archive, original.read_bytes())
            self.write(Path(str(archive) + '.sha256'), f'{hashlib.sha256(archive.read_bytes()).hexdigest()}  {archive.name}\n'.encode())
            archives.append(archive)
        original.unlink(); Path(str(original) + '.sha256').unlink()
        unknown = archives[0].parent / 'operator-notes'; self.write(unknown, b'preserve')
        keepers, removable, skipped = retention.prune_backups('native-files', archives[0].parent, 2)
        self.assertEqual(len(keepers), 2); self.assertEqual(len(removable), 3); self.assertEqual(skipped, [])
        self.assertTrue(all(p.exists() for p in archives))
        retention.prune_backups('native-files', archives[0].parent, 2, True)
        self.assertEqual([p.exists() for p in archives], [False, False, False, True, True])
        self.assertTrue(key_archive.exists()); self.assertTrue(unknown.exists())

    def test_invalid_new_keeper_prevents_all_rotation(self):
        original = Path(self.create()['archive'])
        self.write(original, b'not gzip')
        self.write(Path(str(original) + '.sha256'), f'{hashlib.sha256(original.read_bytes()).hexdigest()}  {original.name}\n'.encode())
        with self.assertRaises((ValueError, tarfile.TarError, OSError)):
            retention.prune_backups('native-files', original.parent, 2, True)
        self.assertTrue(original.exists())


if __name__ == '__main__': unittest.main()
