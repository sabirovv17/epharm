#!/usr/bin/env python3
"""Count-based daily backup retention. Caller must hold the producer's backup lock."""
import argparse
from datetime import datetime
import gzip
import hashlib
import os
from pathlib import Path, PurePosixPath
import re
import stat
import sys
import tarfile

PATTERNS = {
    'postgres': re.compile(r'epharm-postgres-([0-9]{8}T[0-9]{6}Z)\.dump\.gz(?:\.sha256)?\Z'),
    'minio': re.compile(r'epharm-minio-([0-9]{8}T[0-9]{6}Z)\.tar\.gz(?:\.sha256)?\Z'),
    'native-files': re.compile(r'epharm-native-files-([0-9]{8}T[0-9]{6}Z)\.tar\.gz(?:\.sha256)?\Z'),
}
CHUNK = 1024 * 1024


def fingerprint(info):
    return info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns


def validate_directory(directory: Path, kind: str, descriptor=None) -> None:
    if (not directory.is_absolute() or '..' in directory.parts or len(directory.parts) < 4
            or directory.name != kind or directory == Path.home()
            or directory.resolve(strict=True) != directory or directory.is_symlink()
            or not directory.is_dir()):
        raise ValueError('Use an absolute, dedicated, non-symlink backup component directory')
    current = directory.stat(follow_symlinks=False)
    if current.st_uid != os.geteuid() or stat.S_IMODE(current.st_mode) != 0o700:
        raise ValueError('Backup directory must be owned by the effective user with mode 0700')
    if descriptor is not None:
        opened = os.fstat(descriptor)
        if (current.st_dev, current.st_ino) != (opened.st_dev, opened.st_ino):
            raise ValueError('Backup directory changed; no further deletion allowed')


def inventory(descriptor, kind):
    files = {}
    for name in os.listdir(descriptor):
        matched = PATTERNS[kind].fullmatch(name)
        if not matched:
            continue
        datetime.strptime(matched[1], '%Y%m%dT%H%M%SZ')
        info = os.stat(name, dir_fd=descriptor, follow_symlinks=False)
        if stat.S_ISLNK(info.st_mode):
            raise ValueError(f'Matching backup symlink refused: {name}')
        if stat.S_ISDIR(info.st_mode):
            continue
        if not stat.S_ISREG(info.st_mode):
            raise ValueError(f'Matching backup is not a regular file: {name}')
        files[name] = fingerprint(info)
    return files


def unchanged(descriptor, name, expected):
    info = os.stat(name, dir_fd=descriptor, follow_symlinks=False)
    if not stat.S_ISREG(info.st_mode) or fingerprint(info) != expected:
        raise ValueError(f'Backup changed; no further deletion allowed: {name}')


def open_snapshot(descriptor, name, expected):
    file_descriptor = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=descriptor)
    info = os.fstat(file_descriptor)
    if not stat.S_ISREG(info.st_mode) or fingerprint(info) != expected:
        os.close(file_descriptor)
        raise ValueError(f'Backup changed before validation: {name}')
    return os.fdopen(file_descriptor, 'rb')


def read_checksum(descriptor, name, files):
    sidecar = name + '.sha256'
    if sidecar not in files:
        raise ValueError(f'Missing backup checksum: {name}')
    with open_snapshot(descriptor, sidecar, files[sidecar]) as source:
        body = source.read(1024)
        if fingerprint(os.fstat(source.fileno())) != files[sidecar]:
            raise ValueError(f'Checksum changed during validation: {name}')
    matched = re.fullmatch(rb'([a-f0-9]{64})  ' + re.escape(name.encode('ascii')) + rb'\n?', body)
    if not matched:
        raise ValueError(f'Invalid backup checksum sidecar: {name}')
    unchanged(descriptor, sidecar, files[sidecar])
    return matched[1].decode('ascii')


def validate_minio(stream, require_metadata=False):
    seen = set()
    has_manifest = has_data = has_bucket = False
    bucket_names, policies = set(), set()
    manifest_body = b''
    metadata_body = None
    object_bodies = {}
    with tarfile.open(fileobj=stream, mode='r|') as archive:
        for member in archive:
            path = PurePosixPath(member.name)
            if (path.is_absolute() or '..' in path.parts or not path.parts
                    or member.name.rstrip('/') != path.as_posix() or '\\' in member.name
                    or any(ord(c) < 32 or ord(c) == 127 for c in member.name)
                    or path.as_posix() in seen or not (member.isfile() or member.isdir())
                    or (member.isfile() and member.name.endswith('/'))):
                raise ValueError('MinIO backup contains unsafe or duplicate tar entries')
            seen.add(path.as_posix())
            if path.parts == ('manifest.txt',):
                if not member.isfile() or not 0 < member.size <= 65536:
                    raise ValueError('MinIO backup manifest is invalid')
                has_manifest = True
                manifest_body = archive.extractfile(member).read()
            elif path.parts == ('object-metadata.json',):
                from minio_metadata import MAX_MANIFEST
                if not member.isfile() or not 0 < member.size <= MAX_MANIFEST:
                    raise ValueError('Invalid object metadata manifest')
                metadata_body = archive.extractfile(member).read()
            elif path.parts[0] == 'data':
                if len(path.parts) == 1:
                    if not member.isdir():
                        raise ValueError('MinIO data root is not a directory')
                    has_data = True
                elif len(path.parts) == 2:
                    if not member.isdir():
                        raise ValueError('MinIO bucket root is not a directory')
                    has_bucket = True
                    bucket_names.add(path.parts[1])
                elif member.isfile():
                    from minio_metadata import key_path, file_hash
                    key = '/'.join(path.parts[2:]); key_path(key)
                    object_bodies[(path.parts[1], key)] = file_hash(archive.extractfile(member))
            elif path.parts[0] == 'policies':
                if len(path.parts) == 1:
                    if not member.isdir():
                        raise ValueError('Policy root must be a directory')
                elif len(path.parts) == 2 and member.isfile() and path.name.endswith('.json'):
                    from minio_policy import policy_value
                    if not 0 < member.size <= 1024 * 1024:
                        raise ValueError('Invalid bucket policy size')
                    policy_value(archive.extractfile(member).read())
                    policies.add(path.name[:-5])
                else:
                    raise ValueError('Unexpected bucket policy entry')
            else:
                raise ValueError('MinIO backup contains an unexpected tar root')
    if not (has_manifest and has_data and has_bucket):
        raise ValueError('MinIO backup lacks manifest/data/bucket structure')
    rows = {}
    for line in manifest_body.decode('utf8').splitlines():
        key, separator, value = line.partition('=')
        if not separator or key in rows:
            raise ValueError('Invalid MinIO manifest')
        rows[key] = value
    from minio_metadata import BUCKET
    buckets = rows.get('buckets', '').replace(' ', '').split(',')
    if (not all(BUCKET.fullmatch(b) for b in buckets) or len(set(buckets)) != len(buckets)
            or set(buckets) != bucket_names):
        raise ValueError('Invalid MinIO bucket manifest')
    if 'minio_policy_format' in rows:
        if (rows.get('minio_policy_format') != '1' or len(set(buckets)) != len(buckets)
                or set(buckets) != bucket_names or policies != bucket_names):
            raise ValueError('Policy/bucket inventory mismatch')
    elif policies:
        raise ValueError('Unversioned policy manifest refused')
    # Retention can keep honest legacy archives; new/native-required restore cannot.
    version = rows.get('minio_metadata_format')
    if version is not None or metadata_body is not None:
        from minio_metadata import metadata_value
        if version != '1' or metadata_body is None or rows.get('minio_policy_format') != '1':
            raise ValueError('Missing or unsupported metadata manifest')
        value = metadata_value(metadata_body)
        expected = {(r['bucket'], r['key']): (r['bytes'], r['sha256']) for r in value['objects']}
        if value['buckets'] != sorted(bucket_names) or object_bodies != expected:
            raise ValueError('Archived metadata/body inventory or SHA256 mismatch')
    elif require_metadata:
        raise ValueError('Required object metadata manifest missing')


def validate_archive(descriptor, name, files, kind):
    expected_digest = read_checksum(descriptor, name, files)
    with open_snapshot(descriptor, name, files[name]) as source:
        digest = hashlib.sha256()
        for block in iter(lambda: source.read(CHUNK), b''):
            digest.update(block)
        if digest.hexdigest() != expected_digest:
            raise ValueError(f'Backup checksum mismatch: {name}')
        source.seek(0)
        with gzip.GzipFile(fileobj=source, mode='rb') as stream:
            if kind == 'postgres':
                if stream.read(5) != b'PGDMP':
                    raise ValueError(f'Not a PostgreSQL custom dump: {name}')
            elif kind == 'minio':
                validate_minio(stream)
            else:
                from private_backup import validate_stream
                validate_stream(stream, kind)
            # Tar parsing stops at its end marker; drain gzip as well to verify its CRC/trailer.
            while stream.read(CHUNK):
                pass
        if fingerprint(os.fstat(source.fileno())) != files[name]:
            raise ValueError(f'Backup changed during validation: {name}')
    unchanged(descriptor, name, files[name])
    unchanged(descriptor, name + '.sha256', files[name + '.sha256'])


def prune_backups(kind: str, directory: Path, keep: int = 7, apply: bool = False):
    if kind not in PATTERNS or type(keep) is not int or keep < 2:
        raise ValueError('Unsupported backup kind or keep below 2')
    directory = Path(directory)
    validate_directory(directory, kind)
    descriptor = os.open(directory, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        validate_directory(directory, kind, descriptor)
        files = inventory(descriptor, kind)
        archives = sorted((name for name in files if not name.endswith('.sha256')), reverse=True)
        keepers, older = archives[:keep], archives[keep:]
        for name in keepers:
            validate_archive(descriptor, name, files, kind)
        removable, skipped = [], []
        for name in older:
            try:
                read_checksum(descriptor, name, files)
            except ValueError:
                skipped.append(name)
            else:
                removable.append(name)
        validate_directory(directory, kind, descriptor)
        if inventory(descriptor, kind) != files:
            raise ValueError('Backup set changed during validation; deletion refused')
        if apply:
            for name in removable:
                for target in (name, name + '.sha256'):
                    validate_directory(directory, kind, descriptor)
                    for keeper in keepers:
                        unchanged(descriptor, keeper, files[keeper])
                        unchanged(descriptor, keeper + '.sha256', files[keeper + '.sha256'])
                    unchanged(descriptor, target, files[target])
                    os.unlink(target, dir_fd=descriptor)
                    print(f'Removed {target}')
        return keepers, removable, skipped
    finally:
        os.close(descriptor)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--kind', required=True, choices=tuple(PATTERNS))
    parser.add_argument('--directory', required=True, type=Path)
    parser.add_argument('--keep', type=int, default=7)
    parser.add_argument('--apply', action='store_true', help='Delete old pairs; default is dry-run')
    args = parser.parse_args(argv)
    try:
        keepers, removable, skipped = prune_backups(args.kind, args.directory, args.keep, args.apply)
    except (OSError, EOFError, ValueError, tarfile.TarError) as error:
        print(f'ERROR: retention stopped: {error}', file=sys.stderr)
        return 1
    if not args.apply:
        for name in removable:
            print(f'Would remove {name} and its .sha256 sidecar')
    print(f'{args.kind}: verified keepers={len(keepers)}, '
          f'{"removed" if args.apply else "would remove"} pairs={len(removable)}, '
          f'untouched unpaired/invalid older backups={len(skipped)}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
