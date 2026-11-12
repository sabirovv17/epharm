"""One stopped-backend snapshot roundtrip before the unchanged candidate workload."""
import hashlib
import json
import os
import re
import stat
from runtime_observations import fingerprint_comparison, ddl_block_fingerprints


def owned_identity(item, identifier, owner, label, network):
    assert re.fullmatch(r'[a-f0-9]{64}', identifier) and item['Id'] == identifier
    assert item['Config']['Labels'].get(label) == owner
    attached = item['NetworkSettings']['Networks']
    assert len(attached) == 1 and next(iter(attached.values()))['NetworkID'] == network
    assert not item['HostConfig'].get('PortBindings'), 'Disposable services must have no published port'


def canonical_schema(text):
    # PostgreSQL dump psql restriction nonces vary; neither is schema or data.
    return '\n'.join(line for line in text.splitlines() if not re.fullmatch(r'\\(?:un)?restrict [A-Za-z0-9]+', line))


def numeric_cpu_model(text):
    first = text.split('\n\n', 1)[0]
    fields = dict(line.split(':', 1) for line in first.splitlines() if ':' in line)
    fields = {name.strip(): value.strip() for name, value in fields.items()}
    return {name: int(fields[key]) for name, key in [('family', 'cpu family'), ('model', 'model'), ('stepping', 'stepping')]}


def host_cpu_model():
    with open('/proc/cpuinfo') as cpuinfo:
        result = numeric_cpu_model(cpuinfo.read(1024 * 1024))
    result['hostLogicalProcessors'] = os.cpu_count()
    return result


def checked_snapshot(path, expected_hash):
    info = path.lstat()
    assert stat.S_ISREG(info.st_mode) and not path.is_symlink() and info.st_nlink == 1 and info.st_uid == os.getuid() and info.st_mode & 0o077 == 0
    data = path.read_bytes()
    assert hashlib.sha256(data).hexdigest() == expected_hash
    return data.decode('utf-8')


def validate_reference(summary, diagnostic, phase_assertion):
    metrics = summary['metrics']
    assert metrics['rps_registration_created']['values']['count'] == 500
    assert metrics['rps_completed']['values']['count'] == 500
    checks = metrics['checks']['values']
    assert checks['fails'] == 0 and checks['passes'] > 0 and checks['rate'] == 1
    assert metrics['http_req_failed']['values']['rate'] == 0
    for name in ('rps_errors', 'rps_rate_limited', 'rps_5xx', 'rps_transport_errors',
                 'rps_unexpected_4xx', 'rps_unexpected_redirects', 'dropped_iterations'):
        assert metrics[name]['values']['count'] == 0, 'Reference has an error or incomplete iteration'
    failed = [(name, gate) for name, metric in metrics.items() for gate, result in metric.get('thresholds', {}).items() if result.get('ok') is not True]
    if phase_assertion:
        assert diagnostic['k6ExitCode'] == 99 and failed, 'Unexpected reference phase assertion'
        assert all(name == 'http_req_duration{workload:rps-register}' and gate in ('p(95)<2000', 'p(99)<10000') for name, gate in failed), 'Reference failed a non-registration-latency gate'
    else:
        assert diagnostic['k6ExitCode'] == 0 and not failed
    return {'registered': 500, 'completed': 500, 'checksFailed': 0, 'httpFailureRate': 0,
            'zeroErrorCounters': True, 'failedRegistrationLatencyGates': [gate for _, gate in failed]}


class SnapshotRoundtrip:
    def __init__(self, qa):
        self.q = qa
        self.completed = False
        self.last_ddl_diagnostics = None

    def guard(self):
        q = self.q
        assert re.fullmatch(r'pr164-[a-f0-9]{12}', q.OWNER)
        assert re.fullmatch(r'epharm_rps_load_pr164_[a-f0-9]{10}', q.DATABASE)
        assert re.fullmatch(r'[a-f0-9]{64}', q.NET)
        network = json.loads(q.docker('network', 'inspect', q.NET).stdout)[0]
        assert network['Id'] == q.NET and network['Name'] == q.OWNER + '-net'
        assert network['Internal'] is True and network['Labels'].get(q.LABEL) == q.OWNER
        for identifier in network['Containers']:
            assert identifier in q.OWNED and q.inspect(identifier)['Config']['Labels'].get(q.LABEL) == q.OWNER
        for identifier in (q.PG, q.REDIS):
            assert identifier in q.OWNED
            owned_identity(q.inspect(identifier), identifier, q.OWNER, q.LABEL, q.NET)
        assert q.sql('SELECT current_database();') == q.DATABASE
        fixture = json.loads((q.ROOT / 'fixture/fixture.private.json').read_text())
        assert fixture['expectedDatabase'] == q.DATABASE and len(fixture['devices']) == 500

    def dump(self, schema_only=False):
        self.guard()
        args = ['exec', self.q.PG, 'pg_dump', '--no-owner', '--no-privileges', '-U', 'rpsload', '-d', self.q.DATABASE]
        args += ['--schema-only'] if schema_only else ['--clean', '--if-exists']
        value = self.q.docker(*args, timeout=90).stdout
        assert 0 < len(value.encode()) < 32 * 1024**2, 'Fresh synthetic dump exceeded its bound'
        return value

    def fingerprint(self):
        q = self.q
        names = json.loads(q.sql("SELECT json_agg(tablename ORDER BY tablename) FROM pg_tables WHERE schemaname='public';"))
        assert names and all(re.fullmatch(r'[a-z][a-z0-9_]*', name) for name in names)
        selects = ["SELECT '" + name + "'::text AS name,(SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb) FROM public.\"" + name + '\" t) AS payload' for name in names]
        data = json.loads(q.sql('SELECT jsonb_object_agg(name,payload) FROM (' + ' UNION ALL '.join(selects) + ') fixture;'))
        sequences = json.loads(q.sql("SELECT coalesce(json_agg(sequencename ORDER BY sequencename),'[]') FROM pg_sequences WHERE schemaname='public';"))
        assert all(re.fullmatch(r'[a-z][a-z0-9_]*', name) for name in sequences)
        counters = {}
        for name in sequences:
            counters[name] = json.loads(q.sql('SELECT json_build_array(last_value,is_called) FROM public."' + name + '";'))
        counts = {name: len(rows) for name, rows in data.items()}
        assert counts['pharmacists'] == 0 and counts['posm_device_staff_sessions'] == 0
        ddl = canonical_schema(self.dump(True))
        self.last_ddl_diagnostics = ddl_block_fingerprints(ddl)
        return {'schema99': q.schema(), 'ddlSha256': hashlib.sha256(ddl.encode()).hexdigest(),
                'allPublicDataSha256': hashlib.sha256(json.dumps(data, sort_keys=True).encode()).hexdigest(),
                'sequenceCountersSha256': hashlib.sha256(json.dumps(counters, sort_keys=True).encode()).hexdigest(),
                'publicTableCount': len(names), 'publicRowCount': sum(counts.values()), 'sequenceCount': len(sequences),
                'pharmacists': counts['pharmacists'], 'deviceSessions': counts['posm_device_staff_sessions']}

    def reset_redis(self):
        self.guard()
        assert self.q.BACKEND is None, 'Stop the old backend before restoring rate-limit state'
        keyspace = self.q.docker('exec', self.q.REDIS, 'redis-cli', 'INFO', 'keyspace').stdout
        assert all(line.startswith(('db0:', '#')) or not line.strip() for line in keyspace.splitlines()), 'Refuse another Redis logical database'
        assert self.q.docker('exec', self.q.REDIS, 'redis-cli', '-n', '0', 'FLUSHDB', 'SYNC').stdout.strip() == 'OK'
        assert self.q.docker('exec', self.q.REDIS, 'redis-cli', '-n', '0', 'DBSIZE').stdout.strip() == '0'

    def require_stopped(self, backend):
        self.guard()
        assert self.q.BACKEND is None, 'Stop the old backend before the snapshot roundtrip'
        assert backend in self.q.OWNED
        item = self.q.inspect(backend)
        owned_identity(item, backend, self.q.OWNER, self.q.LABEL, self.q.NET)
        assert item['State']['Running'] is False, 'Recorded old backend must remain stopped'

    def run(self, stop):
        q = self.q
        assert not self.completed and q.REPORT['stage'] == 'compat-old-before'
        original_stage = q.REPORT['stage']
        proof = {'outcome': 'INCOMPLETE', 'acceptanceRole': 'FRESH_FIXTURE_ROUNDTRIP_ONLY',
                 'oldReferenceLoadExecuted': False, 'candidateAuthWarmup': False,
                 'privateSnapshotUploaded': False}
        q.REPORT['snapshotRoundtrip'] = proof

        def stage(name):
            proof['stage'] = name
            q.REPORT['stage'] = 'snapshot-roundtrip-' + name
            q.write('assessment.json', q.REPORT)

        try:
            stage('stop-old-backend')
            self.guard()
            backend = q.BACKEND
            assert backend in q.OWNED
            owned_identity(q.inspect(backend), backend, q.OWNER, q.LABEL, q.NET)
            stop()
            self.require_stopped(backend)
            assert q.docker('exec', q.REDIS, 'redis-cli', '-n', '0', 'DBSIZE').stdout.strip() == '0'
            assert not list((q.ROOT / 'results').glob('rps-register-*.json'))

            stage('baseline-fingerprint')
            baseline = self.fingerprint()
            baseline_ddl_diagnostics = self.last_ddl_diagnostics
            stage('write-snapshot')
            self.require_stopped(backend)
            snapshot = q.ROOT / 'fresh-fixture.private.sql'
            with snapshot.open('x') as output:
                output.write(self.dump())
            snapshot.chmod(0o600)
            snapshot_hash = hashlib.sha256(snapshot.read_bytes()).hexdigest()

            stage('stability-fingerprint')
            self.require_stopped(backend)
            stable = self.fingerprint()
            proof['stabilityComparison'] = fingerprint_comparison(baseline, stable)
            proof['stabilityComparison']['ddlBlockDiagnostics'] = {'before': baseline_ddl_diagnostics, 'after': self.last_ddl_diagnostics}
            q.write('assessment.json', q.REPORT)
            assert stable == baseline, 'Snapshot was not taken from a stable fresh fixture'

            stage('restore-snapshot')
            self.require_stopped(backend)
            restore_text = checked_snapshot(snapshot, snapshot_hash)
            guard = "DO $$ BEGIN IF current_database() <> '" + q.DATABASE + "' THEN RAISE EXCEPTION 'Wrong disposable database'; END IF; END $$;\n"
            q.sql(guard + restore_text)
            stage('reset-redis')
            self.require_stopped(backend)
            self.reset_redis()
            stage('restored-fingerprint')
            self.require_stopped(backend)
            restored = self.fingerprint()
            proof['restoreComparison'] = fingerprint_comparison(baseline, restored)
            proof['restoreComparison']['ddlBlockDiagnostics'] = {'before': baseline_ddl_diagnostics, 'after': self.last_ddl_diagnostics}
            q.write('assessment.json', q.REPORT)
            assert restored == baseline, 'Snapshot roundtrip changed the candidate fresh fixture'
            proof.update(outcome='PASS', stage='complete', snapshotSha256=snapshot_hash,
                         ownedRedisDb0Keys=0, candidateFreshProfiles=0)
            q.REPORT['stage'] = original_stage
            q.write('assessment.json', q.REPORT)
            self.completed = True
        except Exception as error:
            proof.update(outcome='FAIL', failureType=type(error).__name__)
            q.write('assessment.json', q.REPORT)
            raise
