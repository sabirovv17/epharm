#!/usr/bin/env python3
"""Owned disposable QA of official artifacts. Never builds application code."""
import datetime as dt
import hashlib
import json
import os
import pathlib
import re
import secrets
import shutil
import subprocess
import sys
import time
import uuid
import zipfile

os.umask(0o077)
ROOT = pathlib.Path(os.environ['QA_PRIVATE_ROOT']).resolve()
SOURCE = pathlib.Path(os.environ['QA_SOURCE_ROOT']).resolve()
DOCKER = shutil.which('docker')
OWNER = 'pr160-' + uuid.uuid4().hex[:12]
LABEL = 'epharm.isolated-qa.owner'
OWNED = []
NET = None
PG = None
BACKEND = None
REPORT = {'outcome': 'INCOMPLETE', 'owner': OWNER, 'resources': [], 'phases': {}, 'compatibility': {}, 'limits': {
    'backendCpu': 2, 'backendMemoryMiB': 1536, 'jvmXmxMiB': 768, 'metaspaceMiB': 384, 'hikariMaximumPoolSize': 10,
    'postgresCpu': 2, 'postgresMemoryMiB': 768, 'redisCpu': 1, 'redisMemoryMiB': 128,
    'k6Cpu': 2, 'k6MemoryMiB': 1024,
}, 'limitations': [
    'Synthetic 500-pharmacy API workload; no production-sized dataset, physical POSM capacity or real video playback.',
    'No sales, offline replay, uploads, Flutter/mobile, real SMS, external catalogue, Standard-N source or CRM bridges.',
    'Compatibility covers normal Flyway, process-bound staff sessions and preservation of synthetic financial data; it does not certify financial writers or production disaster recovery.',
    'k6 capped at 2 CPU/1024MiB; prior PR158 generator was uncapped. Backend limits and all workload/latency/error thresholds remain unchanged.',
]}


def command(args, *, input=None, check=True, timeout=120):
    return subprocess.run(args, input=input, capture_output=True, text=True, check=check, timeout=timeout)


def docker(*args, **kwargs):
    return command([DOCKER, *args], **kwargs)


def write(name, value):
    (ROOT / name).write_text(json.dumps(value, indent=2) + '\n')


def inspect(identifier):
    return json.loads(docker('inspect', identifier).stdout)[0]


def own(identifier, role):
    item = inspect(identifier)
    assert item['Id'] == identifier and item['Config']['Labels'][LABEL] == OWNER
    OWNED.append(identifier)
    REPORT['resources'].append({'role': role, 'containerId': identifier, 'imageId': item['Image'],
                                'memoryBytes': item['HostConfig']['Memory'], 'nanoCpus': item['HostConfig']['NanoCpus']})
    write('assessment.json', REPORT)
    return identifier


def run_container(role, *args):
    identifier = docker('run', '-d', '--pull=never', '--name', OWNER + '-' + role,
                        '--label', LABEL + '=' + OWNER, *args).stdout.strip()
    assert re.fullmatch('[a-f0-9]{64}', identifier)
    return own(identifier, role)


def sql(text):
    assert PG and inspect(PG)['Config']['Labels'][LABEL] == OWNER
    return docker('exec', '-i', PG, 'psql', '-XAt', '-v', 'ON_ERROR_STOP=1', '-U', 'rpsload', '-d', DATABASE,
                  input=text, timeout=90).stdout.strip()


def schema():
    rows = json.loads(sql("SELECT json_agg(json_build_array(installed_rank,version,description,type,script,checksum,success) ORDER BY installed_rank) FROM flyway_schema_history;"))
    assert all(row[-1] for row in rows) and max(int(row[1]) for row in rows if row[1]) == 99
    return hashlib.sha256(json.dumps(rows, sort_keys=True).encode()).hexdigest()


def stop_backend():
    global BACKEND
    if BACKEND:
        assert inspect(BACKEND)['Config']['Labels'][LABEL] == OWNER
        docker('stop', '--time', '30', BACKEND)
        BACKEND = None


def start_backend(which, image, env_path):
    global BACKEND
    BACKEND = run_container('backend-' + which, '--network', NET, '--network-alias', 'backend',
        '--memory', '1536m', '--cpus', '2', '--read-only', '--tmpfs', '/tmp:rw,nosuid,size=128m',
        '--env-file', str(env_path), image)
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        result = docker('exec', BACKEND, 'curl', '-fsS', '--max-time', '3', 'http://127.0.0.1:8080/api/health', check=False, timeout=10)
        if result.returncode == 0:
            health = json.loads(result.stdout)
            expected = IDENTITIES['previous' if which.startswith('old') else 'candidate']
            assert health['status'] == 'ok' and health['version'] == expected['version'] and health['commit'] == expected['commit']
            REPORT['compatibility'][which] = {'health': health, 'schema99Fingerprint': schema()}
            return
        assert inspect(BACKEND)['State']['Running'], 'Backend exited before readiness'
        time.sleep(2)
    raise AssertionError('Bounded readiness exceeded')


def session_request(path, payload, desk, token=None, expected=200):
    config = ['silent', 'show-error', 'max-time = 15', 'request = POST',
              'url = ' + json.dumps('http://127.0.0.1:8080/api/posm/staff/' + path),
              'header = ' + json.dumps('Content-Type: application/json'),
              'header = ' + json.dumps('X-Device-Id: ' + desk['deviceId']),
              'header = ' + json.dumps('X-Posm-Key: ' + desk['key']), 'write-out = "\\n%{http_code}"']
    if token:
        config.append('header = ' + json.dumps('X-Staff-Session: ' + token))
    if payload is not None:
        config.append('data = ' + json.dumps(json.dumps(payload)))
    response = docker('exec', '-i', BACKEND, 'curl', '--config', '-', input='\n'.join(config) + '\n').stdout
    body, status = response.rsplit('\n', 1)
    assert int(status) == expected, 'Staff compatibility HTTP status mismatch'
    return json.loads(body) if body else None


def financial_fingerprint():
    # Retain hashes/aggregates, never credential or identity rows, in the public report.
    value = sql("""BEGIN READ ONLY;
SELECT json_build_object(
 'wallet',(SELECT coalesce(json_agg(row_to_json(w) ORDER BY id),'[]'::json) FROM pharmacist_wallet_entries w),
 'checkpoints',(SELECT coalesce(json_agg(row_to_json(c) ORDER BY pharmacist_id),'[]'::json) FROM pharmacist_wallet_checkpoints c),
 'balances',(SELECT coalesce(json_agg(json_build_array(p.id,p.balance,p.earned_30d) ORDER BY p.id),'[]'::json) FROM pharmacists p),
 'mappings',(SELECT coalesce(json_agg(row_to_json(m) ORDER BY id),'[]'::json) FROM posm_pharmacist_mappings m));
ROLLBACK;""")
    # psql -tA also emits transaction tags; select only the JSON line.
    data = json.loads(next(line for line in value.splitlines() if line.startswith('{')))
    return {'sha256': hashlib.sha256(json.dumps(data, sort_keys=True).encode()).hexdigest(),
            'walletRows': len(data['wallet']), 'checkpointRows': len(data['checkpoints']),
            'walletAmountKzt': sum(row['amount_kzt'] for row in data['wallet']), 'mappingRows': len(data['mappings'])}


def phase(mode):
    REPORT['stage'] = 'load-' + mode
    name = OWNER + '-k6-' + mode
    env = os.environ.copy()
    for key in list(env):
        if key in ('GITHUB_TOKEN', 'GH_TOKEN'):
            del env[key]
    env.update(PATH=str(ROOT / 'bin') + os.pathsep + env['PATH'], BASE_URL='http://127.0.0.1:8080',
        RPS_FIXTURE_FILE=str(ROOT / 'fixture/fixture.private.json'), ISOLATED_RPS_LOAD_TEST='DISPOSABLE_DATABASE_NO_SMS',
        RPS_DOCKER_NETWORK='container:' + BACKEND, K6_RESULT_DIR=str(ROOT / 'results'), MODE=mode,
        VUS='50' if mode == 'register' else '25', DURATION='2m', RPS_OWNED_CONTAINER=name,
        QA_OWNER=OWNER, QA_DOCKER=DOCKER, K6_IMAGE=IMAGES['k6'])
    start = time.monotonic()
    maxima = {'cpuPercent': {}, 'memoryMiB': {}, 'postgresConnections': 0, 'postgresLockWaiters': 0, 'postgresDeadlocks': 0}
    cpu_before = docker('exec', BACKEND, 'cat', '/sys/fs/cgroup/cpu.stat', check=False).stdout
    def cpu_counters(raw):
        return {parts[0]: int(parts[1]) for line in raw.splitlines() if len(parts := line.split()) == 2 and parts[1].isdigit()}
    def memory_mib(raw):
        match = re.fullmatch(r'([0-9.]+)([kMGT]?i?B)', raw.strip())
        factors = {'B': 1 / 1024**2, 'kB': 1000 / 1024**2, 'KiB': 1 / 1024, 'MB': 1000**2 / 1024**2,
                   'MiB': 1, 'GB': 1000**3 / 1024**2, 'GiB': 1024, 'TB': 1000**4 / 1024**2, 'TiB': 1024**2}
        return float(match[1]) * factors[match[2]] if match else None
    with (ROOT / (mode + '-stdout.private.txt')).open('w') as out, (ROOT / (mode + '-stderr.private.txt')).open('w') as err, (ROOT / (mode + '-metrics.private.jsonl')).open('w') as metrics:
        process = subprocess.Popen(['bash', str(SOURCE / 'load-tests/run-rps-registration.sh')], cwd=SOURCE, env=env, stdout=out, stderr=err)
        try:
            while process.poll() is None:
                k6 = docker('inspect', name, check=False)
                if k6.returncode == 0:
                    identifier = json.loads(k6.stdout)[0]['Id']
                    if identifier not in OWNED:
                        own(identifier, 'k6-' + mode)
                stat = docker('stats', '--no-stream', '--format', '{{json .}}', BACKEND, PG, REDIS, name, check=False)
                pg = sql("SELECT json_build_object('connections',(SELECT count(*) FROM pg_stat_activity WHERE datname=current_database()),'waiting',(SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'),'deadlocks',deadlocks,'temp_bytes',temp_bytes) FROM pg_stat_database WHERE datname=current_database();")
                pg = json.loads(pg)
                assert pg['deadlocks'] == 0
                for line in stat.stdout.splitlines():
                    sample = json.loads(line)
                    role = sample['Name'].removeprefix(OWNER + '-')
                    maxima['cpuPercent'][role] = max(maxima['cpuPercent'].get(role, 0), float(sample['CPUPerc'].rstrip('%')))
                    memory = memory_mib(sample['MemUsage'].split('/')[0])
                    if memory is not None:
                        maxima['memoryMiB'][role] = round(max(maxima['memoryMiB'].get(role, 0), memory), 3)
                for report_key, pg_key in [('postgresConnections', 'connections'), ('postgresLockWaiters', 'waiting'), ('postgresDeadlocks', 'deadlocks')]:
                    maxima[report_key] = max(maxima[report_key], pg[pg_key])
                metrics.write(json.dumps({'elapsedSeconds': round(time.monotonic() - start, 3), 'dockerStats': stat.stdout.splitlines(), 'postgres': pg}) + '\n')
                metrics.flush()
                assert time.monotonic() - start < 600, 'Bounded phase exceeded'
                time.sleep(8)
            assert process.returncode == 0, 'Original k6 phase failed; no retries or relaxed thresholds'
        finally:
            if process.poll() is None:
                process.terminate()
                process.wait(timeout=20)
    summaries = sorted((ROOT / 'results').glob('rps-' + mode + '-*.json'))
    assert len(summaries) == 1
    summary = json.loads(summaries[0].read_text())
    assert summary['fixture']['devices'] == 500 and summary['mode'] == mode
    metrics = summary['metrics']
    assert not [name for name, metric in metrics.items() if any(not gate['ok'] for gate in metric.get('thresholds', {}).values())]
    for name in ('rps_errors', 'rps_rate_limited', 'rps_5xx', 'rps_transport_errors', 'rps_unexpected_4xx', 'rps_unexpected_redirects', 'dropped_iterations'):
        assert metrics.get(name, {}).get('values', {}).get('count', 0) == 0
    assert metrics['rps_completed']['values']['count'] == 500 if mode != 'sessions' else metrics['rps_session_checks']['values']['count'] >= 2000
    if mode == 'register':
        assert metrics['rps_registration_created']['values']['count'] == 500
    verification = sql((ROOT / 'fixture/verify.sql').read_text())
    (ROOT / (mode + '-verify.txt')).write_text(verification + '\n')
    prometheus = docker('exec', BACKEND, 'curl', '-fsS', 'http://127.0.0.1:8080/actuator/prometheus').stdout
    (ROOT / (mode + '-prometheus.private.txt')).write_text(prometheus)
    timeouts = re.findall(r'^hikaricp_connections_timeout_total(?:\{[^\n]*\})? ([0-9.eE+-]+)$', prometheus, re.M)
    assert timeouts and all(float(value) == 0 for value in timeouts), 'Hikari timeout evidence missing or nonzero'
    hikari = {}
    for metric, label in [('hikaricp_connections_acquire_seconds_max', 'rollingAcquireMaxSeconds'), ('hikaricp_connections_usage_seconds_max', 'rollingHoldMaxSeconds')]:
        values = re.findall(r'^' + metric + r'(?:\{[^\n]*\})? ([0-9.eE+-]+)$', prometheus, re.M)
        hikari[label] = max(map(float, values)) if values else None
    cpu_after = cpu_counters(docker('exec', BACKEND, 'cat', '/sys/fs/cgroup/cpu.stat', check=False).stdout)
    cpu_before = cpu_counters(cpu_before)
    REPORT['phases'][mode] = {'outcome': 'PASS', 'elapsedSeconds': round(time.monotonic() - start, 3),
        'summary': summary, 'sqlVerification': 'PASS', 'hikariTimeouts': 0, 'sampledResourceMaxima': maxima,
        'hikariAtPhaseEnd': hikari, 'cgroupPhaseCpuCounterDeltaIncludingSetupTeardown': {
            key: value - cpu_before[key] for key, value in cpu_after.items() if key in cpu_before}}
    write('assessment.json', REPORT)


def main():
    global IDENTITIES, DATABASE, IMAGES, NET, PG, REDIS
    assert sys.platform.startswith('linux') and os.environ.get('GITHUB_ACTIONS') == 'true'
    assert os.environ.get('ROOT_CANDIDATE_READY') == 'APPROVED_OFFICIAL_CANDIDATE'
    assert command(['git', '-C', str(SOURCE), 'rev-parse', 'HEAD']).stdout.strip() == os.environ['CANDIDATE_COMMIT']
    assert not os.environ.get('GITHUB_TOKEN') and not os.environ.get('GH_TOKEN'), 'GitHub credentials must not reach runtime step'
    IDENTITIES = json.loads((ROOT / 'artifacts/verified-identities.json').read_text())
    candidate_sources = json.loads((pathlib.Path(IDENTITIES['candidate']['directory']) / 'source-files.json').read_text())
    assert all(hashlib.sha256((SOURCE / name).read_bytes()).hexdigest() == digest for name, digest in candidate_sources.items())
    REPORT['artifacts'] = IDENTITIES
    REPORT['preexistingContainers'] = docker('ps', '-aq', '--no-trunc').stdout.splitlines()
    REPORT['preexistingNetworks'] = docker('network', 'ls', '-q', '--no-trunc').stdout.splitlines()
    loaded = {}
    migration_maps = {}
    for which, item in IDENTITIES.items():
        REPORT['stage'] = 'verify-official-' + which
        directory = pathlib.Path(item['directory'])
        command([sys.executable, str(SOURCE / 'tools/release/verify-image-archive.py'), str(directory / 'backend.tar.gz'), 'epharm/backend:' + item['version']])
        command([sys.executable, str(SOURCE / 'tools/release/verify-financial-transition.py'), '--jar', str(directory / 'app.jar'), '--schema-protected', '--access-protected', '--session-protected', '--compound-protected'])
        with zipfile.ZipFile(directory / 'app.jar') as jar:
            migration_maps[which] = {name: hashlib.sha256(jar.read(name)).hexdigest() for name in jar.namelist() if re.fullmatch(r'BOOT-INF/classes/db/migration/V[0-9]+__[^/]+\.sql', name)}
        docker('load', '-i', str(directory / 'backend.tar.gz'), timeout=240)
        image = json.loads(docker('image', 'inspect', 'epharm/backend:' + item['version']).stdout)[0]
        assert image['Os'] == 'linux' and image['Architecture'] == 'amd64'
        assert image['Config']['Labels']['org.opencontainers.image.version'] == item['version']
        assert image['Config']['Labels']['org.opencontainers.image.revision'] == item['commit']
        provenance = json.loads((directory / 'provenance.json').read_text())
        assert image['RootFS']['Layers'] == provenance['images']['backend']['rootfs']
        # Extraction uses a labelled stopped container without a network; the app never runs here.
        identifier = docker('create', '--network', 'none', '--label', LABEL + '=' + OWNER, image['Id']).stdout.strip()
        own(identifier, 'artifact-extraction-' + which)
        extracted = ROOT / (which + '-image-app.jar')
        docker('cp', identifier + ':/app/app.jar', str(extracted), timeout=90)
        assert hashlib.sha256(extracted.read_bytes()).hexdigest() == item['sha256']['app.jar']
        loaded[which] = image['Id']
    assert migration_maps['previous'] == migration_maps['candidate'], 'Unexpected migration change: separate schema review required'
    REPORT['unchangedMigrationCount'] = len(migration_maps['candidate'])
    IMAGES = {}
    for role, tag in [('postgres', 'postgres:16-alpine'), ('redis', 'redis:7-alpine'), ('k6', 'grafana/k6:0.54.0')]:
        docker('pull', tag, timeout=240)
        IMAGES[role] = json.loads(docker('image', 'inspect', tag).stdout)[0]['Id']
    REPORT['runtimeImages'] = dict(IMAGES, **loaded)
    REPORT['stage'] = 'prepare-owned-fixture-and-network'
    run_id = 'pr160_' + secrets.token_hex(5)
    command([sys.executable, str(SOURCE / 'load-tests/build-rps-fixture.py'), '--run-id', run_id, '--desks', '500', '--output-dir', str(ROOT / 'fixture')])
    fixture = json.loads((ROOT / 'fixture/fixture.private.json').read_text())
    DATABASE = fixture['expectedDatabase']
    NET = docker('network', 'create', '--internal', '--label', LABEL + '=' + OWNER, OWNER + '-net').stdout.strip()
    network = json.loads(docker('network', 'inspect', NET).stdout)[0]
    assert network['Internal'] is True and network['Labels'][LABEL] == OWNER
    REPORT['networkId'] = NET
    write('assessment.json', REPORT)
    password = secrets.token_urlsafe(24)
    pg_env = ROOT / 'postgres.private.env'
    pg_env.write_text('POSTGRES_USER=rpsload\nPOSTGRES_PASSWORD=' + password + '\nPOSTGRES_DB=' + DATABASE + '\n')
    PG = run_container('postgres', '--network', NET, '--network-alias', 'db', '--cpus', '2', '--memory', '768m',
        '--tmpfs', '/var/lib/postgresql/data:rw,noexec,nosuid,size=512m', '--env-file', str(pg_env), IMAGES['postgres'])
    REDIS = run_container('redis', '--network', NET, '--network-alias', 'redis', '--cpus', '1', '--memory', '128m',
        '--read-only', '--tmpfs', '/data:rw,noexec,nosuid,size=64m', IMAGES['redis'], 'redis-server', '--save', '', '--appendonly', 'no')
    for _ in range(60):
        if docker('exec', PG, 'pg_isready', '-U', 'rpsload', '-d', DATABASE, check=False).returncode == 0:
            break
        time.sleep(1)
    else:
        raise AssertionError('Disposable PostgreSQL readiness exceeded')
    env = {
        'SPRING_PROFILES_ACTIVE': 'test', 'SPRING_DATASOURCE_URL': 'jdbc:postgresql://db:5432/' + DATABASE,
        'SPRING_DATASOURCE_USERNAME': 'rpsload', 'SPRING_DATASOURCE_PASSWORD': password,
        'SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE': '10', 'SPRING_DATA_REDIS_HOST': 'redis',
        'JWT_SECRET': secrets.token_urlsafe(48), 'OTP_DEV_MODE': 'true', 'OTP_PROVIDER': 'dev',
        'POSM_STAFF_AUTH_ENABLED': 'true', 'POSM_STAFF_REGISTRATION_SMS_REQUIRED': 'false', 'POSM_LEGACY_DEVICE_KEY_ENABLED': 'false',
        'PROMO_PRICE_REFRESH_CRON': '-', 'ANALYTICS_METRICS_REFRESH_CRON': '-', 'STANDARDN_INITIAL_DELAY_MS': '86400000',
        'MEDUSA_SNAPSHOT_INITIAL_DELAY_MS': '86400000', 'SENTRY_DSN': '', 'APP_PUBLIC_BASE_URL': 'http://127.0.0.1:8080',
        'DARIBAR_OTP_BASE_URL': 'http://127.0.0.1:9', 'P1SMS_BASE_URL': 'http://127.0.0.1:9',
        'LOGGING_LEVEL_KZ_EPHARM': 'INFO', 'LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_SECURITY': 'WARN',
        'SERVER_ADDRESS': '0.0.0.0', 'JAVA_TOOL_OPTIONS': '-Xmx768m -XX:MaxMetaspaceSize=384m -XX:ActiveProcessorCount=2',
    }
    for key in ('STANDARDN_DIRECTORY_ENABLED', 'STOREFRONT_CATALOG_ENABLED', 'MEDUSA_ENABLED', 'MERCH_AUTOMATION_ENABLED',
        'MERCH_PLACEMENT_EXPIRY_ENABLED', 'MERCH_TASKS_ENABLED', 'MERCH_REWARDS_ENABLED', 'MERCH_NATIVE_DELIVERY_ENABLED',
        'MERCH_NATIVE_EXECUTOR_ENABLED', 'MERCH_STAFF_ACCESS_ENABLED', 'MERCH_PRODUCER_AUTH_ENABLED',
        'MERCH_LEGACY_PORTAL_CUTOVER_ENABLED', 'FULFILLMENT_ENABLED', 'FULFILLMENT_DEVICE_REGISTRATION_ENABLED',
        'POSM_SALE_BONUS_ENABLED', 'LEARNER_CREDENTIALS_ENABLED'):
        env[key] = 'false'
    env_path = ROOT / 'app.private.env'
    env_path.write_text(''.join(key + '=' + value + '\n' for key, value in env.items()))
    (ROOT / 'results').mkdir(mode=0o700)
    (ROOT / 'bin').mkdir(mode=0o700)
    wrapper = ROOT / 'bin/docker'
    wrapper.write_text('#!/bin/sh\nif [ "$1" = run ]; then\n shift\n exec "$QA_DOCKER" run --name "$RPS_OWNED_CONTAINER" --label "' + LABEL + '=$QA_OWNER" --cpus 2 --memory 1024m "$@"\nfi\nexec "$QA_DOCKER" "$@"\n')
    wrapper.chmod(0o700)
    REPORT['stage'] = 'compat-old-before'
    start_backend('old-before', loaded['previous'], env_path)
    old_schema = schema()
    sql((ROOT / 'fixture/seed.private.sql').read_text())
    assert sql("SELECT count(*) FROM pharmacists WHERE name LIKE 'RPS Synthetic %';") == '0', 'Auth warmup/profiles forbidden'
    stop_backend()
    REPORT['stage'] = 'compat-candidate-forward'
    start_backend('candidate', loaded['candidate'], env_path)
    assert schema() == old_schema
    assert sql("SELECT count(*) FROM pharmacists WHERE name LIKE 'RPS Synthetic %';") == '0'
    REPORT['fixture'] = {'desks': 500, 'runId': run_id, 'database': DATABASE, 'freshProfiles': True, 'authWarmup': False, 'retries': 0}
    for mode in ('register', 'login', 'sessions'):
        phase(mode)
    REPORT['stage'] = 'compat-synthetic-nonempty-ledger'
    # Add a nonempty, consistent synthetic checkpoint/ledger only AFTER all load verifications.
    iin = fixture['devices'][0]['iin']
    sql("BEGIN; DO $$ BEGIN IF current_database() <> '" + DATABASE + "' THEN RAISE EXCEPTION 'Wrong fixture DB'; END IF; END $$;\n" +
        "INSERT INTO pharmacist_wallet_checkpoints(pharmacist_id,balance_kzt,earned_30d_kzt) SELECT id,0,0 FROM pharmacists WHERE iin='" + iin + "';\n" +
        "INSERT INTO pharmacist_wallet_entries(id,pharmacist_id,source,source_id,amount_kzt,earned_delta_kzt,context_key,balance_after_kzt) SELECT '" + str(uuid.uuid4()) + "',id,'admin_adjustment','synthetic-compat',125,0,'synthetic-only',125 FROM pharmacists WHERE iin='" + iin + "';\n" +
        "UPDATE pharmacists SET balance=125 WHERE iin='" + iin + "'; COMMIT;")
    before = financial_fingerprint()
    assert before['walletRows'] == 1 and before['walletAmountKzt'] == 125 and before['checkpointRows'] == 1 and before['mappingRows'] == 0
    context = {'processInstanceId': OWNER + ':cross-version'}
    desk = fixture['devices'][0]
    grant = session_request('device/login', {'iin': desk['iin'], 'password': desk['password'], 'deviceContext': context}, desk)
    assert grant['status'] == 'authenticated' and grant['deviceContext']['processInstanceId'] == context['processInstanceId']
    assert grant['deviceContext'].get('standardNContext') is None and grant['pharmacist']['name'] == desk['name']
    token = grant['sessionToken']
    stop_backend()
    REPORT['stage'] = 'compat-old-after'
    start_backend('old-after', loaded['previous'], env_path)
    assert schema() == old_schema and financial_fingerprint() == before
    check = session_request('device/session/check', context, desk, token)
    assert check['status'] == 'authenticated' and check['pharmacist']['id'] == grant['pharmacist']['id']
    assert check['deviceContext']['processInstanceId'] == context['processInstanceId'] and check['deviceContext'].get('standardNContext') is None
    session_request('device/logout', None, desk, token, expected=204)
    assert financial_fingerprint() == before
    assert sql('SELECT count(*) FROM posm_device_staff_sessions WHERE revoked_at IS NULL AND expires_at>now();') == '0'
    REPORT['compatibility'].update({'outcome': 'PASS', 'normalFlyway': True, 'schemaUnchanged': True,
        'candidateGrantAcceptedByPrevious': True, 'logoutAcceptedByPrevious': True, 'financialDataBeforeAfter': before})
    REPORT['outcome'] = 'PASS'
    REPORT['stage'] = 'complete'


def cleanup():
    removed = list(REPORT.get('cleanup', {}).get('removedContainerIds', []))
    errors = []
    # A late-created k6 container can outlive its Docker client after interruption.
    for identifier in docker('ps', '-aq', '--no-trunc', '--filter', 'label=' + LABEL + '=' + OWNER).stdout.splitlines():
        item = inspect(identifier)
        if identifier not in OWNED and item['Config']['Labels'].get(LABEL) == OWNER and item['Name'].startswith('/' + OWNER + '-'):
            OWNED.append(identifier)
            REPORT['resources'].append({'role': 'late-owned-cleanup', 'containerId': identifier, 'imageId': item['Image']})
    for identifier in reversed(OWNED):
        state = docker('inspect', identifier, check=False)
        if state.returncode:
            continue  # --rm k6 was already removed by Docker.
        item = json.loads(state.stdout)[0]
        if item['Config']['Labels'].get(LABEL) != OWNER:
            errors.append('Ownership changed for recorded container')
            continue
        logs = docker('logs', '--timestamps', identifier, check=False)
        (ROOT / (identifier + '-logs.private.txt')).write_text(logs.stdout + logs.stderr)
        result = docker('rm', '-f', '-v', identifier, check=False)
        (removed if result.returncode == 0 else errors).append(identifier)
    if NET:
        result = docker('network', 'inspect', NET, check=False)
        network = json.loads(result.stdout)[0] if result.returncode == 0 else None
        if network is None:
            pass
        elif network['Labels'].get(LABEL) == OWNER and not network['Containers']:
            result = docker('network', 'rm', NET, check=False)
            if result.returncode:
                errors.append('Owned network removal failed')
        else:
            errors.append('Owned network not empty or ownership changed')
    remaining = docker('ps', '-aq', '--filter', 'label=' + LABEL + '=' + OWNER).stdout.splitlines()
    REPORT['cleanup'] = {'removedContainerIds': removed, 'ownedContainersRemaining': remaining, 'errors': errors}
    if errors or remaining:
        REPORT['outcome'] = 'FAIL'
    write('assessment.json', REPORT)


if __name__ == '__main__':
    if sys.argv[1:] == ['--cleanup-only']:
        path = ROOT / 'assessment.json'
        if not path.exists():
            raise SystemExit(0)
        assert sys.platform.startswith('linux') and os.environ.get('GITHUB_ACTIONS') == 'true'
        REPORT = json.loads(path.read_text())
        OWNER = REPORT['owner']
        assert re.fullmatch(r'pr160-[a-f0-9]{12}', OWNER)
        OWNED = [item['containerId'] for item in REPORT['resources']]
        assert all(re.fullmatch(r'[a-f0-9]{64}', item) for item in OWNED)
        NET = REPORT.get('networkId')
        assert NET is None or re.fullmatch(r'[a-f0-9]{64}', NET)
        cleanup()
        raise SystemExit(1 if REPORT['cleanup']['errors'] or REPORT['cleanup']['ownedContainersRemaining'] else 0)
    try:
        main()
    except Exception as error:
        REPORT['outcome'] = 'FAIL'
        REPORT['failureType'] = type(error).__name__
        if isinstance(error, AssertionError):
            REPORT['failureMessage'] = str(error)
        # Do not print failed command arguments or HTTP payloads containing synthetic secrets.
        print('Isolated QA failed: ' + type(error).__name__, file=sys.stderr)
    finally:
        cleanup()
    print(json.dumps({'outcome': REPORT['outcome'], 'phases': {key: value['outcome'] for key, value in REPORT['phases'].items()}, 'cleanup': REPORT.get('cleanup')}))
    raise SystemExit(0 if REPORT['outcome'] == 'PASS' else 1)
