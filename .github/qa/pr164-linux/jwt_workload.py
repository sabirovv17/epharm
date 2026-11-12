"""Private synthetic learner acceptance; every token comes from the real login API."""
import concurrent.futures
import hashlib
import importlib.util
import json
import os
import pathlib
import re
import secrets
import time
import uuid


def literal(value):
    assert isinstance(value, str) and len(value) <= 256
    return "'" + value.replace("'", "''") + "'"


def token_shape(value):
    return isinstance(value, str) and len(value) <= 8192 and re.fullmatch(r'[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+', value) is not None


def overview_valid(body, expected):
    return isinstance(body, dict) and body.get('total') == expected and isinstance(body.get('assignments'), list) and len(body['assignments']) == expected and isinstance(body.get('certificates'), list) and isinstance(body.get('notifications'), list)


def memory_mib(raw):
    match = re.fullmatch(r'([0-9.]+)([kMGT]?i?B)', raw.strip())
    factors = {'B': 1 / 1024**2, 'kB': 1000 / 1024**2, 'KiB': 1 / 1024, 'MB': 1000**2 / 1024**2, 'MiB': 1, 'GB': 1000**3 / 1024**2, 'GiB': 1024, 'TB': 1000**4 / 1024**2, 'TiB': 1024**2}
    return float(match[1]) * factors[match[2]] if match else None


def prometheus_values(text, metric):
    return list(map(float, re.findall(r'^' + re.escape(metric) + r'(?:\{[^\n]*\})? ([0-9.eE+-]+)$', text, re.M)))


def validate_fixture(value):
    assert value['kind'] == 'epharm-isolated-learner-jwt-v1'
    assert re.fullmatch(r'pr164_[a-f0-9]{10}', value['runId'])
    assert value['expectedDatabase'] == 'epharm_rps_load_' + value['runId']
    rows = value['accounts']
    assert len(rows) == 500 and len({x['id'] for x in rows}) == 500 and len({x['token'] for x in rows}) == 500
    assert all(isinstance(x['id'], str) and 1 <= len(x['id']) <= 64 and token_shape(x['token']) and type(x['expectedAssignments']) is int and x['expectedAssignments'] >= 0 for x in rows)
    return value


class JwtAcceptance:
    def __init__(self, qa, harness):
        self.qa = qa
        self.harness = pathlib.Path(harness)
        self.rollback_token = None
        self.rollback_id = None
        self.initial_profiles = None
        self.training_before = None

    def request(self, path, *, token=None, payload=None, expected=200, code=None):
        q = self.qa
        assert path in ('/api/mobile/auth/login', '/api/mobile/auth/me', '/api/mobile/training') or re.fullmatch(r'/api/mobile/training/assignments/[a-f0-9-]{36}/start', path)
        assert q.inspect(q.BACKEND)['Config']['Labels'][q.LABEL] == q.OWNER
        config = ['silent', 'show-error', 'max-time = 15', 'request = ' + ('POST' if payload is not None or path.endswith('/start') else 'GET'),
                  'url = ' + json.dumps('http://127.0.0.1:8080' + path), 'header = "Content-Type: application/json"',
                  'write-out = "\\n%{http_code}"']
        if token is not None:
            assert token_shape(token)
            config.append('header = ' + json.dumps('Authorization: Bearer ' + token))
        if payload is not None:
            config.append('data = ' + json.dumps(json.dumps(payload)))
        result = q.docker('exec', '-i', q.BACKEND, 'curl', '--config', '-', input='\n'.join(config) + '\n').stdout
        body, status = result.rsplit('\n', 1)
        assert int(status) == expected, 'Learner HTTP status mismatch'
        data = json.loads(body) if body else None
        if code is not None:
            assert isinstance(data, dict) and data.get('code') == code, 'Learner denial code mismatch'
        return data

    def mutate(self, text):
        q = self.qa
        assert re.fullmatch(r'epharm_rps_load_pr164_[a-f0-9]{10}', q.DATABASE)
        guard = "DO $$ BEGIN IF current_database() <> " + literal(q.DATABASE) + " THEN RAISE EXCEPTION 'Wrong synthetic database'; END IF; END $$;\n"
        return q.sql('BEGIN;\n' + guard + text + '\nCOMMIT;')

    def change_status(self, person_id, status):
        assert status in ('active', 'pending', 'blocked')
        self.mutate("UPDATE pharmacists SET status=" + literal(status) + " WHERE id=" + literal(person_id) + ";")
        assert self.qa.sql('SELECT status FROM pharmacists WHERE id=' + literal(person_id) + ';') == status

    def financial(self):
        return self.qa.financial_fingerprint()

    def profiles(self):
        # Legacy acceptance deliberately adds125KZT after load; balances and earned
        # totals are covered separately by its financial checkpoint fingerprint.
        raw = self.qa.sql("SELECT json_agg(json_build_array(p.id,p.name,p.iin,p.phone,p.pharmacy_id,p.password_hash,p.receipts_30d,p.rules_accepted_30d) ORDER BY p.id) FROM pharmacists p JOIN rps_load_expected e ON e.iin=p.iin;")
        value = json.loads(raw)
        assert len(value) == 500
        return hashlib.sha256(json.dumps(value, sort_keys=True).encode()).hexdigest()

    def training(self):
        q = self.qa
        names = json.loads(q.sql("SELECT coalesce(json_agg(tablename ORDER BY tablename),'[]') FROM pg_tables WHERE schemaname='public' AND tablename ~ '(^training_|^pharmacist_training_preferences$|^admin_training_regions$)';"))
        assert names and all(re.fullmatch(r'(training_[a-z0-9_]+|pharmacist_training_preferences|admin_training_regions)', name) for name in names)
        fields = [literal(name) + ",(SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb) FROM \"" + name + '\" t)' for name in names]
        data = json.loads(q.sql('SELECT jsonb_build_object(' + ','.join(fields) + ');'))
        return {'sha256': hashlib.sha256(json.dumps(data, sort_keys=True).encode()).hexdigest(), 'rows': {name: len(rows) for name, rows in data.items()}}

    def login(self, desk):
        response = self.request('/api/mobile/auth/login', payload={'iin': desk['iin'], 'password': desk['password']})
        token = response['tokens']['accessToken']
        person = response['pharmacist']
        assert token_shape(token) and person['iin'] == desk['iin'] and person['name'] == desk['name'] and person['status'] == 'active'
        me = self.request('/api/mobile/auth/me', token=token)
        assert me['id'] == person['id'] and me['iin'] == desk['iin'], 'JWT identity mismatch'
        return {'id': person['id'], 'token': token}

    def bootstrap(self, fixture):
        q = self.qa
        assert len(fixture['devices']) == 500
        self.initial_profiles = self.profiles()
        self.training_before = self.training()
        accounts = [None] * 500
        started = time.monotonic()
        # At most five in-flight real logins and four new requests/sec; no limit override.
        with concurrent.futures.ThreadPoolExecutor(max_workers=5) as pool:
            pending = {}
            for index, desk in enumerate(fixture['devices']):
                while len(pending) >= 5:
                    done, _ = concurrent.futures.wait(pending, return_when=concurrent.futures.FIRST_COMPLETED)
                    for future in done:
                        accounts[pending.pop(future)] = future.result()
                time.sleep(max(0, started + index / 4 - time.monotonic()))
                pending[pool.submit(self.login, desk)] = index
            for future in concurrent.futures.as_completed(pending):
                accounts[pending[future]] = future.result()
        counts = json.loads(q.sql("SELECT json_object_agg(p.id,(SELECT count(*) FROM training_assignments a WHERE a.pharmacist_id=p.id)) FROM pharmacists p JOIN rps_load_expected e ON e.iin=p.iin;"))
        for account in accounts:
            account['expectedAssignments'] = counts[account['id']]
        value = validate_fixture({'kind': 'epharm-isolated-learner-jwt-v1', 'runId': fixture['runId'], 'expectedDatabase': q.DATABASE, 'accounts': accounts})
        private = q.ROOT / 'fixture/learner.jwt.private.json'
        private.write_text(json.dumps(value) + '\n')
        private.chmod(0o600)
        assert self.profiles() == self.initial_profiles and self.training() == self.training_before
        q.REPORT['learnerJwt'] = {'bootstrap': {'outcome': 'PASS', 'normalPasswordLogins': 500, 'identityChecks': 500, 'maxInFlightLogins': 5, 'scheduledLoginsPerSecond': 4, 'elapsedSeconds': round(time.monotonic() - started, 3)}, 'syntheticProfilesPreserved': True, 'trainingBefore': self.training_before}
        q.write('assessment.json', q.REPORT)

    def load(self):
        q = self.qa
        q.REPORT['stage'] = 'learner-jwt-load'
        script = self.harness / 'learner-jwt.mjs'
        before_prometheus = q.docker('exec', q.BACKEND, 'curl', '-fsS', 'http://127.0.0.1:8080/actuator/prometheus').stdout
        before_acquisitions = prometheus_values(before_prometheus, 'hikaricp_connections_acquire_seconds_count')
        assert before_acquisitions, 'Hikari acquisition counter missing before workload'
        identifier = q.run_container('k6-learner', '--network', 'container:' + q.BACKEND,
            '--cpus', '2', '--memory', '1024m', '--read-only', '--tmpfs', '/tmp:rw,nosuid,size=64m',
            '--user', str(os.getuid()) + ':' + str(os.getgid()),
            '--mount', 'type=bind,src=' + str(script) + ',dst=/work/learner-jwt.mjs,readonly',
            '--mount', 'type=bind,src=' + str(q.ROOT / 'fixture/learner.jwt.private.json') + ',dst=/fixture/learner.jwt.private.json,readonly',
            '--mount', 'type=bind,src=' + str(q.ROOT / 'results') + ',dst=/results',
            '-e', 'ISOLATED_RPS_LOAD_TEST=DISPOSABLE_DATABASE_NO_SMS', '-e', 'K6_NO_USAGE_REPORT=true',
            q.IMAGES['k6'], 'run', '--quiet', '/work/learner-jwt.mjs')
        limits = q.inspect(identifier)['HostConfig']
        assert limits['Memory'] == 1024**3 and limits['NanoCpus'] == 2_000_000_000
        started = time.monotonic()
        maxima = {'postgresConnections': 0, 'postgresLockWaiters': 0, 'postgresDeadlocks': 0, 'cpuPercent': {}, 'memoryMiB': {}}
        with (q.ROOT / 'learner-jwt-metrics.private.jsonl').open('w') as output:
            while True:
                state = q.inspect(identifier)['State']
                pg = json.loads(q.sql("SELECT json_build_object('connections',(SELECT count(*) FROM pg_stat_activity WHERE datname=current_database()),'waiting',(SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'),'deadlocks',deadlocks,'temp_bytes',temp_bytes) FROM pg_stat_database WHERE datname=current_database();"))
                assert pg['deadlocks'] == 0
                for report_key, key in [('postgresConnections', 'connections'), ('postgresLockWaiters', 'waiting'), ('postgresDeadlocks', 'deadlocks')]:
                    maxima[report_key] = max(maxima[report_key], pg[key])
                stat = q.docker('stats', '--no-stream', '--format', '{{json .}}', q.BACKEND, q.PG, q.REDIS, identifier, check=False).stdout
                for line in stat.splitlines():
                    sample = json.loads(line)
                    role = sample['Name'].removeprefix(q.OWNER + '-')
                    maxima['cpuPercent'][role] = max(maxima['cpuPercent'].get(role, 0), float(sample['CPUPerc'].rstrip('%')))
                    memory = memory_mib(sample['MemUsage'].split('/')[0])
                    if memory is not None:
                        maxima['memoryMiB'][role] = round(max(maxima['memoryMiB'].get(role, 0), memory), 3)
                output.write(json.dumps({'elapsedSeconds': round(time.monotonic() - started, 3), 'postgres': pg, 'dockerStats': stat.splitlines()}) + '\n')
                output.flush()
                if not state['Running']:
                    assert state['ExitCode'] == 0 and not state['OOMKilled'], 'Learner generator failed'
                    break
                assert time.monotonic() - started < 300, 'Learner phase exceeded bounded deadline'
                time.sleep(8)
        summary = json.loads((q.ROOT / 'results/learner-jwt-summary.json').read_text())
        assert summary['accounts'] == 500 and summary['outcome'] == 'completed'
        metrics = summary['metrics']
        assert metrics['learner_completed']['values']['count'] >= 2000
        for name in ['learner_errors', 'learner_rate_limited', 'learner_transport', 'learner_5xx', 'learner_unexpected_status', 'dropped_iterations']:
            assert metrics.get(name, {}).get('values', {}).get('count', 0) == 0, 'Learner error budget failed'
        assert not [name for name, metric in metrics.items() if any(not gate['ok'] for gate in metric.get('thresholds', {}).values())], 'Learner latency/threshold failed'
        prometheus = q.docker('exec', q.BACKEND, 'curl', '-fsS', 'http://127.0.0.1:8080/actuator/prometheus').stdout
        timeouts = re.findall(r'^hikaricp_connections_timeout_total(?:\{[^\n]*\})? ([0-9.eE+-]+)$', prometheus, re.M)
        assert timeouts and all(float(x) == 0 for x in timeouts), 'Hikari timeout evidence missing or nonzero'
        actual_pool_maximum = prometheus_values(prometheus, 'hikaricp_connections_max')
        assert actual_pool_maximum == [10], 'Actual Hikari pool must retain the prescribed10 connections'
        hikari = {}
        for metric in ['hikaricp_connections_acquire_seconds_max', 'hikaricp_connections_usage_seconds_max', 'hikaricp_connections_active']:
            values = re.findall(r'^' + metric + r'(?:\{[^\n]*\})? ([0-9.eE+-]+)$', prometheus, re.M)
            hikari[metric] = max(map(float, values)) if values else None
        after_acquisitions = prometheus_values(prometheus, 'hikaricp_connections_acquire_seconds_count')
        assert after_acquisitions
        acquisition_delta = sum(after_acquisitions) - sum(before_acquisitions)
        assert acquisition_delta >= metrics['learner_completed']['values']['count'], 'Workload lacks corresponding database-pool activity'
        assert self.profiles() == self.initial_profiles and self.training() == self.training_before
        q.REPORT['learnerJwt']['load'] = {'outcome': 'PASS', 'readCount': metrics['learner_completed']['values']['count'], 'elapsedSeconds': round(time.monotonic() - started, 3), 'sampledResourceMaxima': maxima, 'hikariTimeouts': 0, 'hikariActualMaximumPoolSize': actual_pool_maximum[0], 'hikariAcquisitionCountDelta': acquisition_delta, 'hikariAtPhaseEnd': hikari, 'summary': summary}
        q.write('assessment.json', q.REPORT)

    def create_probe(self, fixture, ordinal):
        q = self.qa
        spec = importlib.util.spec_from_file_location('rps_fixture_generator', q.SOURCE / 'load-tests/build-rps-fixture.py')
        generator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(generator)
        desk = dict(fixture['devices'][0])
        desk.update(iin=generator.synthetic_iins(502)[500 + ordinal], phone='+7700' + str(9990001 + ordinal), name='JWT Probe ' + ('delete' if ordinal == 0 else 'rollback'), password=secrets.token_urlsafe(12))
        context = {'processInstanceId': q.OWNER + ':jwt-probe-' + str(ordinal)}
        grant = q.session_request('device/register-without-sms', {'iin': desk['iin'], 'phone': desk['phone'], 'name': desk['name'], 'password': desk['password'], 'deviceContext': context}, desk)
        assert grant['status'] == 'authenticated'
        q.session_request('device/logout', None, desk, grant['sessionToken'], expected=204)
        account = self.login(desk)
        assert account['id'] == grant['pharmacist']['id']
        return account

    def require_denied(self, account, status, code):
        before = self.training()
        for path in ['/api/mobile/auth/me', '/api/mobile/training', '/api/mobile/training/assignments/' + str(uuid.UUID(int=1)) + '/start']:
            self.request(path, token=account['token'], expected=status, code=code)
        assert self.training() == before, 'Rejected request changed protected training data'

    def semantics(self, fixture):
        q = self.qa
        q.REPORT['stage'] = 'learner-same-jwt-status-revocation'
        account = self.create_probe(fixture, 0)
        assert overview_valid(self.request('/api/mobile/training', token=account['token']), 0)
        self.request('/api/mobile/training/assignments/' + str(uuid.UUID(int=1)) + '/start', token=account['token'], expected=404)
        self.change_status(account['id'], 'pending')
        assert self.request('/api/mobile/auth/me', token=account['token'])['status'] == 'pending'
        assert overview_valid(self.request('/api/mobile/training', token=account['token']), 0)
        self.change_status(account['id'], 'blocked')
        self.require_denied(account, 403, 'USER_NOT_ACTIVE')
        self.change_status(account['id'], 'active')
        assert self.request('/api/mobile/auth/me', token=account['token'])['id'] == account['id']
        self.mutate('DELETE FROM pharmacists WHERE id=' + literal(account['id']) + " AND name='JWT Probe delete';")
        assert q.sql('SELECT count(*) FROM pharmacists WHERE id=' + literal(account['id']) + ';') == '0'
        self.require_denied(account, 401, 'USER_NOT_FOUND')
        kept = self.create_probe(fixture, 1)
        self.change_status(kept['id'], 'blocked')
        self.require_denied(kept, 403, 'USER_NOT_ACTIVE')
        self.rollback_id, self.rollback_token = kept['id'], kept['token']
        assert self.profiles() == self.initial_profiles and self.training() == self.training_before
        q.REPORT['learnerJwt']['sameIssuedJwt'] = {'outcome': 'PASS', 'activeRead200': True, 'pendingStatusProfileAndOverview200': True, 'blockedReadAndStart403': True, 'deletedReadAndStart401': True, 'authorizedMissingAssignmentStart404': True, 'noProtectedTrainingWritesOnDenial': True, 'noNewTokenAfterStatusChanges': True, 'primary500ProfilesPreserved': True, 'rollbackProbeKeptBlocked': True}
        q.write('assessment.json', q.REPORT)

    def old_baseline(self):
        assert self.rollback_token and self.rollback_id
        assert self.qa.sql('SELECT status FROM pharmacists WHERE id=' + literal(self.rollback_id) + ';') == 'blocked'
        assert overview_valid(self.request('/api/mobile/training', token=self.rollback_token), 0)
        self.qa.REPORT['learnerJwt']['downgradeLimitation'] = {'observedOldBackendBlockedJwtTrainingStatus': 200, 'outcome': 'BASELINE_WEAKNESS_CONFIRMED_NOT_SECURITY_ACCEPTANCE', 'note': 'Previousv15 lacks the new current-account status loader; downgrade reopens this baseline behavior. Schema/data compatibility does not preserve the candidate authorization fix.'}

    def final_candidate(self):
        q = self.qa
        before = self.financial()
        schema = q.schema()
        q.REPORT['outcome'] = 'INCOMPLETE'
        q.REPORT['stage'] = 'compat-candidate-final'
        q.write('assessment.json', q.REPORT)
        q.stop_backend()
        q.start_backend('candidate-final', q.REPORT['runtimeImages']['candidate'], q.ROOT / 'app.private.env')
        assert q.schema() == schema and self.financial() == before
        account = {'id': self.rollback_id, 'token': self.rollback_token}
        self.require_denied(account, 403, 'USER_NOT_ACTIVE')
        self.change_status(self.rollback_id, 'active')
        assert self.request('/api/mobile/auth/me', token=self.rollback_token)['id'] == self.rollback_id
        self.change_status(self.rollback_id, 'blocked')
        self.require_denied(account, 403, 'USER_NOT_ACTIVE')
        assert self.financial() == before and self.profiles() == self.initial_profiles and self.training() == self.training_before
        q.REPORT['learnerJwt']['finalCandidate'] = {'outcome': 'PASS', 'sameJwtBlocked403AfterDowngrade': True, 'sameJwtActive200ThenBlocked403': True, 'schemaPreserved': True, 'financialFingerprintPreserved': True, 'primary500ProfilesPreserved': True, 'trainingFingerprintPreserved': True}
