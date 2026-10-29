"""Offline, temporary-fixture-only contract for the narrow pointer recovery helper."""

from argparse import Namespace
from contextlib import redirect_stderr
import fcntl
import gzip
import hashlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


SOURCE = Path(__file__).resolve().parents[1] / "repair-frontend-pointer.py"
SPEC = importlib.util.spec_from_file_location("repair_frontend_pointer", SOURCE)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class PointerRepairTest(unittest.TestCase):
    @staticmethod
    def write(path, body):
        path.write_bytes(body)
        path.chmod(0o600)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="epharm-pointer-repair-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.deploy = self.root / "deploy"
        self.deploy.mkdir(mode=0o700)
        self.frontend = self.deploy / "releases/frontend-only"
        self.frontend.mkdir(parents=True, mode=0o700)
        self.backend = self.deploy / "releases/backend-only"
        self.backend.mkdir(mode=0o700)
        self.lock = self.backend / ".deploy.lock"
        self.write(self.lock, b"")
        self.transaction = self.frontend / "v0.1.52-20261022T112243Z-l9VApeE8"
        self.transaction.mkdir(mode=0o700)
        self.pointer = self.frontend / "active-transaction"
        self.original = b"releases/frontend-only/v0.1.52-20261022T112243Z-l9VApeE8\n"
        self.write(self.pointer, self.original)
        self.backups = self.root / "epharm-backend-only-backups"
        self.backups.mkdir(mode=0o700)
        self.bundle = self.make_bundle(1)
        self.before = self.make_bundle(2)
        self.manifest = self.transaction / "manifest"
        self.images = {"frontend": "sha256:" + "a" * 64, "backend": "sha256:" + "b" * 64}
        self.write(self.manifest, (
            "candidate_frontend_tag=v0.1.52\n"
            f"candidate_frontend_id={self.images['frontend']}\n"
            f"backend_id={self.images['backend']}\n"
            f"backup_dir={self.bundle}\n").encode())
        self.marker = self.transaction / "pre-rollback-backup"
        self.write(self.marker, (str(self.before) + "\n").encode())
        self.write(self.transaction / "rollback.release.env", b"unchanged fixture pins\n")
        self.args = Namespace(
            deploy_root=self.deploy, backup_root=self.backups,
            expected_pointer_sha256=MODULE.digest(self.original),
            expected_manifest_sha256=MODULE.digest(self.manifest.read_bytes()),
            expected_frontend_image_id=self.images["frontend"],
            expected_backend_image_id=self.images["backend"], apply=False)
        self.docker = patch.object(MODULE, "current_images", return_value=self.images)
        self.docker.start()
        self.addCleanup(self.docker.stop)

    def make_bundle(self, day):
        bundle = self.backups / f"pre-backend-only-202610{day:02}T010000Z-ABCDEFG{day}"
        bundle.mkdir(mode=0o700)
        contents = {
            "epharm.dump": b"PGDMPfixture", "env.prod": b"fixture config\n",
            "release.env": b"fixture release\n", "docker-compose.prod.yml": b"services: {}\n",
            "Caddyfile": b"fixture.test {}\n", "backend-v0.1.47.tar.gz": gzip.compress(b"fixture backend"),
            "frontend-v0.1.51.tar.gz": gzip.compress(b"fixture frontend"),
        }
        for name, body in contents.items():
            self.write(bundle / name, body)
        sums = "".join(f"{hashlib.sha256(body).hexdigest()}  {name}\n" for name, body in contents.items())
        self.write(bundle / "SHA256SUMS", sums.encode())
        return bundle

    def snapshot(self):
        result = {}
        for path in [self.root, *sorted(self.root.rglob("*"))]:
            info = path.lstat()
            result[str(path.relative_to(self.root))] = (
                info.st_mode, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns,
                path.read_bytes() if path.is_file() and not path.is_symlink() else None)
        return result

    def reject(self, message=None):
        before = self.snapshot()
        with self.assertRaises((ValueError, OSError)) as raised:
            MODULE.repair(self.args)
        if message:
            self.assertIn(message, str(raised.exception))
        self.assertEqual(self.snapshot(), before)

    def update_pointer(self, body):
        self.write(self.pointer, body)
        self.args.expected_pointer_sha256 = MODULE.digest(body)

    def update_manifest(self, body):
        self.write(self.manifest, body)
        self.args.expected_manifest_sha256 = MODULE.digest(body)

    def test_dry_run_makes_zero_writes_and_reports_both_protected_bundles(self):
        before = self.snapshot()
        result = MODULE.repair(self.args)
        self.assertEqual(result["status"], "would_repair")
        self.assertEqual(result["mode"], "dry-run")
        self.assertEqual(set(result["protected_bundles"]), {str(self.bundle), str(self.before)})
        self.assertFalse(result["deployment_performed"])
        self.assertEqual(before, self.snapshot())

    def test_cli_requires_root_before_inspection(self):
        argv = []
        for key, value in vars(self.args).items():
            if key != 'apply':
                argv.extend(['--' + key.replace('_', '-'), str(value)])
        before = self.snapshot()
        with patch.object(MODULE.os, 'geteuid', return_value=12345), redirect_stderr(io.StringIO()) as errors:
            self.assertEqual(MODULE.main(argv), 1)
        self.assertIn('as root', errors.getvalue())
        self.assertEqual(self.snapshot(), before)

    def test_apply_preserves_original_and_every_other_file(self):
        before = self.snapshot()
        self.args.apply = True
        result = MODULE.repair(self.args)
        self.assertEqual(result["status"], "repaired")
        self.assertEqual(self.pointer.read_bytes(), (str(self.transaction) + "\n").encode())
        audit = Path(result["original_bytes_audit"])
        self.assertEqual(audit.read_bytes(), self.original)
        self.assertEqual(audit.stat().st_mode & 0o777, 0o600)
        self.assertEqual(audit.parent.stat().st_mode & 0o777, 0o700)
        self.assertEqual(self.pointer.stat().st_mode & 0o777, 0o600)
        after = self.snapshot()
        for key, value in before.items():
            if key not in {str(self.pointer.relative_to(self.root)), str(self.frontend.relative_to(self.root))}:
                self.assertEqual(after[key], value, key)

    def test_canonical_idempotence_requires_current_expected_hash(self):
        self.args.apply = True
        result = MODULE.repair(self.args)
        before = self.snapshot()
        self.reject("pointer SHA256")
        self.args.expected_pointer_sha256 = result["canonical_pointer_sha256"]
        result = MODULE.repair(self.args)
        self.assertEqual(result["status"], "already_canonical")
        self.assertEqual(before, self.snapshot())

    def test_malformed_and_noncanonical_paths_are_rejected_without_writes(self):
        for body in (b"", self.original.rstrip(b"\n"), self.original + b"\n",
                     self.original.replace(b"\n", b"\r\n"), b"../frontend-only/escape\n",
                     b"releases/frontend-only/../outside\n", b"releases/frontend-only/invalid\n",
                     b"releases/frontend-only/v0.1.52-20261399T112243Z-l9VApeE8\n",
                     (str(self.root / "outside") + "\n").encode(),
                     b"releases/frontend-only/v0.1.52-20261022T112243Z-l9VApeE8\x00\n"):
            with self.subTest(body=body):
                self.update_pointer(body)
                self.reject()

    def test_pointer_and_transaction_symlinks_are_rejected(self):
        self.pointer.unlink()
        other = self.root / "original"
        self.write(other, self.original)
        self.pointer.symlink_to(other)
        self.reject("symlink")
        self.pointer.unlink()
        self.write(self.pointer, self.original)
        moved = self.root / "moved-transaction"
        self.transaction.rename(moved)
        self.transaction.symlink_to(moved, target_is_directory=True)
        self.reject("symlinks")

    def test_hash_mismatch_or_wrong_manifest_identity_rejected(self):
        self.args.expected_pointer_sha256 = "0" * 64
        self.reject("pointer SHA256")
        self.args.expected_pointer_sha256 = MODULE.digest(self.original)
        self.args.expected_manifest_sha256 = "0" * 64
        self.reject("manifest SHA256")
        self.update_manifest(self.manifest.read_bytes().replace(b"candidate_frontend_tag=v0.1.52", b"candidate_frontend_tag=v0.1.53"))
        self.reject("Manifest does not identify")

    def test_malformed_manifest_and_before_marker_rejected(self):
        original = self.manifest.read_bytes()
        for body in (original + f"backup_dir={self.bundle}\n".encode(),
                     original.replace(f"backup_dir={self.bundle}\n".encode(), b""),
                     original + b"not-a-field\n"):
            with self.subTest(body=body):
                self.update_manifest(body)
                self.reject()
        self.update_manifest(original)
        self.write(self.marker, b"../outside\n")
        self.reject()

    def test_manifest_symlink_and_foreign_manifest_image_rejected(self):
        original = self.manifest.read_bytes()
        self.update_manifest(original.replace(self.images['backend'].encode(), ("sha256:" + "c" * 64).encode()))
        self.reject("Manifest does not identify")
        self.update_manifest(original)
        other = self.root / "manifest-copy"
        self.write(other, original)
        self.manifest.unlink()
        self.manifest.symlink_to(other)
        self.reject("symlink")

    def test_audit_collision_never_overwrites_existing_evidence(self):
        self.args.apply = True
        audit_dir = self.frontend / "pointer-repair-audit"
        audit_dir.mkdir(mode=0o700)
        self.write(audit_dir / (MODULE.digest(self.original) + ".original"), b"other evidence\n")
        self.reject("audit record differs")

    def test_wrong_running_images_rejected(self):
        with patch.object(MODULE, "current_images", return_value={**self.images, "backend": "sha256:" + "c" * 64}):
            self.reject("Running frontend/backend")

    def test_unsafe_records_and_backup_reference_rejected(self):
        self.pointer.chmod(0o644)
        self.reject("Unsafe owner")
        self.pointer.chmod(0o600)
        self.update_manifest(self.manifest.read_bytes().replace(str(self.bundle).encode(), str(self.root / "outside").encode()))
        self.reject("escapes")

    def test_corrupt_backup_or_pre_rollback_backup_rejected(self):
        self.write(self.before / "epharm.dump", b"corrupted")
        self.reject("checksum mismatch")

    def test_busy_existing_lock_and_absent_lock_do_not_write(self):
        with self.lock.open("rb") as locked:
            fcntl.flock(locked, fcntl.LOCK_EX | fcntl.LOCK_NB)
            self.reject()
        self.lock.unlink()
        self.reject()

    def test_changed_pointer_or_manifest_refuses_replacement(self):
        self.args.apply = True
        original_check = MODULE.unchanged
        for target in (self.pointer, self.manifest):
            with self.subTest(target=target):
                self.write(self.pointer, self.original)
                body = target.read_bytes()
                calls = 0

                def race(state, lock):
                    nonlocal calls
                    calls += 1
                    if calls == 2:
                        self.write(target, body + b"changed\n")
                    return original_check(state, lock)

                with patch.object(MODULE, "unchanged", side_effect=race), self.assertRaisesRegex(ValueError, "changed during review"):
                    MODULE.repair(self.args)
                self.assertNotEqual(self.pointer.read_bytes(), (str(self.transaction) + "\n").encode())
                self.write(target, body)

    def test_running_images_changed_before_replace_refuse(self):
        self.args.apply = True
        calls = 0

        def images():
            nonlocal calls
            calls += 1
            return self.images if calls < 3 else {**self.images, "frontend": "sha256:" + "d" * 64}

        with patch.object(MODULE, "current_images", side_effect=images), self.assertRaisesRegex(ValueError, "Running frontend/backend"):
            MODULE.repair(self.args)
        self.assertEqual(self.pointer.read_bytes(), self.original)


if __name__ == "__main__":
    unittest.main()
