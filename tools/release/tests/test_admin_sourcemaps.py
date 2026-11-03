import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / 'verify-admin-sourcemaps.py'
spec = importlib.util.spec_from_file_location('admin_sourcemaps', SCRIPT)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
DEBUG_ID = 'ab10c570-de86-5767-b7ef-277cfc1462b7'


class AdminSourcemapsTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.dist = self.root / 'dist'
        self.dist.mkdir()
        (self.dist / 'index.html').write_text('<script src="/assets/main.js"></script>')
        (self.dist / 'main.js').write_text(f'throw Error("synthetic");\n//# debugId={DEBUG_ID}\n')
        self.source_map = {'version': 3, 'debug_id': DEBUG_ID, 'sources': ['src/main.ts'],
                           'sourcesContent': ['throw Error("synthetic")'], 'mappings': 'AAAA'}
        self.map = self.dist / 'main.js.map'
        self.map.write_text(json.dumps(self.source_map))

    def test_proof_binds_javascript_maps_and_runtime_entry(self):
        proof = module.verify(self.dist)
        self.assertEqual(proof['debugIds'], {'main.js': DEBUG_ID})
        self.assertEqual(set(proof['files']), {'index.html', 'main.js', 'main.js.map'})
        self.assertTrue(all(len(value) == 64 for value in proof['files'].values()))

    def test_missing_map_or_missing_original_sources_rejected(self):
        self.map.unlink()
        with self.assertRaises(ValueError):
            module.verify(self.dist)
        self.source_map['sourcesContent'] = []
        self.map.write_text(json.dumps(self.source_map))
        with self.assertRaises(ValueError):
            module.verify(self.dist)

    def test_different_map_debug_id_rejected(self):
        self.source_map['debug_id'] = 'c' * 36
        self.map.write_text(json.dumps(self.source_map))
        with self.assertRaises(ValueError):
            module.verify(self.dist)

    def test_missing_or_duplicate_javascript_debug_id_rejected(self):
        for body in ('throw Error()', f'//# debugId={DEBUG_ID}\n//# debugId={DEBUG_ID}\n'):
            (self.dist / 'main.js').write_text(body)
            with self.assertRaises(ValueError):
                module.verify(self.dist)

    def test_symlink_cannot_copy_an_unrelated_file_into_upload(self):
        (self.dist / 'unrelated.js').symlink_to(self.root / 'private')
        with self.assertRaises(ValueError):
            module.verify(self.dist)

    def test_post_upload_mutation_is_detected_before_packaging(self):
        proof = self.root / 'proof.json'
        def run(mode):
            return subprocess.run([sys.executable, str(SCRIPT), str(self.dist), mode, str(proof)],
                                  capture_output=True, text=True, timeout=10)
        self.assertEqual(run('--write').returncode, 0)
        self.assertEqual(run('--compare').returncode, 0)
        (self.dist / 'index.html').write_text('changed runtime entry')
        self.assertNotEqual(run('--compare').returncode, 0)

    def test_runtime_must_match_every_uploaded_byte_and_exclude_maps(self):
        proof = module.verify(self.dist)
        (self.dist / 'release.json').write_text(json.dumps({'releaseId': 'v0.2.5', 'commit': 'a' * 40}))
        with self.assertRaises(ValueError):
            module.verify_runtime(self.dist, proof, 'v0.2.5', 'a' * 40)
        self.map.unlink()
        module.verify_runtime(self.dist, proof, 'v0.2.5', 'a' * 40)
        with self.assertRaises(ValueError):
            module.verify_runtime(self.dist, proof, 'v0.2.5', 'b' * 40)
        (self.dist / 'main.js').write_text('different code')
        with self.assertRaises(ValueError):
            module.verify_runtime(self.dist, proof, 'v0.2.5', 'a' * 40)


if __name__ == '__main__':
    unittest.main()
