#!/usr/bin/env python3
"""Retain three verified rollback bundles plus every active release reference.

The caller holds the backend-only deploy flock across this entire command.
Dry-run is the default; --apply removes only validated bundle files.
"""

import argparse
from datetime import datetime
import gzip
import hashlib
import os
from pathlib import Path
import re
import stat


BUNDLE = re.compile(r"pre-backend-only-(\d{8}T\d{6}Z)-[A-Za-z0-9]{8}\Z")
IMAGE = re.compile(r"(?:backend|frontend)-v\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?\.tar\.gz\Z")
SHA_LINE = re.compile(r"([a-f0-9]{64})  ([A-Za-z0-9_.-]+)\Z")
BASE_FILES = {"epharm.dump", "env.prod", "release.env", "docker-compose.prod.yml", "Caddyfile"}
MAX_RECORD = 16384


def fingerprint(path: Path) -> tuple[int, ...]:
    info = path.lstat()
    return (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns,
            info.st_ctime_ns, info.st_mode, info.st_uid)


def secure(path: Path, directory: bool) -> None:
    info = path.lstat()
    if path.is_symlink() or info.st_uid != os.geteuid() or stat.S_IMODE(info.st_mode) != (0o700 if directory else 0o600):
        raise ValueError(f"Unsafe owner, mode or symlink: {path}")
    if not (stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode)):
        raise ValueError(f"Unexpected file type: {path}")


def record(path: Path, required: bool = True) -> tuple[bytes, tuple[int, ...]] | None:
    if not path.exists() and not path.is_symlink():
        if required:
            raise ValueError(f"Missing protected record: {path}")
        return None
    secure(path, False)
    info = path.lstat()
    if info.st_size > MAX_RECORD:
        raise ValueError(f"Oversized protected record: {path}")
    before = fingerprint(path)
    body = path.read_bytes()
    if fingerprint(path) != before:
        raise ValueError(f"Protected record changed during read: {path}")
    return body, before


def one_path(raw: bytes, source: Path) -> Path:
    lines = raw.decode("utf-8").splitlines()
    if len(lines) != 1 or not lines[0].startswith("/") or raw != (lines[0] + "\n").encode():
        raise ValueError(f"Malformed protected path: {source}")
    return Path(lines[0])


def backup_reference(raw: Path, backup_root: Path) -> Path:
    if raw.parent != backup_root or not BUNDLE.fullmatch(raw.name) or raw.resolve(strict=True) != raw:
        raise ValueError(f"Rollback reference escapes the dedicated backup root: {raw}")
    secure(raw, True)
    return raw


def references(deploy_roots: list[Path], backup_root: Path) -> tuple[set[Path], dict[Path, object]]:
    protected: set[Path] = set()
    snapshot: dict[Path, object] = {}
    for root in deploy_roots:
        for kind in ("backend-only", "archive-two-image"):
            parent = root / "releases" / kind
            if parent.exists() or parent.is_symlink():
                secure(parent, True)
            pointer = parent / "active-transaction"
            active = record(pointer, required=False)
            snapshot[pointer] = active
            if active is None:
                continue
            transaction = one_path(active[0], pointer)
            if transaction.parent != parent or transaction.resolve(strict=True) != transaction:
                raise ValueError(f"Active transaction escapes its release directory: {transaction}")
            secure(transaction, True)
            manifest_file = transaction / "manifest"
            manifest = record(manifest_file)
            snapshot[manifest_file] = manifest
            fields: dict[str, str] = {}
            for line in manifest[0].decode("utf-8").splitlines():
                key, separator, value = line.partition("=")
                if not separator or key in fields:
                    raise ValueError(f"Malformed active manifest: {manifest_file}")
                fields[key] = value
            if "backup_dir" not in fields:
                raise ValueError(f"Active manifest has no backup_dir: {manifest_file}")
            protected.add(backup_reference(Path(fields["backup_dir"]), backup_root))
            before_file = transaction / "pre-rollback-backup"
            before = record(before_file, required=False)
            snapshot[before_file] = before
            if before is not None:
                protected.add(backup_reference(one_path(before[0], before_file), backup_root))
    return protected, snapshot


def bundle_files(directory: Path) -> tuple[list[Path], dict[str, str]]:
    secure(directory, True)
    if os.path.ismount(directory) or directory.stat().st_dev != directory.parent.stat().st_dev:
        raise ValueError(f"Rollback bundle is a mount point: {directory}")
    files = list(directory.iterdir())
    names = {path.name for path in files}
    images = {name for name in names if IMAGE.fullmatch(name)}
    backend = {name for name in images if name.startswith("backend-")}
    frontend = {name for name in images if name.startswith("frontend-")}
    expected = BASE_FILES | images | {"SHA256SUMS"}
    if len(backend) != 1 or len(frontend) != 1 or names != expected:
        raise ValueError(f"Rollback bundle has missing or unexpected files: {directory}")
    for path in files:
        secure(path, False)
        if path.stat().st_dev != directory.stat().st_dev:
            raise ValueError(f"Rollback artifact crosses a filesystem boundary: {path}")
        if path.stat().st_size == 0:
            raise ValueError(f"Empty rollback artifact: {path}")
    manifest = record(directory / "SHA256SUMS")[0]
    hashes: dict[str, str] = {}
    for line in manifest.decode("ascii").splitlines():
        match = SHA_LINE.fullmatch(line)
        if match is None or match[2] in hashes:
            raise ValueError(f"Malformed rollback checksums: {directory}")
        hashes[match[2]] = match[1]
    if set(hashes) != expected - {"SHA256SUMS"}:
        raise ValueError(f"Incomplete rollback checksums: {directory}")
    return files, hashes


def verify_bundle(directory: Path) -> tuple[tuple[int, ...], tuple[tuple[str, tuple[int, ...], str], ...]]:
    directory_before = fingerprint(directory)
    files, hashes = bundle_files(directory)
    snapshot = []
    for path in files:
        before = fingerprint(path)
        digest = hashlib.sha256()
        with path.open("rb") as source:
            for block in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(block)
        if path.name != "SHA256SUMS" and digest.hexdigest() != hashes[path.name]:
            raise ValueError(f"Rollback checksum mismatch: {path}")
        if path.name.endswith(".tar.gz"):
            with gzip.open(path, "rb") as source:
                for _ in iter(lambda: source.read(1024 * 1024), b""):
                    pass
        if fingerprint(path) != before:
            raise ValueError(f"Rollback artifact changed during verification: {path}")
        snapshot.append((path.name, before, digest.hexdigest()))
    with (directory / "epharm.dump").open("rb") as source:
        if source.read(5) != b"PGDMP":
            raise ValueError(f"Invalid PostgreSQL custom dump: {directory}")
    if fingerprint(directory) != directory_before:
        raise ValueError(f"Rollback bundle changed during verification: {directory}")
    return directory_before, tuple(sorted(snapshot))


def unchanged_bundle(directory: Path, snapshot: tuple) -> bool:
    files, _ = bundle_files(directory)
    return (fingerprint(directory), tuple(sorted((path.name, fingerprint(path)) for path in files))) == (
        snapshot[0], tuple((name, info) for name, info, _ in snapshot[1]))


def prune(directory: Path, deploy_roots: list[Path], keep: int, apply: bool) -> list[Path]:
    if type(keep) is not int or keep < 2 or not deploy_roots:
        raise ValueError("At least one deployment root and keep >= 2 are required")
    if not directory.is_absolute() or directory.name != "epharm-backend-only-backups" or directory.resolve(strict=True) != directory:
        raise ValueError("Backup directory must be an absolute, real, dedicated epharm-backend-only-backups path")
    secure(directory, True)
    resolved_roots = []
    roots_at_start = []
    for root in deploy_roots:
        if not root.is_absolute():
            raise ValueError("Deployment roots must be absolute")
        resolved = root.resolve(strict=True)  # A caller-supplied deployment symlink is acceptable.
        if not resolved.is_dir() or resolved.is_symlink():
            raise ValueError(f"Deployment root is not a real directory: {root}")
        roots_at_start.append((root, resolved))
        if resolved not in resolved_roots:
            resolved_roots.append(resolved)
    protected, snapshot = references(resolved_roots, directory)
    bundles = []
    for path in directory.iterdir():
        matched = BUNDLE.fullmatch(path.name)
        if matched:
            datetime.strptime(matched[1], "%Y%m%dT%H%M%SZ")
            bundles.append(path)
    bundles.sort(reverse=True)
    names = {path: verify_bundle(path) for path in bundles}  # Complete preflight before any deletion.
    retained = set(bundles[:keep]) | protected
    if not protected.issubset(names):
        raise ValueError("An active rollback reference is not an available verified bundle")
    doomed = [path for path in bundles if path not in retained]
    for path in doomed:
        if apply:
            if any(root.resolve(strict=True) != resolved for root, resolved in roots_at_start):
                raise ValueError("Deployment root changed during retention")
            current, current_snapshot = references(resolved_roots, directory)
            if (current != protected or current_snapshot != snapshot
                    or any(not unchanged_bundle(keeper, names[keeper]) for keeper in retained)
                    or names[path] != verify_bundle(path)):
                raise ValueError("Rollback state or candidate changed during retention")
            for name, _, _ in names[path][1]:
                (path / name).unlink()
            path.rmdir()
            print(f"DELETE {path}")
        else:
            print(f"WOULD DELETE {path}")
    return doomed


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--deploy-root", type=Path, action="append", required=True)
    parser.add_argument("--keep", type=int, default=3)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    if args.keep < 2:
        parser.error("--keep must be at least 2")
    try:
        doomed = prune(args.directory, args.deploy_root, args.keep, args.apply)
    except (OSError, EOFError, ValueError, UnicodeError) as error:
        parser.exit(1, f"ERROR: {error}\n")
    print(f"Rollback retention: {len(doomed)} bundle(s) {'removed' if args.apply else 'eligible for removal'}")


if __name__ == "__main__":
    main()
