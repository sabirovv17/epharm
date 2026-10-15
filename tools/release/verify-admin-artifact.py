#!/usr/bin/env python3
"""Verify the reachable promo flow in a stopped admin image and optionally its public bytes."""
import argparse
import hashlib
from pathlib import Path
import re
import stat
import subprocess
import tempfile
from urllib.parse import urlsplit
from urllib.request import build_opener, HTTPRedirectHandler, Request

IMAGE_ID = re.compile(r'sha256:[a-f0-9]{64}\Z')
CONTAINER_ID = re.compile(r'[a-f0-9]{64}\Z')
ENTRY = re.compile(rb'<script\b[^>]*\bsrc=["\'](/assets/index-[A-Za-z0-9_-]+\.js)["\']')
PROMO = re.compile(rb'import\(\s*[`"\']\./(PromoPage-[A-Za-z0-9_-]+\.js)[`"\']\s*\)')
ASSET = re.compile(rb'(?:src|href)=["\'](/assets/[A-Za-z0-9_-]+\.(?:js|css))["\']')
MAX_FILE = 4 * 1024 * 1024


def read_asset(directory: Path, name: str) -> bytes:
    root = directory.resolve(strict=True)
    if directory.is_symlink() or not directory.is_dir() or name.startswith('/') or '..' in Path(name).parts:
        raise ValueError('Unsafe admin artifact path')
    path = directory
    for part in Path(name).parts:
        path = path / part
        if path.is_symlink():
            raise ValueError('Admin artifact contains a symlink')
    if not path.resolve(strict=True).is_relative_to(root) or not stat.S_ISREG(path.stat().st_mode):
        raise ValueError('Admin artifact asset is not a regular file inside the directory')
    if path.stat().st_size > MAX_FILE:
        raise ValueError('Admin artifact asset exceeds size limit')
    with path.open('rb') as source:
        body = source.read(MAX_FILE + 1)
    if len(body) > MAX_FILE:
        raise ValueError('Admin artifact asset exceeds size limit')
    return body


def verify(directory: Path) -> dict[str, bytes]:
    index = read_asset(directory, 'index.html')
    entries = ENTRY.findall(index)
    if len(entries) != 1:
        raise ValueError('Admin index must load exactly one local Vite entry')
    entry = entries[0].decode().lstrip('/')
    entry_body = read_asset(directory, entry)
    promos = PROMO.findall(entry_body)
    if len(promos) != 1:
        raise ValueError('Admin entry must import exactly one reachable PromoPage bundle')
    promo = 'assets/' + promos[0].decode()
    body = read_asset(directory, promo)
    if not re.search(rb'initialRecommendation\s*:\s*\{\s*type\s*:', body):
        raise ValueError('Admin bundle lacks the initial recommendation submit path')
    if not all(re.search(rb'[`"\']' + kind + rb'[`"\']', body)
               for kind in (b'acc_group', b'acc_subgroup', b'acc_mnn')):
        raise ValueError('Admin bundle lacks an ACC trigger kind')
    if b'triggerKind:' not in body or b'triggerValue:' not in body:
        raise ValueError('Admin bundle lacks the scoped trigger payload')
    names = {'index.html', entry, promo}
    names.update(ref.decode().lstrip('/') for ref in ASSET.findall(index))
    return {name: read_asset(directory, name) for name in sorted(names)}


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        return None


def compare_public(origin: str, assets: dict[str, bytes]) -> None:
    uri = urlsplit(origin)
    loopback = uri.hostname in {'localhost', '127.0.0.1', '::1'}
    if (uri.scheme != 'https' and not (uri.scheme == 'http' and loopback)) or not uri.hostname \
            or uri.username or uri.password or uri.path not in ('', '/') or uri.query or uri.fragment:
        raise ValueError('Public URL must be a credential-free HTTPS origin')
    opener = build_opener(NoRedirect())
    base = origin.rstrip('/')
    routes = {**assets, '': assets['index.html'], 'promo': assets['index.html']}
    for name, expected in routes.items():
        with opener.open(Request(f'{base}/{name}', headers={'Cache-Control': 'no-cache'}), timeout=10) as response:
            if response.status != 200:
                raise ValueError(f'Public admin asset unavailable: {name}')
            actual = response.read(MAX_FILE + 1)
        if len(actual) > MAX_FILE or hashlib.sha256(actual).digest() != hashlib.sha256(expected).digest():
            raise ValueError(f'Public admin asset differs from candidate image: {name}')


def verify_image(image: str, public_url: str | None = None) -> None:
    if not IMAGE_ID.fullmatch(image):
        raise ValueError('An exact sha256 Docker image ID is required')
    container = subprocess.check_output(['docker', 'create', '--network', 'none', image], text=True).strip()
    if not CONTAINER_ID.fullmatch(container):
        raise ValueError('Docker did not return a valid container identity')
    try:
        with tempfile.TemporaryDirectory(prefix='epharm-admin-artifact-') as temporary:
            directory = Path(temporary) / 'site'
            subprocess.run(['docker', 'cp', f'{container}:/usr/share/nginx/html', str(directory)], check=True)
            assets = verify(directory)
            if public_url:
                compare_public(public_url, assets)
    finally:
        subprocess.run(['docker', 'rm', container], check=True, stdout=subprocess.DEVNULL)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument('--directory', type=Path)
    source.add_argument('--image')
    parser.add_argument('--public-url', help='Compare candidate assets with the deployed HTTPS origin')
    args = parser.parse_args()
    if args.directory:
        if args.public_url:
            parser.error('--public-url requires --image')
        verify(args.directory)
    else:
        verify_image(args.image, args.public_url)
    print('Admin artifact contract OK')


if __name__ == '__main__':
    main()
