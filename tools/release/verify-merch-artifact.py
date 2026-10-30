#!/usr/bin/env python3
"""Reject a known obsolete bridge in the actual JAR, not only in source/OCI labels."""
import argparse
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
CONTRACT = ROOT / 'admin-panel/backend/src/main/resources/merch-artifact-contract.properties'


def verify(jar_path: Path, contract_path: Path = CONTRACT) -> None:
    rows = [line.split('=', 1) for line in contract_path.read_text().splitlines()
            if line and not line.startswith('#')]
    if len(rows) != 13 or any(len(row) != 2 for row in rows) or len({row[0] for row in rows}) != 13:
        raise ValueError('Invalid merchandising artifact contract')
    with zipfile.ZipFile(jar_path) as jar:
        for name, markers in rows:
            body = jar.read(name)
            for marker in markers.split(','):
                if marker.encode('ascii') not in body:
                    raise ValueError(f'Obsolete merchandising artifact: {name} missing {marker}')


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument('--jar', type=Path)
    source.add_argument('--image', help='Exact candidate Docker image ID (no mutable tags)')
    args = parser.parse_args()
    if args.jar:
        verify(args.jar)
    else:
        if not re.fullmatch(r'sha256:[a-f0-9]{64}', args.image):
            parser.error('An exact sha256 Docker image ID is required')
        # A stopped, network-isolated container cannot run migrations or contact production.
        container = subprocess.check_output(
            ['docker', 'create', '--network', 'none', args.image], text=True).strip()
        if not re.fullmatch(r'[a-f0-9]{64}', container):
            raise ValueError('Docker did not return a valid container identity')
        try:
            with tempfile.TemporaryDirectory(prefix='epharm-merch-artifact-') as directory:
                target = Path(directory) / 'app.jar'
                subprocess.run(['docker', 'cp', f'{container}:/app/app.jar', str(target)], check=True)
                verify(target)
        finally:
            subprocess.run(['docker', 'rm', container], check=True, stdout=subprocess.DEVNULL)
    print('Merchandising artifact contract OK')


if __name__ == '__main__':
    main()
