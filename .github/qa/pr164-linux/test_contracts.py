"""Offline trust-boundary checks. No Docker, network, VM or application execution."""
import json
import copy
import hashlib
import os
import pathlib
import tempfile
import types
import unittest
from unittest import mock

from jwt_workload import JwtAcceptance, literal, memory_mib, overview_valid, prometheus_values, token_shape, validate_fixture
from paired_registration import PairedRegistration, canonical_schema, checked_snapshot, numeric_cpu_model, validate_reference


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


class OwnedFixtureQa:
    OWNER = 'pr164-' + 'a' * 12
    LABEL = 'epharm.isolated-qa.owner'
    PG, REDIS, NET = '1' * 64, '2' * 64, '3' * 64
    DATABASE = 'epharm_rps_load_pr164_' + 'b' * 10

    def __init__(self, root):
        self.ROOT = pathlib.Path(root)
        self.BACKEND = '4' * 64
        self.OWNED = [self.PG, self.REDIS, self.BACKEND]
        self.REPORT = {'stage': 'compat-old-before', 'phases': {}, 'phaseDiagnostics': {}}
        self.rows, self.redis_keys, self.calls = 0, 0, []
        self.network = {'Id': self.NET, 'Name': self.OWNER + '-net', 'Internal': True, 'Labels': {self.LABEL: self.OWNER}, 'Containers': {x: {} for x in self.OWNED}}
        self.items = {identifier: {'Id': identifier, 'Config': {'Labels': {self.LABEL: self.OWNER}},
                                  'HostConfig': {'PortBindings': {}}, 'NetworkSettings': {'Networks': {'own': {'NetworkID': self.NET}}}} for identifier in self.OWNED}
        (self.ROOT / 'fixture').mkdir()
        (self.ROOT / 'results').mkdir()
        (self.ROOT / 'fixture/fixture.private.json').write_text(json.dumps({'expectedDatabase': self.DATABASE, 'devices': [{}] * 500}))
        (self.ROOT / 'fixture/verify.sql').write_text('-- OFFLINE_ORIGINAL_VERIFY\n')
        self.verify_ok = True

    def inspect(self, identifier):
        return self.items[identifier]

    def sql(self, text):
        self.calls.append(('sql', text))
        if text == 'SELECT current_database();':
            return self.DATABASE
        if 'json_agg(tablename' in text:
            return json.dumps(['pharmacists', 'posm_device_staff_sessions'])
        if 'jsonb_object_agg' in text:
            return json.dumps({'pharmacists': [{}] * self.rows, 'posm_device_staff_sessions': []})
        if 'pg_sequences' in text:
            return '[]'
        if text.startswith('DO $$') and 'SYNTHETIC_DUMP' in text:
            self.rows = 0
            return ''
        if text == '-- OFFLINE_ORIGINAL_VERIFY\n':
            if not self.verify_ok:
                raise RuntimeError('Offline reference SQL verification refused')
            return '500 profiles verified'
        raise AssertionError('Unexpected offline SQL fixture call')

    def schema(self):
        return 'schema99'

    def write(self, name, value):
        (self.ROOT / name).write_text(json.dumps(value))

    def docker(self, *args, **kwargs):
        self.calls.append(('docker', args))
        if args[:2] == ('network', 'inspect'):
            value = json.dumps([self.network])
        elif 'pg_dump' in args:
            value = '\\restrict abc123\nCREATE TABLE synthetic();\n\\unrestrict abc123\n' if '--schema-only' in args else '-- SYNTHETIC_DUMP\n'
        elif args[-1] == 'keyspace':
            value = '# Keyspace\ndb0:keys=' + str(self.redis_keys) + ',expires=0\n'
        elif args[-1] == 'DBSIZE':
            value = str(self.redis_keys)
        elif args[-2:] == ('FLUSHDB', 'SYNC'):
            self.redis_keys = 0
            value = 'OK'
        else:
            raise AssertionError('Unexpected offline Docker fixture call')
        return types.SimpleNamespace(stdout=value)


class PairedContracts(unittest.TestCase):
    def reference_summary(self, qa, latency_failure=False):
        metrics = {name: {'values': {'count': 0}, 'thresholds': {'count==0': {'ok': True}}} for name in
                   ('rps_errors', 'rps_rate_limited', 'rps_5xx', 'rps_transport_errors', 'rps_unexpected_4xx', 'rps_unexpected_redirects', 'dropped_iterations')}
        metrics.update(rps_registration_created={'values': {'count': 500}}, rps_completed={'values': {'count': 500}},
                       checks={'values': {'fails': 0, 'passes': 8902, 'rate': 1}}, http_req_failed={'values': {'rate': 0}})
        metrics['http_req_duration{workload:rps-register}'] = {'thresholds': {'p(95)<2000': {'ok': not latency_failure}, 'p(99)<10000': {'ok': True}}}
        return {'mode': 'register', 'fixture': {'devices': 500, 'expectedDatabase': qa.DATABASE}, 'metrics': metrics}

    def test_reference_only_completed_error_free_registration_latency_refusal_allowed(self):
        qa = types.SimpleNamespace(DATABASE='synthetic')
        value = self.reference_summary(qa, True)
        self.assertEqual(validate_reference(value, {'k6ExitCode': 99}, 'AssertionError')['failedRegistrationLatencyGates'], ['p(95)<2000'])
        for change in (
            lambda x: x['metrics']['rps_completed']['values'].update(count=499),
            lambda x: x['metrics']['rps_registration_created']['values'].update(count=499),
            lambda x: x['metrics']['rps_5xx']['values'].update(count=1),
            lambda x: x['metrics']['http_req_failed']['values'].update(rate=0.001),
            lambda x: x['metrics']['checks']['values'].update(fails=1),
            lambda x: x['metrics']['dropped_iterations']['values'].update(count=1),
            lambda x: x['metrics']['rps_errors']['thresholds']['count==0'].update(ok=False),
        ):
            invalid = copy.deepcopy(value)
            change(invalid)
            with self.assertRaises(AssertionError):
                validate_reference(invalid, {'k6ExitCode': 99}, 'AssertionError')
        with self.assertRaises(AssertionError):
            validate_reference(self.reference_summary(qa), {'k6ExitCode': 0}, 'AssertionError')

    def test_foreign_owner_network_database_and_ports_refused_before_mutation(self):
        with tempfile.TemporaryDirectory() as root:
            good = OwnedFixtureQa(root)
            PairedRegistration(good).guard()
            for change in (
                lambda q: q.items[q.PG]['Config']['Labels'].update({q.LABEL: 'foreign'}),
                lambda q: q.items[q.REDIS]['NetworkSettings']['Networks']['own'].update(NetworkID='9' * 64),
                lambda q: q.items[q.REDIS]['HostConfig'].update(PortBindings={'6379/tcp': []}),
                lambda q: q.network.update(Internal=False),
                lambda q: setattr(q, 'DATABASE', 'epharm'),
            ):
                q = copy.deepcopy(good)
                q.calls = []
                change(q)
                with self.assertRaises(AssertionError):
                    PairedRegistration(q).reset_redis()
                self.assertFalse(any('FLUSHDB' in repr(call) or 'pg_dump' in repr(call) for call in q.calls))

    def test_snapshot_hash_mode_symlink_and_hardlink_refused(self):
        with tempfile.TemporaryDirectory() as root:
            p = pathlib.Path(root) / 'snapshot.private.sql'
            p.write_text('synthetic only')
            p.chmod(0o600)
            digest = hashlib.sha256(p.read_bytes()).hexdigest()
            self.assertEqual(checked_snapshot(p, digest), 'synthetic only')
            with self.assertRaises(AssertionError):
                checked_snapshot(p, '0' * 64)
            p.chmod(0o644)
            with self.assertRaises(AssertionError):
                checked_snapshot(p, digest)
            p.chmod(0o600)
            link = pathlib.Path(root) / 'link'
            link.symlink_to(p)
            with self.assertRaises(AssertionError):
                checked_snapshot(link, digest)
            link.unlink()
            os.link(p, link)
            with self.assertRaises(AssertionError):
                checked_snapshot(p, digest)

    def test_one_reference_pass_or_fail_restores_fresh_candidate_without_retries(self):
        for exit_code in (0, 99):
            with tempfile.TemporaryDirectory() as root:
                q = OwnedFixtureQa(root)
                paired = PairedRegistration(q)
                calls = []
                def phase(mode):
                    calls.append(mode)
                    self.assertEqual(q.rows, 0)
                    q.rows, q.redis_keys = 500, 7
                    q.REPORT['phaseDiagnostics']['register'] = {'k6ExitCode': exit_code, 'backendState': {'OOMKilled': False, 'Running': True}}
                    if exit_code == 0:
                        q.REPORT['phases']['register'] = {'outcome': 'PASS'}
                    (q.ROOT / 'results/rps-register-offline.json').write_text(json.dumps(self.reference_summary(q, exit_code == 99)))
                    if exit_code:
                        raise AssertionError('Original fixed threshold rejected reference')
                def stop():
                    q.BACKEND = None
                with mock.patch('paired_registration.host_cpu_model', return_value={'family': 6, 'model': 143, 'stepping': 8, 'hostLogicalProcessors': 4}):
                    paired.run(phase, stop)
                self.assertTrue(paired.completed)
                self.assertEqual(calls, ['register'])
                self.assertEqual(q.REPORT['pairedRegistration15']['outcome'], 'PASS' if exit_code == 0 else 'FAIL')
                self.assertEqual((q.rows, q.redis_keys), (0, 0))
                self.assertEqual(q.REPORT['phases'], {})
                self.assertEqual(q.REPORT['pairedRegistration15']['sqlVerification'], 'PASS')
                verify_position = next(i for i, call in enumerate(q.calls) if call == ('sql', '-- OFFLINE_ORIGINAL_VERIFY\n'))
                restore_position = next(i for i, call in enumerate(q.calls) if call[0] == 'sql' and call[1].startswith('DO $$') and 'SYNTHETIC_DUMP' in call[1])
                self.assertLess(verify_position, restore_position)
                self.assertFalse(list((q.ROOT / 'results').glob('rps-register-*.json')))
                self.assertTrue((q.ROOT / 'results/rps-reference15-register-offline.json').is_file())
                with self.assertRaises(AssertionError):
                    paired.run(phase, stop)

    def test_reference_sql_failure_aborts_before_restore_or_candidate(self):
        with tempfile.TemporaryDirectory() as root:
            q = OwnedFixtureQa(root)
            q.verify_ok = False
            paired = PairedRegistration(q)
            def phase(mode):
                q.rows = 500
                q.REPORT['phaseDiagnostics']['register'] = {'k6ExitCode': 99, 'backendState': {'OOMKilled': False, 'Running': True}}
                (q.ROOT / 'results/rps-register-offline.json').write_text(json.dumps(self.reference_summary(q, True)))
                raise AssertionError('Registration latency refusal')
            def stop():
                q.BACKEND = None
            with mock.patch('paired_registration.host_cpu_model', return_value={}), self.assertRaises(RuntimeError):
                paired.run(phase, stop)
            self.assertFalse(paired.completed)
            self.assertFalse(any(call[0] == 'sql' and call[1].startswith('DO $$') for call in q.calls))
            self.assertFalse(any('FLUSHDB' in repr(call) for call in q.calls))

    def test_dump_nonce_is_not_schema_but_schema_changes_remain_visible(self):
        self.assertEqual(canonical_schema('\\restrict abc\nCREATE TABLE t();\n\\unrestrict abc'), canonical_schema('\\restrict xyz\nCREATE TABLE t();\n\\unrestrict xyz'))
        self.assertNotEqual(canonical_schema('CREATE TABLE t();'), canonical_schema('CREATE TABLE changed();'))
        self.assertEqual(numeric_cpu_model('cpu family : 6\nmodel : 143\nstepping : 8\n\n'), {'family': 6, 'model': 143, 'stepping': 8})


if __name__ == '__main__':
    unittest.main()
