#!/usr/bin/env python3
"""Checksummed private-file snapshots. Never restores into an existing directory."""
import argparse
from datetime import datetime, timezone
import gzip
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import sys
import tarfile

CHUNK = 1024 * 1024
ROOTS = {'native-files': ('merch', 'merch-legacy'), 'credential-keys': ('learner-credentials',)}


def fingerprint(info):
    return info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns


def private_directory(path):
    path = Path(path)
    if not path.is_absolute() or '..' in path.parts or path.resolve(strict=True) != path:
        raise ValueError('Directory must be absolute, physical and non-symlink')
    info = path.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.geteuid() or stat.S_IMODE(info.st_mode) != 0o700:
        raise ValueError('Directory requires effective ownership and mode 0700')
    return path


def safe_name(name):
    if not isinstance(name, str):
        raise ValueError('Archive path must be text')
    path = PurePosixPath(name)
    if (not name or path.is_absolute() or path.as_posix() != name or '\\' in name
            or any(ord(c) < 32 or ord(c) == 127 for c in name)
            or any(p in ('', '.', '..') for p in name.split('/'))):
        raise ValueError('Unsafe archive path')
    return path


def regular(path):
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    info = os.fstat(descriptor)
    if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid()
            or stat.S_IMODE(info.st_mode) & 0o077 or info.st_nlink != 1):
        os.close(descriptor)
        raise ValueError('Private files must be owned, regular, unshared and mode 0600/0400')
    return os.fdopen(descriptor, 'rb'), fingerprint(info)


def digest_stream(source):
    result = hashlib.sha256()
    for block in iter(lambda: source.read(CHUNK), b''):
        result.update(block)
    return result.hexdigest()


def inventory(roots):
    rows, directories = {}, []
    for label, root in roots.items():
        root = private_directory(root)
        for current, folders, files in os.walk(root, followlinks=False):
            current = private_directory(current)
            relative = current.relative_to(root)
            directories.append(label if relative == Path('.') else f'{label}/{relative.as_posix()}')
            for name in folders:
                private_directory(current / name)
            for name in sorted(files):
                path = current / name
                key = f'{label}/{path.relative_to(root).as_posix()}'
                safe_name(key)
                with regular(path)[0] as source:
                    before = fingerprint(os.fstat(source.fileno()))
                    sha = digest_stream(source)
                    if before != fingerprint(os.fstat(source.fileno())) or before != fingerprint(path.lstat()):
                        raise ValueError('Source changed during snapshot inventory')
                rows[key] = {'size': before[2], 'sha256': sha, 'fingerprint': before, 'source': path}
    return rows, sorted(directories)


def validate_stream(stream, kind, destination=None):
    """Validate the entire uncompressed tar, optionally materializing into a fresh private stage."""
    expected_roots = ROOTS[kind]
    with tarfile.open(fileobj=stream, mode='r|') as archive:
        members = iter(archive)
        first = next(members, None)
        if first is None or first.name != 'manifest.json' or not first.isfile() or not 0 < first.size <= 64 * CHUNK:
            raise ValueError('Missing or invalid private backup manifest')
        manifest = json.load(archive.extractfile(first))
        if (not isinstance(manifest, dict) or type(manifest.get('version')) is not int or manifest.get('version') != 1 or manifest.get('kind') != kind
                or manifest.get('roots') != list(expected_roots) or not isinstance(manifest.get('files'), list)
                or not isinstance(manifest.get('directories'), list)):
            raise ValueError('Unexpected private backup manifest')
        expected, directories = {}, set()
        for value in manifest['directories']:
            path = safe_name(value)
            if path.parts[0] not in expected_roots or value in directories:
                raise ValueError('Invalid manifest directory')
            directories.add(value)
        if not set(expected_roots) <= directories:
            raise ValueError('Missing private backup roots')
        if any(str(PurePosixPath(value).parent) not in directories for value in directories if value not in expected_roots):
            raise ValueError('Missing manifest ancestor directory')
        for row in manifest['files']:
            if not isinstance(row, dict) or set(row) != {'path', 'size', 'sha256'}:
                raise ValueError('Invalid manifest file record')
            path = safe_name(row['path'])
            if (len(path.parts) < 2 or path.parts[0] not in expected_roots or row['path'] in expected
                    or row['path'] in directories or str(path.parent) not in directories
                    or type(row['size']) is not int or row['size'] < 0
                    or not isinstance(row['sha256'], str) or not re.fullmatch('[a-f0-9]{64}', row['sha256'])):
                raise ValueError('Invalid private backup file record')
            expected[row['path']] = row
        if kind == 'credential-keys' and 'learner-credentials/keys.json' not in expected:
            raise ValueError('Key recovery archive lacks keys.json')
        seen = set()
        for member in members:
            path = safe_name(member.name)
            if member.name in seen or member.name == 'manifest.json' or not (member.isfile() or member.isdir()):
                raise ValueError('Duplicate or unsafe private tar member')
            seen.add(member.name)
            if member.isdir():
                if member.name not in directories:
                    raise ValueError('Unexpected archive directory')
                if destination is not None:
                    (destination / path).mkdir(mode=0o700, parents=True, exist_ok=True)
                continue
            row = expected.get(member.name)
            if row is None or member.size != row['size']:
                raise ValueError('Unexpected archive file or size')
            source = archive.extractfile(member)
            result = hashlib.sha256()
            output = None
            try:
                if destination is not None:
                    output = (destination / path).open('xb')
                    os.chmod(destination / path, 0o600)
                for block in iter(lambda: source.read(CHUNK), b''):
                    result.update(block)
                    if output is not None:
                        output.write(block)
            finally:
                if output is not None:
                    output.close()
            if result.hexdigest() != row['sha256']:
                raise ValueError('Private file checksum mismatch')
        if seen != directories | set(expected):
            raise ValueError('Private archive has missing entries')
    return {'kind': kind, 'files': len(expected), 'bytes': sum(r['size'] for r in expected.values())}


def verify(archive, kind, destination=None):
    archive = Path(archive)
    private_directory(archive.parent)
    if not re.fullmatch(r'epharm-' + kind + r'-[0-9]{8}T[0-9]{6}Z\.tar\.gz', archive.name):
        raise ValueError('Unexpected private backup name')
    sidecar = Path(str(archive) + '.sha256')
    with regular(sidecar)[0] as source:
        checksum = source.read(1024)
    expected = re.fullmatch(rb'([a-f0-9]{64})  ' + re.escape(archive.name.encode()) + rb'\n', checksum)
    if expected is None:
        raise ValueError('Invalid private checksum sidecar')
    with regular(archive)[0] as source:
        before = fingerprint(os.fstat(source.fileno()))
        if digest_stream(source) != expected[1].decode():
            raise ValueError('Private archive checksum mismatch')
        source.seek(0)
        with gzip.GzipFile(fileobj=source) as stream:
            result = validate_stream(stream, kind)
            while stream.read(CHUNK):
                pass
        if before != fingerprint(os.fstat(source.fileno())) or before != fingerprint(archive.lstat()):
            raise ValueError('Archive changed during verification')
        if destination is not None:
            destination = Path(destination)
            private_directory(destination.parent)
            if destination.exists() or destination.is_symlink():
                raise ValueError('Restore target must not exist')
            if shutil.disk_usage(destination.parent).free < result['bytes'] + 256 * CHUNK:
                raise ValueError('Insufficient isolated restore capacity')
            destination.mkdir(mode=0o700)
            try:
                source.seek(0)
                with gzip.GzipFile(fileobj=source) as stream:
                    validate_stream(stream, kind, destination)
                    while stream.read(CHUNK):
                        pass
                if before != fingerprint(os.fstat(source.fileno())) or before != fingerprint(archive.lstat()):
                    raise ValueError('Archive changed during restore')
            except BaseException:
                shutil.rmtree(destination)
                raise
    return result


def create(directory, kind, roots):
    if set(roots) != set(ROOTS[kind]):
        raise ValueError('All explicit source roots are required')
    roots = {key: private_directory(roots[key]) for key in ROOTS[kind]}
    paths = list(roots.values())
    if any(a == b or a in b.parents or b in a.parents for i, a in enumerate(paths) for b in paths[i + 1:]):
        raise ValueError('Source roots must be disjoint')
    directory = Path(directory)
    private_directory(directory.parent)
    directory.mkdir(mode=0o700, exist_ok=True)
    private_directory(directory)
    if directory.name != kind or any(directory == p or p in directory.parents or directory in p.parents for p in paths):
        raise ValueError('Dedicated backup directory must be outside source roots')
    rows, directories = inventory(roots)
    if kind == 'credential-keys' and 'learner-credentials/keys.json' not in rows:
        raise ValueError('Explicit key directory has no keys.json')
    total = sum(row['size'] for row in rows.values())
    # Gzip may expand incompressible input; refuse before consuming the host's final free reserve.
    if shutil.disk_usage(directory).free < total + total // 20 + 256 * CHUNK:
        raise ValueError('Insufficient capacity for private snapshot plus 256 MiB reserve')
    stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    final = directory / f'epharm-{kind}-{stamp}.tar.gz'
    temporary = Path(str(final) + '.partial')
    manifest = {'version': 1, 'kind': kind, 'createdAt': stamp, 'roots': list(ROOTS[kind]),
                'directories': directories,
                'files': [{'path': key, 'size': row['size'], 'sha256': row['sha256']} for key, row in sorted(rows.items())]}
    temporary_identity = None
    try:
        with temporary.open('xb') as output:
            opened = os.fstat(output.fileno())
            temporary_identity = opened.st_dev, opened.st_ino
            os.chmod(temporary, 0o600)
            with tarfile.open(fileobj=output, mode='w:gz') as archive:
                data = json.dumps(manifest, separators=(',', ':')).encode()
                entry = tarfile.TarInfo('manifest.json'); entry.size = len(data); entry.mode = 0o600
                archive.addfile(entry, io.BytesIO(data))
                for key in directories:
                    entry = tarfile.TarInfo(key); entry.type = tarfile.DIRTYPE; entry.mode = 0o700
                    archive.addfile(entry)
                for key, row in sorted(rows.items()):
                    with regular(row['source'])[0] as source:
                        if fingerprint(os.fstat(source.fileno())) != row['fingerprint']:
                            raise ValueError('Source changed before archiving')
                        entry = tarfile.TarInfo(key); entry.size = row['size']; entry.mode = 0o600
                        archive.addfile(entry, source)
                        if fingerprint(os.fstat(source.fileno())) != row['fingerprint'] or fingerprint(row['source'].lstat()) != row['fingerprint']:
                            raise ValueError('Source changed while archiving')
            output.flush(); os.fsync(output.fileno())
        # O_EXCL-equivalent publication: never overwrite another restore point.
        os.link(temporary, final)
        temporary.unlink()
        temporary_identity = None
        with Path(str(final) + '.sha256').open('x') as sidecar:
            os.chmod(sidecar.name, 0o600)
            with final.open('rb') as source:
                sidecar.write(f'{digest_stream(source)}  {final.name}\n')
            sidecar.flush(); os.fsync(sidecar.fileno())
        result = verify(final, kind)
        descriptor = os.open(directory, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
        return {**result, 'archive': str(final)}
    finally:
        if temporary_identity is not None:
            try:
                current = temporary.lstat()
            except FileNotFoundError:
                pass
            else:
                if stat.S_ISREG(current.st_mode) and (current.st_dev, current.st_ino) == temporary_identity:
                    temporary.unlink()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=('create', 'verify', 'restore'))
    parser.add_argument('--kind', required=True, choices=tuple(ROOTS))
    parser.add_argument('--directory', type=Path)
    parser.add_argument('--root', action='append', default=[])
    parser.add_argument('--archive', type=Path)
    parser.add_argument('--destination', type=Path)
    args = parser.parse_args()
    try:
        if args.operation == 'create':
            if args.directory is None or any('=' not in item for item in args.root):
                raise ValueError('Creation requires directory and labelled roots')
            roots = dict(item.split('=', 1) for item in args.root)
            if len(roots) != len(args.root):
                raise ValueError('Duplicate source root')
            result = create(args.directory, args.kind, roots)
        else:
            if args.archive is None or (args.operation == 'restore' and args.destination is None):
                raise ValueError('Archive and a new restore destination are required')
            result = verify(args.archive, args.kind, args.destination if args.operation == 'restore' else None)
    except (OSError, ValueError, EOFError, tarfile.TarError, TypeError, KeyError) as error:
        # Do not print manifest values, source filenames, keys or provider configuration.
        print(f'ERROR: private snapshot refused ({type(error).__name__})', file=sys.stderr)
        return 1
    print(json.dumps(result))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
