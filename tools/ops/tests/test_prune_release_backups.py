"""Offline safety contract for the dedicated rollback-bundle retention helper."""

from contextlib import redirect_stdout
import gzip
import hashlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


SOURCE = Path(__file__).resolve().parents[1] / "prune-release-backups.py"
SPEC = importlib.util.spec_from_file_location("prune_release_backups", SOURCE)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class RollbackRetentionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="epharm-release-prune-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.backups = self.root / "epharm-backend-only-backups"
        self.backups.mkdir(mode=0o700)
        self.deploy = self.root / "deploy"
        self.deploy.mkdir(mode=0o755)
        for kind in ("backend-only", "archive-two-image", "frontend-only"):
            (self.deploy / "releases" / kind).mkdir(parents=True, mode=0o700)

    @staticmethod
    def write(path: Path, data: bytes) -> None:
        path.write_bytes(data)
        path.chmod(0o600)

    def bundle(self, day: int) -> Path:
        path = self.backups / f"pre-backend-only-202610{day:02}T010000Z-ABCDEFG{day}"
        path.mkdir(mode=0o700)
        content = {
            "epharm.dump": b"PGDMPfixture",
            "env.prod": b"SECRET=fixture\n",
            "release.env": b"RELEASE_ID=v0.1.1\n",
            "docker-compose.prod.yml": b"services: {}\n",
            "Caddyfile": b"example.test {}\n",
            "backend-v0.1.1.tar.gz": gzip.compress(b"backend image"),
            "frontend-v0.1.1.tar.gz": gzip.compress(b"frontend image"),
        }
        for name, body in content.items():
            self.write(path / name, body)
        checksums = "".join(
            f"{hashlib.sha256(body).hexdigest()}  {name}\n" for name, body in content.items()
        )
        self.write(path / "SHA256SUMS", checksums.encode())
        return path

    def active(self, kind: str, backup: Path, before: Path | None = None) -> Path:
        parent = self.deploy / "releases" / kind
        transaction = parent / "transaction"
        transaction.mkdir(mode=0o700)
        self.write(transaction / "manifest", f"backup_dir={backup}\n".encode())
        if before is not None:
            self.write(transaction / "pre-rollback-backup", f"{before}\n".encode())
        self.write(parent / "active-transaction", f"{transaction}\n".encode())
        return transaction

    def change_bundle(self, bundle: Path) -> None:
        target = bundle / "env.prod"
        self.write(target, b"SECRET=changed\n")
        manifest = bundle / "SHA256SUMS"
        rows = manifest.read_text().splitlines()
        rows = [f"{hashlib.sha256(target.read_bytes()).hexdigest()}  env.prod"
                if row.endswith("  env.prod") else row for row in rows]
        self.write(manifest, ("\n".join(rows) + "\n").encode())

    def run_prune(self, apply: bool = False, roots: list[Path] | None = None) -> list[Path]:
        with redirect_stdout(io.StringIO()):
            return MODULE.prune(self.backups, roots or [self.deploy], 3, apply)

    def test_dry_run_and_apply_keep_three_plus_both_active_reference_types(self):
        bundles = [self.bundle(day) for day in range(1, 7)]
        self.active("backend-only", bundles[0], bundles[1])
        self.active("archive-two-image", bundles[1], bundles[0])
        alias = self.root / "deploy-current"
        alias.symlink_to(self.deploy, target_is_directory=True)
        self.assertEqual(self.run_prune(roots=[self.deploy, alias]), [bundles[2]])
        self.assertTrue(all(path.exists() for path in bundles))
        self.assertEqual(self.run_prune(apply=True, roots=[alias]), [bundles[2]])
        self.assertFalse(bundles[2].exists())
        self.assertTrue(all(path.exists() for index, path in enumerate(bundles) if index != 2))
        self.assertEqual(self.run_prune(apply=True), [])

    def test_frontend_only_active_rollback_bundle_is_not_rotated(self):
        bundles = [self.bundle(day) for day in range(1, 7)]
        self.active("frontend-only", bundles[0], bundles[1])
        self.assertEqual(self.run_prune(apply=True), [bundles[2]])
        self.assertTrue(bundles[0].exists())
        self.assertTrue(bundles[1].exists())

    def test_fewer_than_keep_are_untouched(self):
        only = self.bundle(1)
        self.assertEqual(self.run_prune(apply=True), [])
        self.assertTrue(only.exists())

    def test_recovery_reference_is_protected_without_an_active_or_fake_healthy_baseline(self):
        bundles=[self.bundle(day) for day in range(1,6)]
        pointer=self.deploy/'releases/archive-two-image/recovery-backup'
        self.write(pointer,(str(bundles[0])+'\n').encode())
        self.assertEqual(self.run_prune(apply=True),[bundles[1]])
        self.assertTrue(bundles[0].exists())
        self.assertFalse((pointer.parent/'active-transaction').exists())

    def test_stale_malformed_escaping_or_symlink_recovery_reference_aborts_all_deletion(self):
        bundles=[self.bundle(day) for day in range(1,5)]
        pointer=self.deploy/'releases/archive-two-image/recovery-backup'
        for value in (b'relative\n',str(self.root/'outside').encode()+b'\n',
                      str(self.backups/'pre-backend-only-20261009T010000Z-ABCDEFG9').encode()+b'\n',
                      (str(bundles[0])+'\n'+str(bundles[1])+'\n').encode()):
            with self.subTest(value=value):
                self.write(pointer,value)
                with self.assertRaises((ValueError,OSError)):self.run_prune(apply=True)
                self.assertTrue(all(path.exists() for path in bundles))
        pointer.unlink();pointer.symlink_to(bundles[0]/'SHA256SUMS')
        with self.assertRaises(ValueError):self.run_prune(apply=True)
        self.assertTrue(all(path.exists() for path in bundles))

    def test_corrupt_recovery_bundle_is_not_hidden_by_newer_keepers(self):
        bundles=[self.bundle(day) for day in range(1,5)]
        self.write(self.deploy/'releases/archive-two-image/recovery-backup',(str(bundles[0])+'\n').encode())
        with (bundles[0]/'epharm.dump').open('ab') as stream:stream.write(b'drift')
        with self.assertRaisesRegex(ValueError,'checksum mismatch'):self.run_prune(apply=True)
        self.assertTrue(all(path.exists() for path in bundles))

    def test_changed_recovery_reference_before_delete_aborts(self):
        bundles=[self.bundle(day) for day in range(1,6)]
        pointer=self.deploy/'releases/archive-two-image/recovery-backup'
        self.write(pointer,(str(bundles[0])+'\n').encode())
        original=MODULE.references;calls=0
        def changed(*args):
            nonlocal calls
            calls+=1
            if calls==2:self.write(pointer,(str(bundles[1])+'\n').encode())
            return original(*args)
        with patch.object(MODULE,'references',side_effect=changed):
            with self.assertRaisesRegex(ValueError,'state or candidate changed'):self.run_prune(apply=True)
        self.assertTrue(all(path.exists() for path in bundles))

    def test_failed_checksum_never_deletes_old_bundle(self):
        bundles = [self.bundle(day) for day in range(1, 5)]
        with (bundles[-1] / "epharm.dump").open("ab") as output:
            output.write(b"corrupt")
        with self.assertRaisesRegex(ValueError, "checksum mismatch"):
            self.run_prune(apply=True)
        self.assertTrue(all(path.exists() for path in bundles))

    def test_unknown_or_symlink_bundle_content_aborts_without_deletion(self):
        bundles = [self.bundle(day) for day in range(1, 5)]
        self.write(bundles[0] / "unexpected.txt", b"do not delete")
        with self.assertRaisesRegex(ValueError, "unexpected files"):
            self.run_prune(apply=True)
        (bundles[0] / "unexpected.txt").unlink()
        (bundles[0] / "Caddyfile").unlink()
        (bundles[0] / "Caddyfile").symlink_to(self.root / "outside")
        with self.assertRaisesRegex(ValueError, "symlink"):
            self.run_prune(apply=True)
        self.assertTrue(all(path.exists() for path in bundles))

    def test_outside_or_symlinked_active_reference_aborts(self):
        bundles = [self.bundle(day) for day in range(1, 5)]
        transaction = self.active("backend-only", bundles[0])
        self.write(transaction / "manifest", f"backup_dir={self.root / 'elsewhere'}\n".encode())
        with self.assertRaisesRegex(ValueError, "escapes"):
            self.run_prune(apply=True)
        self.write(transaction / "manifest", f"backup_dir={bundles[0]}\n".encode())
        pointer = self.deploy / "releases/backend-only/active-transaction"
        pointer.unlink()
        pointer.symlink_to(transaction / "manifest")
        with self.assertRaisesRegex(ValueError, "symlink"):
            self.run_prune(apply=True)
        self.assertTrue(all(path.exists() for path in bundles))

    def test_changed_active_pointer_before_delete_aborts(self):
        bundles = [self.bundle(day) for day in range(1, 6)]
        transaction = self.active("backend-only", bundles[0])
        original = MODULE.references
        calls = 0

        def changed(*args):
            nonlocal calls
            calls += 1
            if calls == 2:
                self.write(transaction / "manifest", f"backup_dir={bundles[1]}\n".encode())
            return original(*args)

        with patch.object(MODULE, "references", side_effect=changed):
            with self.assertRaisesRegex(ValueError, "state or candidate changed"):
                self.run_prune(apply=True)
        self.assertTrue(all(path.exists() for path in bundles))

    def test_changed_candidate_with_consistent_new_checksums_aborts(self):
        bundles = [self.bundle(day) for day in range(1, 6)]
        self.active("backend-only", bundles[0])
        original = MODULE.references
        calls = 0

        def changed(*args):
            nonlocal calls
            calls += 1
            if calls == 2:
                self.change_bundle(bundles[1])
            return original(*args)

        with patch.object(MODULE, "references", side_effect=changed):
            with self.assertRaisesRegex(ValueError, "state or candidate changed"):
                self.run_prune(apply=True)
        self.assertTrue(all(path.exists() for path in bundles))

    def test_changed_keeper_or_protected_bundle_aborts(self):
        bundles = [self.bundle(day) for day in range(1, 6)]
        self.active("backend-only", bundles[0])
        original = MODULE.references
        for target in (bundles[0], bundles[-1]):
            calls = 0

            def changed(*args):
                nonlocal calls
                calls += 1
                if calls == 2:
                    self.change_bundle(target)
                return original(*args)

            with self.subTest(target=target), patch.object(MODULE, "references", side_effect=changed):
                with self.assertRaisesRegex(ValueError, "state or candidate changed"):
                    self.run_prune(apply=True)
                self.assertTrue(all(path.exists() for path in bundles))

    def test_invalid_keep_missing_roots_and_invalid_timestamp_abort(self):
        self.bundle(1)
        for keep in (0, 1, True):
            with self.assertRaises(ValueError):
                MODULE.prune(self.backups, [self.deploy], keep, True)
        with self.assertRaises(ValueError):
            MODULE.prune(self.backups, [], 3, True)
        bad = self.backups / "pre-backend-only-20261399T010000Z-ABCDEFG8"
        bad.mkdir(mode=0o700)
        with self.assertRaises(ValueError):
            self.run_prune(apply=True)

    def test_unknown_root_entry_is_not_touched(self):
        bundles = [self.bundle(day) for day in range(1, 5)]
        unknown = self.backups / "notes.txt"
        self.write(unknown, b"operator note")
        self.run_prune(apply=True)
        self.assertTrue(unknown.exists())
        self.assertFalse(bundles[0].exists())


if __name__ == "__main__":
    unittest.main()
