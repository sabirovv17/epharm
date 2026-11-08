import os
from pathlib import Path
import re
import subprocess
import textwrap
import unittest


WORKFLOW = Path(__file__).resolve().parents[2] / '.github/workflows/ci.yml'
REQUIRED = (
    'REPO_QUALITY', 'OPS_CONTRACT', 'P0_CONFIG', 'ADMIN', 'STOREFRONT', 'BACKEND',
    'ADMIN_E2E', 'CONTAINERS', 'PIM_ETL', 'POSM_CORE', 'POSM_WINDOWS', 'CODEQL',
)


class MobilePauseGateTest(unittest.TestCase):
    def gate(self, **overrides):
        source = WORKFLOW.read_text()
        gate = source.split('      - name: Require every applicable check\n', 1)[1]
        script = textwrap.dedent(gate.split('        run: |\n', 1)[1])
        env = {**os.environ, **dict.fromkeys(REQUIRED, 'success'),
               'EVENT_NAME': 'pull_request', 'DEPENDENCY_REVIEW': 'success',
               'COMMITLINT': 'success', 'MOBILE_APP_CI_ENABLED': '',
               'MOBILE_ANDROID': 'skipped', 'MOBILE_IOS': 'skipped', **overrides}
        return subprocess.run(['bash', '-e', '-c', script], env=env,
                              capture_output=True, check=False).returncode

    def test_paused_jobs_are_not_run(self):
        source = WORKFLOW.read_text()
        for job in ('mobile-android', 'mobile-ios'):
            section = re.split(r'\n  [a-zA-Z0-9_-]+:\n', source.split(f'  {job}:\n', 1)[1])[0]
            self.assertIn("if: vars.MOBILE_APP_CI_ENABLED == 'true'", section)
        self.assertEqual(0, self.gate())

    def test_every_other_gate_remains_required(self):
        for key in (*REQUIRED, 'DEPENDENCY_REVIEW', 'COMMITLINT'):
            with self.subTest(check=key):
                self.assertNotEqual(0, self.gate(**{key: 'failure'}))

    def test_resume_requires_both_mobile_checks(self):
        self.assertNotEqual(0, self.gate(MOBILE_APP_CI_ENABLED='true'))
        self.assertEqual(0, self.gate(MOBILE_APP_CI_ENABLED='true',
                                   MOBILE_ANDROID='success', MOBILE_IOS='success'))
        self.assertNotEqual(0, self.gate(MOBILE_APP_CI_ENABLED='true',
                                      MOBILE_ANDROID='success', MOBILE_IOS='failure'))

    def test_push_event_keeps_its_existing_contract(self):
        self.assertEqual(0, self.gate(EVENT_NAME='push',
                                   DEPENDENCY_REVIEW='skipped', COMMITLINT='skipped'))


if __name__ == '__main__':
    unittest.main()
