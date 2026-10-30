#!/usr/bin/env python3
"""Review or narrowly repair a root-relative frontend release pointer; never deploy."""

import argparse
from contextlib import contextmanager
from datetime import datetime
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import sys


SPEC = importlib.util.spec_from_file_location(
    "release_backup_retention", Path(__file__).with_name("prune-release-backups.py"))
RETENTION = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RETENTION)
SHA = re.compile(r"[a-f0-9]{64}\Z")
IMAGE = re.compile(r"sha256:[a-f0-9]{64}\Z")
TRANSACTION = re.compile(
    r"(v[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?)-(\d{8}T\d{6}Z)-[A-Za-z0-9]{8}\Z")


def digest(body):
    return hashlib.sha256(body).hexdigest()


def real_root(path):
    if (not path.is_absolute() or ".." in path.parts or
            path.resolve(strict=True) != path or not path.is_dir()):
        raise ValueError("Roots must be existing canonical absolute directories without symlinks")
    return path


def identity(path):
    info = path.lstat()
    return info.st_dev, info.st_ino, info.st_mode, info.st_uid


@contextmanager
def deployment_lock(root):
    path = root / "releases/backend-only/.deploy.lock"
    if path.parent.resolve(strict=True) != path.parent:
        raise ValueError("Deployment lock directory must not contain symlinks")
    RETENTION.secure(path.parent, True)
    RETENTION.secure(path, False)
    with os.fdopen(os.open(path, os.O_RDONLY | os.O_NOFOLLOW), "rb") as handle:
        fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        before = RETENTION.fingerprint(path)
        info = os.fstat(handle.fileno())
        if (info.st_dev, info.st_ino) != before[:2]:
            raise ValueError("Deployment lock changed while opening")
        yield path, before


def current_images():
    # Only image IDs of these two fixed containers may leave Docker.
    import subprocess
    try:
        result = subprocess.run(
            ["docker", "inspect", "--format", "{{.Image}}", "epharm-frontend", "epharm-backend"],
            capture_output=True, text=True, check=True, timeout=15)
    except (OSError, subprocess.SubprocessError):
        raise ValueError("Cannot inspect the two running application image IDs") from None
    images = result.stdout.splitlines()
    if len(images) != 2 or any(not IMAGE.fullmatch(value) for value in images):
        raise ValueError("Docker did not return two exact image IDs")
    return {"frontend": images[0], "backend": images[1]}


def transaction_path(body, root):
    parent = root / "releases/frontend-only"
    relative = b"releases/frontend-only/"
    absolute = (str(parent) + "/").encode()
    if body.startswith(relative):
        name_bytes, canonical = body[len(relative):], False
    elif body.startswith(absolute):
        name_bytes, canonical = body[len(absolute):], True
    else:
        raise ValueError("Pointer must name a direct frontend transaction under the deployment root")
    if not name_bytes.endswith(b"\n"):
        raise ValueError("Pointer must end in exactly one newline")
    name = name_bytes[:-1].decode("utf-8")
    match = TRANSACTION.fullmatch(name)
    if match is None:
        raise ValueError("Pointer has an invalid frontend release transaction name")
    datetime.strptime(match[2], "%Y%m%dT%H%M%SZ")
    transaction = parent / name
    if transaction.resolve(strict=True) != transaction:
        raise ValueError("Frontend transaction must not contain symlinks")
    RETENTION.secure(transaction, True)
    return transaction, canonical, match[1]


def manifest_fields(body):
    fields = {}
    for line in body.decode("utf-8").splitlines():
        key, separator, value = line.partition("=")
        if not separator or not key or key in fields:
            raise ValueError("Malformed frontend transaction manifest")
        fields[key] = value
    return fields


def image_check(expected):
    if current_images() != expected:
        raise ValueError("Running frontend/backend image IDs differ from the expected transaction")


def inspect_state(args):
    root, backup_root = real_root(args.deploy_root), real_root(args.backup_root)
    if backup_root.name != "epharm-backend-only-backups":
        raise ValueError("Use the dedicated epharm-backend-only-backups root")
    RETENTION.secure(backup_root, True)
    parent = root / "releases/frontend-only"
    if parent.resolve(strict=True) != parent:
        raise ValueError("Frontend release directory must not contain symlinks")
    RETENTION.secure(parent, True)
    pointer = parent / "active-transaction"
    active = RETENTION.record(pointer)
    if digest(active[0]) != args.expected_pointer_sha256:
        raise ValueError("Current pointer SHA256 differs from the reviewed value")
    transaction, canonical, release = transaction_path(active[0], root)
    manifest_path = transaction / "manifest"
    manifest = RETENTION.record(manifest_path)
    if digest(manifest[0]) != args.expected_manifest_sha256:
        raise ValueError("Current manifest SHA256 differs from the reviewed value")
    fields = manifest_fields(manifest[0])
    expected = {"frontend": args.expected_frontend_image_id, "backend": args.expected_backend_image_id}
    if (fields.get("candidate_frontend_id") != expected["frontend"] or
            fields.get("backend_id") != expected["backend"] or
            fields.get("candidate_frontend_tag") != release):
        raise ValueError("Manifest does not identify the expected frontend release and image IDs")
    if not fields.get("backup_dir"):
        raise ValueError("Manifest has no backup_dir")
    backup = RETENTION.backup_reference(Path(fields["backup_dir"]), backup_root)
    before_path = transaction / "pre-rollback-backup"
    before = RETENTION.record(before_path, required=False)
    bundles = {backup}
    if before is not None:
        bundles.add(RETENTION.backup_reference(RETENTION.one_path(before[0], before_path), backup_root))
    verified = {path: RETENTION.verify_bundle(path) for path in sorted(bundles)}
    image_check(expected)
    return {
        "root": root, "backup_root": backup_root, "parent": parent, "pointer": pointer,
        "transaction": transaction, "canonical": canonical, "original": active[0],
        "records": {pointer: active, manifest_path: manifest, before_path: before},
        "directories": {path: identity(path) for path in (root, parent, transaction, backup_root)},
        "bundles": verified, "images": expected,
    }


def unchanged(state, lock):
    if RETENTION.fingerprint(lock[0]) != lock[1]:
        raise ValueError("Deployment lock changed during review")
    for path, expected in state["directories"].items():
        if path.resolve(strict=True) != path or identity(path) != expected:
            raise ValueError("A protected directory changed during review")
    for path, expected in state["records"].items():
        if RETENTION.record(path, required=expected is not None) != expected:
            raise ValueError("Pointer, manifest or rollback reference changed during review")
    for path, expected in state["bundles"].items():
        if not RETENTION.unchanged_bundle(path, expected):
            raise ValueError("A verified rollback bundle changed during review")
    image_check(state["images"])


def fsync_directory(path):
    descriptor = os.open(path, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def write_new(path, body):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, "wb") as output:
        os.fchmod(output.fileno(), 0o600)
        output.write(body)
        output.flush()
        os.fsync(output.fileno())


def apply_repair(state, lock, audit, canonical):
    if audit.is_relative_to(state["backup_root"]):
        raise ValueError("Repair evidence must stay outside rollback bundles")
    try:
        audit.parent.mkdir(mode=0o700)
    except FileExistsError:
        pass
    RETENTION.secure(audit.parent, True)
    if audit.parent.resolve(strict=True) != audit.parent:
        raise ValueError("Repair audit directory must not contain symlinks")
    if audit.exists() or audit.is_symlink():
        if RETENTION.record(audit)[0] != state["original"]:
            raise ValueError("Existing audit record differs from the original pointer")
    else:
        write_new(audit, state["original"])
    fsync_directory(audit.parent)
    temporary = state["parent"] / (".active-transaction.repair-" + secrets.token_hex(8))
    write_new(temporary, canonical)
    # Keep evidence/temporary files if a last-moment refusal occurs; never delete release history.
    unchanged(state, lock)
    if RETENTION.record(temporary)[0] != canonical or RETENTION.record(audit)[0] != state["original"]:
        raise ValueError("Repair output or audit record changed before replacement")
    os.replace(temporary, state["pointer"])
    fsync_directory(state["parent"])


def repair(args):
    for value in (args.expected_pointer_sha256, args.expected_manifest_sha256):
        if not SHA.fullmatch(value):
            raise ValueError("Expected hashes must be lowercase SHA256 values")
    for value in (args.expected_frontend_image_id, args.expected_backend_image_id):
        if not IMAGE.fullmatch(value):
            raise ValueError("Expected images must be exact sha256 image IDs")
    real_root(args.deploy_root)
    with deployment_lock(args.deploy_root) as lock:
        state = inspect_state(args)
        canonical = (str(state["transaction"]) + "\n").encode()
        audit = state["parent"] / "pointer-repair-audit" / (digest(state["original"]) + ".original")
        result = {
            "mode": "apply" if args.apply else "dry-run",
            "status": "already_canonical" if state["canonical"] else "would_repair",
            "pointer": str(state["pointer"]), "transaction": str(state["transaction"]),
            "expected_pointer_sha256": args.expected_pointer_sha256,
            "canonical_pointer_sha256": digest(canonical),
            "manifest_sha256": args.expected_manifest_sha256, "image_ids": state["images"],
            "protected_bundles": [str(path) for path in state["bundles"]],
            "original_bytes_audit": None if state["canonical"] else str(audit),
            "deployment_performed": False, "retention_performed": False,
        }
        unchanged(state, lock)
        if args.apply and not state["canonical"]:
            apply_repair(state, lock, audit, canonical)
            result["status"] = "repaired"
        return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--deploy-root", type=Path, required=True)
    parser.add_argument("--backup-root", type=Path, required=True)
    parser.add_argument("--expected-pointer-sha256", required=True)
    parser.add_argument("--expected-manifest-sha256", required=True)
    parser.add_argument("--expected-frontend-image-id", required=True)
    parser.add_argument("--expected-backend-image-id", required=True)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args(argv)
    if os.geteuid() != 0:
        print("ERROR: run the recovery CLI as root to preserve root-owned release evidence", file=sys.stderr)
        return 1
    try:
        result = repair(args)
    except (OSError, ValueError, RuntimeError, EOFError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print(json.dumps(result, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
