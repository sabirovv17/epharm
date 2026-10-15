import importlib.util
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
import subprocess
import tempfile
import threading
import unittest
from unittest.mock import patch
from urllib.error import HTTPError

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('admin_artifact', ROOT / 'tools/release/verify-admin-artifact.py')
artifact = importlib.util.module_from_spec(spec)
spec.loader.exec_module(artifact)

ENTRY = 'index-good123.js'
PROMO = 'PromoPage-good123.js'
INDEX = (f'<script type="module" src="/assets/{ENTRY}"></script>'
         '<link rel="stylesheet" href="/assets/index-good123.css">').encode()
ENTRY_BODY = f'const page=()=>import(`./{PROMO}`)'.encode()
PROMO_BODY = (b'initialRecommendation:{type:t,trigger:ref},'
              b'[`product`,`acc_group`,`acc_subgroup`,`acc_mnn`],'
              b'triggerKind:kind,triggerValue:value')


def make_site(root: Path, *, index=INDEX, entry=ENTRY_BODY, promo=PROMO_BODY) -> None:
    (root / 'assets').mkdir()
    (root / 'index.html').write_bytes(index)
    (root / 'assets' / ENTRY).write_bytes(entry)
    (root / 'assets' / PROMO).write_bytes(promo)
    (root / 'assets' / 'index-good123.css').write_text('body{color:black}')


class AdminArtifactTests(unittest.TestCase):
    def test_valid_reachable_bundle_and_old_product_only_bundle(self):
        with tempfile.TemporaryDirectory() as temporary:
            site = Path(temporary)
            make_site(site)
            self.assertEqual(set(artifact.verify(site)), {
                'index.html', f'assets/{ENTRY}', f'assets/{PROMO}', 'assets/index-good123.css',
            })
            for broken in (
                PROMO_BODY.replace(b'initialRecommendation:', b'oldCampaign:'),
                PROMO_BODY.replace(b'`acc_subgroup`', b'`product_only`'),
                PROMO_BODY.replace(b'triggerValue:', b'oldTriggerValue:'),
            ):
                (site / 'assets' / PROMO).write_bytes(broken)
                with self.assertRaises(ValueError):
                    artifact.verify(site)

    def test_fake_or_external_entry_and_missing_promo_import_fail_closed(self):
        with tempfile.TemporaryDirectory() as temporary:
            site = Path(temporary)
            make_site(site)
            (site / 'index.html').write_bytes(b'<script src="https://other.example/index.js"></script>')
            with self.assertRaises(ValueError):
                artifact.verify(site)
            (site / 'index.html').write_bytes(INDEX)
            (site / 'assets' / ENTRY).write_bytes(b'const old=()=>import(`./OldPromo.js`)')
            with self.assertRaises(ValueError):
                artifact.verify(site)
            (site / 'index.html').write_bytes(b'<script src="/assets/../index-good123.js"></script>')
            with self.assertRaises(ValueError):
                artifact.verify(site)

    def test_referenced_symlink_and_large_asset_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            site = Path(temporary)
            make_site(site)
            css = site / 'assets' / 'index-good123.css'
            css.unlink()
            css.symlink_to(Path(temporary).parent / 'outside.css')
            with self.assertRaises(ValueError):
                artifact.verify(site)
            css.unlink()
            css.write_bytes(b'x' * (artifact.MAX_FILE + 1))
            with self.assertRaises(ValueError):
                artifact.verify(site)

    def test_bad_image_identity_never_calls_docker_and_cp_failure_removes_container(self):
        with patch.object(artifact.subprocess, 'check_output') as create:
            with self.assertRaises(ValueError):
                artifact.verify_image('epharm/frontend:latest')
            create.assert_not_called()
        container = 'a' * 64
        with patch.object(artifact.subprocess, 'check_output', return_value=container), \
                patch.object(artifact.subprocess, 'run') as run:
            run.side_effect = [subprocess.CalledProcessError(1, ['docker', 'cp']), None]
            with self.assertRaises(subprocess.CalledProcessError):
                artifact.verify_image('sha256:' + 'b' * 64)
            self.assertEqual(run.call_args_list[0].args[0][:2], ['docker', 'cp'])
            self.assertEqual(run.call_args_list[1].args[0], ['docker', 'rm', container])

    def test_public_comparison_rejects_changed_bytes_and_redirects(self):
        with tempfile.TemporaryDirectory() as temporary:
            site = Path(temporary)
            make_site(site)
            assets = artifact.verify(site)

            class Handler(BaseHTTPRequestHandler):
                redirect = False
                stale_promo = False

                def do_GET(self):
                    if self.path == '/index.html' and Handler.redirect:
                        self.send_response(302)
                        self.send_header('Location', f'/assets/{ENTRY}')
                        self.end_headers()
                        return
                    name = self.path.lstrip('/')
                    body = assets['index.html'] if name in ('', 'promo') else assets.get(name)
                    if name == 'promo' and Handler.stale_promo:
                        body = b'old product-only shell'
                    if body is None:
                        self.send_error(404)
                        return
                    self.send_response(200)
                    self.end_headers()
                    self.wfile.write(body)

                def log_message(self, *_):
                    pass

            server = HTTPServer(('127.0.0.1', 0), Handler)
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                url = f'http://127.0.0.1:{server.server_port}'
                artifact.compare_public(url, assets)
                Handler.stale_promo = True
                with self.assertRaises(ValueError):
                    artifact.compare_public(url, assets)
                Handler.stale_promo = False
                with self.assertRaises(ValueError):
                    artifact.compare_public(url, {**assets, 'index.html': b'old index'})
                with self.assertRaises(ValueError):
                    artifact.compare_public('https://user:password@example.com', assets)
                with self.assertRaises(ValueError):
                    artifact.compare_public('http://example.com', assets)
                Handler.redirect = True
                with self.assertRaises(HTTPError) as redirected:
                    artifact.compare_public(url, assets)
                redirected.exception.close()
            finally:
                server.shutdown()
                server.server_close()


if __name__ == '__main__':
    unittest.main()
