import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('merch_artifact', ROOT / 'tools/release/verify-merch-artifact.py')
artifact = importlib.util.module_from_spec(spec)
spec.loader.exec_module(artifact)


class MerchArtifactTests(unittest.TestCase):
    def test_current_artifact_and_missing_class_configuration_and_fix(self):
        rows = [row.split('=', 1) for row in artifact.CONTRACT.read_text().splitlines()
                if row and not row.startswith('#')]
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / 'app.jar'
            def write(omit=None, marker=None):
                with zipfile.ZipFile(jar, 'w') as archive:
                    for name, markers in rows:
                        if name != omit:
                            archive.writestr(name, ','.join(m for m in markers.split(',') if m != marker))
            write()
            artifact.verify(jar)
            for name, _ in rows:
                write(omit=name)
                with self.assertRaises(KeyError):
                    artifact.verify(jar)
            for marker in ('writeValueAsBytes', 'IPV4_OCTET', 'MERCH_TASKS_STAFF_URL'):
                write(marker=marker)
                with self.assertRaises(ValueError):
                    artifact.verify(jar)


if __name__ == '__main__':
    unittest.main()
