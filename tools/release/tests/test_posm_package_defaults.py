import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location(
    'posm_packages', Path(__file__).resolve().parents[2] / 'build-posm-pharmacy-packages.py')
packages = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(packages)


class PosmPackageDefaultsTests(unittest.TestCase):
    def test_orders_disabled_without_disabling_recommendations_screen_or_updater(self):
        config = packages.build_config('synthetic-pharmacy', 'synthetic-device', 'synthetic-token')
        self.assertIs(config['fulfillmentEnabled'], False)
        for key in ('enabled', 'videoEnabled', 'updateEnabled'):
            self.assertIs(config[key], True)
        self.assertEqual(config['backendBaseUrl'], 'https://epharm.inkar.kz')
        self.assertEqual(config['pharmacyId'], 'synthetic-pharmacy')
        self.assertEqual(config['deviceKey'], 'synthetic-token')


if __name__ == '__main__':
    unittest.main()
