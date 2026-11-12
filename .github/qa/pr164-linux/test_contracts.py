"""Offline trust-boundary checks. No Docker, network, VM or application execution."""
import json
import types
import unittest

from jwt_workload import JwtAcceptance, literal, memory_mib, overview_valid, prometheus_values, token_shape, validate_fixture


class FakeQa:
    BACKEND = 'a' * 64
    LABEL = 'epharm.isolated-qa.owner'
    OWNER = 'pr164-' + 'b' * 12

    def __init__(self, status=200, body=None):
        self.status = status
        self.body = body or {'id': 'synthetic'}
        self.calls = []

    def inspect(self, identifier):
        assert identifier == self.BACKEND
        return {'Config': {'Labels': {self.LABEL: self.OWNER}}}

    def docker(self, *args, input=None):
        self.calls.append((args, input))
        return types.SimpleNamespace(stdout=json.dumps(self.body) + '\n' + str(self.status))


class Contracts(unittest.TestCase):
    def fixture(self):
        return {'kind': 'epharm-isolated-learner-jwt-v1', 'runId': 'pr164_' + 'a' * 10,
                'expectedDatabase': 'epharm_rps_load_pr164_' + 'a' * 10,
                'accounts': [{'id': 'ph_' + str(i), 'token': 'header' + str(i) + '.payload.signature', 'expectedAssignments': 0} for i in range(500)]}

    def test_distinct_500_accounts_required(self):
        value = self.fixture()
        self.assertIs(validate_fixture(value), value)
        value['accounts'][1]['token'] = value['accounts'][0]['token']
        with self.assertRaises(AssertionError):
            validate_fixture(value)

    def test_wrong_database_and_real_identity_shape_refused(self):
        value = self.fixture()
        value['expectedDatabase'] = 'epharm'
        with self.assertRaises(AssertionError):
            validate_fixture(value)
        self.assertFalse(token_shape('header.payload.signature\nInjected: header'))
        self.assertFalse(token_shape('not-a-bearer-token'))

    def test_http_is_loopback_in_owned_container_and_token_only_on_stdin(self):
        fake = FakeQa()
        api = JwtAcceptance(fake, '.')
        api.request('/api/mobile/auth/me', token='header.payload.signature')
        args, stdin = fake.calls[0]
        self.assertEqual(args, ('exec', '-i', fake.BACKEND, 'curl', '--config', '-'))
        self.assertNotIn('header.payload.signature', repr(args))
        self.assertIn('http://127.0.0.1:8080/api/mobile/auth/me', stdin)
        self.assertNotIn('location', stdin)
        with self.assertRaises(AssertionError):
            api.request('https://epharm.inkar.kz/api/mobile/auth/me', token='header.payload.signature')
        self.assertEqual(len(fake.calls), 1)

    def test_definitive_status_and_error_code_are_required(self):
        api = JwtAcceptance(FakeQa(403, {'code': 'USER_NOT_ACTIVE'}), '.')
        api.request('/api/mobile/training', token='header.payload.signature', expected=403, code='USER_NOT_ACTIVE')
        with self.assertRaises(AssertionError):
            api.request('/api/mobile/training', token='header.payload.signature', expected=200)
        with self.assertRaises(AssertionError):
            api.request('/api/mobile/training', token='header.payload.signature', expected=403, code='USER_NOT_FOUND')

    def test_semantic_overview_requires_complete_expected_lists(self):
        value = {'total': 0, 'assignments': [], 'certificates': [], 'notifications': []}
        self.assertTrue(overview_valid(value, 0))
        self.assertFalse(overview_valid(value, 1))
        self.assertFalse(overview_valid({'total': 0, 'assignments': []}, 0))
        self.assertEqual(literal("x'y"), "'x''y'")

    def test_real_pool_and_resource_metrics_parse_without_labels_as_data(self):
        self.assertEqual(memory_mib('1.5GiB'), 1536)
        self.assertEqual(memory_mib('512MiB'), 512)
        self.assertIsNone(memory_mib('unknown'))
        metrics = 'hikaricp_connections_timeout_total{pool="HikariPool-1"} 0\nhikaricp_connections_acquire_seconds_count{pool="HikariPool-1"} 2500\n'
        self.assertEqual(prometheus_values(metrics, 'hikaricp_connections_timeout_total'), [0])
        self.assertEqual(prometheus_values(metrics, 'hikaricp_connections_acquire_seconds_count'), [2500])


if __name__ == '__main__':
    unittest.main()
