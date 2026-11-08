import importlib.util
from pathlib import Path
import unittest
import tempfile
import stat

spec = importlib.util.spec_from_file_location('rps_fixture', Path(__file__).with_name('build-rps-fixture.py'))
subject = importlib.util.module_from_spec(spec)
spec.loader.exec_module(subject)


class RpsFixtureTest(unittest.TestCase):
    def test_shared_device_ids_have_distinct_pharmacies_keys_and_valid_profiles(self):
        fixture = subject.generate('before', 500)
        devices = fixture['devices']
        self.assertEqual({'KASSA'}, {x['deviceId'] for x in devices})
        for field in ['pharmacyId', 'iin', 'phone', 'key']:
            self.assertEqual(500, len({x[field] for x in devices}))
        for desk in devices:
            digits = list(map(int, desk['iin']))
            checksum = sum(a * b for a, b in zip(digits, range(1, 12))) % 11
            if checksum == 10:
                checksum = sum(a * b for a, b in zip(digits, [3, 4, 5, 6, 7, 8, 9, 10, 11, 1, 2])) % 11
            self.assertNotEqual(10, checksum)
            self.assertEqual(checksum, digits[-1])
        background = fixture['background']
        self.assertNotEqual(background['triggerBarcode'], background['recommendBarcode'])
        for field in ['triggerBarcode', 'recommendBarcode']:
            digits = list(map(int, background[field]))
            self.assertEqual(13, len(digits))
            self.assertEqual(0, (sum(digit * (1 if index % 2 == 0 else 3) for index, digit in enumerate(digits[:12])) + digits[-1]) % 10)

    def test_seed_has_only_hashed_keys_and_checks_exact_database_before_mutations(self):
        fixture = subject.generate('test', 2)
        sql = subject.seed_sql(fixture)
        self.assertLess(sql.index('current_database()'), sql.index('CREATE TABLE'))
        self.assertIn("current_database() <> 'epharm_rps_load_test'", sql)
        self.assertNotIn('DELETE ', sql)
        for desk in fixture['devices']:
            self.assertNotIn(desk['key'], sql)
            self.assertNotIn(desk['password'], sql)
            self.assertIn(subject.hashlib.sha256(desk['key'].encode()).hexdigest(), sql)
        verification = subject.verification_sql(fixture)
        self.assertIn('BEGIN READ ONLY', verification)
        self.assertIn('p.pharmacy_id=e.pharmacy_id', verification)
        self.assertIn("p.status='active'", verification)
        self.assertIn("p.phone_verification_status='self_declared'", verification)
        self.assertIn('financial_rows_unchanged', verification)
        self.assertIn('positive_recommendation_events', verification)
        self.assertIn('INSERT INTO products', sql)
        self.assertIn('INSERT INTO rules', sql)
        self.assertIn('INSERT INTO slides', sql)
        self.assertEqual(2, sql.count('INSERT INTO playlist_pharmacy_assignments'))
        self.assertNotIn('pl_broadcast', sql)
        for field in ['ruleId', 'triggerSku', 'recommendSku', 'playlistId', 'slideId']:
            self.assertIn(fixture['background'][field], sql)

    def test_bounds_and_unique_comparison_mode(self):
        for name, count in [('bad-name', 2), ('x', 0), ('x', 501), ('x' * 33, 1)]:
            with self.assertRaises(ValueError): subject.generate(name, count)
        value = subject.generate('test', 10, unique_device_ids=True)
        self.assertEqual(10, len({x['deviceId'] for x in value['devices']}))

    def test_private_output_is_exclusive_and_owner_only(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'fixture.private.json'
            subject.exclusive(path, 'synthetic-only')
            self.assertEqual(0o600, stat.S_IMODE(path.stat().st_mode))
            with self.assertRaises(FileExistsError): subject.exclusive(path, 'replacement')
            self.assertEqual('synthetic-only', path.read_text())


if __name__ == '__main__':
    unittest.main()
