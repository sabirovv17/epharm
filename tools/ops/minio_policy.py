#!/usr/bin/env python3
"""Validate captured anonymous bucket policies and exact isolated restore results."""
import argparse
import gzip
import importlib.util
import json
from pathlib import Path
import re
import sys
import tarfile


def policy_value(body):
    if not 0 < len(body) <= 1024 * 1024:
        raise ValueError('Invalid policy size')
    def pairs(rows):
        result = {}
        for key, value in rows:
            if key in result:
                raise ValueError('Duplicate policy key')
            result[key] = value
        return result
    def constant(_):
        raise ValueError('Invalid JSON constant')
    value = json.loads(body, object_pairs_hook=pairs, parse_constant=constant)
    if not isinstance(value, dict) or (value and (not isinstance(value.get('Statement'), list)
                                                or not isinstance(value.get('Version'), str))):
        raise ValueError('Invalid S3 policy object')
    # MinIO serializes these IAM sets from Go maps; their array order is not stable.
    for statement in value.get('Statement', []):
        if not isinstance(statement, dict):
            raise ValueError('Invalid S3 policy statement')
        for key in ['Action', 'NotAction', 'Resource', 'NotResource']:
            if isinstance(statement.get(key), list):
                if any(not isinstance(item, str) for item in statement[key]):
                    raise ValueError('Invalid IAM string array')
                statement[key] = sorted(statement[key])
    return value


def validate_stage(stage, actual=None, allow_legacy=False):
    stage = Path(stage)
    rows = {}
    for line in (stage / 'manifest.txt').read_text().splitlines():
        key, separator, value = line.partition('=')
        if not separator or key in rows:
            raise ValueError('Invalid MinIO manifest')
        rows[key] = value
    if rows.get('minio_policy_format') != '1':
        if allow_legacy and 'minio_policy_format' not in rows:
            return {'policyFormat': 'legacy_not_captured', 'policies': 0}
        raise ValueError('Snapshot lacks mandatory bucket policies')
    buckets = rows.get('buckets', '').replace(' ', '').split(',')
    if not buckets or len(set(buckets)) != len(buckets) or any(not re.fullmatch(r'[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]', b) for b in buckets):
        raise ValueError('Invalid policy bucket inventory')
    for base in [stage / 'policies'] + ([Path(actual)] if actual else []):
        if base.is_symlink() or not base.is_dir() or set(p.name for p in base.iterdir()) != {b + '.json' for b in buckets}:
            raise ValueError('Policy file inventory differs')
    for bucket in buckets:
        source = stage / 'policies' / (bucket + '.json')
        if source.is_symlink() or not source.is_file():
            raise ValueError('Policy is not a regular file')
        value = policy_value(source.read_bytes())
        if actual:
            restored = Path(actual) / source.name
            if restored.is_symlink() or not restored.is_file() or policy_value(restored.read_bytes()) != value:
                raise ValueError('Restored policy differs from its source; no additional grant accepted')
    return {'policyFormat': 1, 'policies': len(buckets), 'exactRestoredMatch': actual is not None}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--stage', type=Path)
    parser.add_argument('--actual', type=Path)
    parser.add_argument('--archive', type=Path)
    parser.add_argument('--allow-legacy', action='store_true')
    args = parser.parse_args()
    try:
        if args.archive:
            spec = importlib.util.spec_from_file_location('minio_retention_validation', Path(__file__).with_name('prune-backups.py'))
            module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
            with gzip.open(args.archive, 'rb') as stream:
                module.validate_minio(stream, require_metadata=not args.allow_legacy)
                while stream.read(1024 * 1024):
                    pass
        elif args.stage:
            print(json.dumps(validate_stage(args.stage, args.actual, args.allow_legacy)))
            from minio_metadata import validate_stage as validate_metadata
            metadata = validate_metadata(args.stage, args.allow_legacy)
            print(json.dumps({'metadataFormat': 1 if metadata else 'legacy_not_captured', 'objects': metadata['objectCount'] if metadata else None}))
        else:
            raise ValueError('Archive or stage is required')
    except (OSError, ValueError, EOFError, TypeError, KeyError, tarfile.TarError) as error:
        print(f'ERROR: bucket policy validation refused ({type(error).__name__})', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
