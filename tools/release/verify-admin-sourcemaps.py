#!/usr/bin/env python3
"""Bind private source maps to the exact static files packaged by release CI."""
import argparse
import hashlib
import json
from pathlib import Path
import re


DEBUG_ID = re.compile(rb'(?m)^//# debugId=([a-f0-9-]{36})\s*$')
UUID = re.compile(r'[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}\Z')


def read_files(directory: Path) -> dict[str, bytes]:
    if directory.is_symlink() or not directory.is_dir():
        raise ValueError('Expected a regular frontend build directory')
    files = {}
    total = 0
    for path in sorted(directory.rglob('*')):
        if path.is_symlink():
            raise ValueError('Build contains a symlink')
        if path.is_dir():
            continue
        size = path.stat().st_size
        total += size
        if not path.is_file() or size > 64 * 1024 * 1024 or total > 256 * 1024 * 1024 or len(files) >= 1024:
            raise ValueError('Build exceeds regular-file limits')
        files[path.relative_to(directory).as_posix()] = path.read_bytes()
    return files


def verify(directory: Path) -> dict:
    files = read_files(directory)
    if not files.get('index.html'):
        raise ValueError('Missing frontend entry')
    scripts = {name: body for name, body in files.items() if name.endswith('.js')}
    if not scripts:
        raise ValueError('Missing JavaScript bundles')
    debug_ids = {}
    for name, body in scripts.items():
        ids = DEBUG_ID.findall(body)
        if len(ids) != 1 or not UUID.fullmatch(ids[0].decode()):
            raise ValueError('Bundle must contain exactly one Debug ID')
        map_body = files.get(name + '.map')
        if not map_body:
            raise ValueError('Bundle has no private source map')
        source_map = json.loads(map_body)
        if source_map.get('version') != 3 or source_map.get('debug_id') != ids[0].decode():
            raise ValueError('Bundle and source map Debug IDs differ')
        sources = source_map.get('sources', [])
        content = source_map.get('sourcesContent', [])
        if not sources or len(sources) != len(content) or not any(isinstance(x, str) and x for x in content):
            raise ValueError('Private source map has no original sources')
        debug_ids[name] = ids[0].decode()
    return {
        'files': {name: hashlib.sha256(body).hexdigest() for name, body in files.items()},
        'debugIds': debug_ids,
    }


def verify_runtime(directory: Path, proof: dict, release: str, commit: str):
    files = read_files(directory)
    if json.loads(files.pop('release.json')) != {'releaseId': release, 'commit': commit}:
        raise ValueError('Runtime release identity differs from the uploaded build')
    actual = {name: hashlib.sha256(body).hexdigest() for name, body in files.items()}
    expected = {name: digest for name, digest in proof['files'].items() if not name.endswith('.map')}
    if actual != expected:
        raise ValueError('Runtime differs from uploaded files or contains private source maps')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--write', type=Path)
    mode.add_argument('--compare', type=Path)
    args = parser.parse_args()
    proof = verify(args.directory)
    if args.write:
        with args.write.open('x') as output:
            json.dump(proof, output, sort_keys=True)
            output.write('\n')
    elif json.loads(args.compare.read_text()) != proof:
        raise ValueError('Uploaded assets changed before runtime packaging')
    print(f'Verified {len(proof["debugIds"])} JavaScript/source-map pairs')


if __name__ == '__main__':
    main()
