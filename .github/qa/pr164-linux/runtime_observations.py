"""Owned-run production-budget checks and numeric diagnostics; no product instrumentation."""
import re
import pathlib
import hashlib
import math


def ddl_block_fingerprints(canonical_sql):
    headers = list(re.finditer(r'(?m)^--\n-- Name: [^\n]+; Type: [^\n]+; Schema: [^\n]+; Owner: [^\n]*\n--\n', canonical_sql))
    footers = list(re.finditer(r'(?m)^--\n-- PostgreSQL database dump complete\n--(?:\n|$)', canonical_sql))
    if not headers or len(footers) != 1 or footers[0].start() <= headers[-1].start():
        return {'supportedDumpBlockLayout': False}
    footer = footers[0].start()
    blocks = [canonical_sql[header.start():headers[i+1].start() if i+1 < len(headers) else footer].encode('utf-8') for i, header in enumerate(headers)]
    digest = hashlib.sha256()
    for block in sorted(blocks):
        digest.update(len(block).to_bytes(8, 'big'))
        digest.update(block)
    return {'supportedDumpBlockLayout': True, 'objectBlockCount': len(blocks), 'sortedIntactObjectBlocksSha256': digest.hexdigest(),
            'headerSha256': hashlib.sha256(canonical_sql[:headers[0].start()].encode('utf-8')).hexdigest(),
            'footerSha256': hashlib.sha256(canonical_sql[footer:].encode('utf-8')).hexdigest()}


def safe_helper_traceback(error):
    root = pathlib.Path(__file__).resolve().parent
    frames = []
    cursor = error.__traceback__
    while cursor is not None:
        code = cursor.tb_frame.f_code
        path = pathlib.Path(code.co_filename).resolve()
        if path.parent == root and re.fullmatch(r'[A-Za-z0-9_-]+\.py', path.name) and re.fullmatch(r'[A-Za-z0-9_<>-]{1,128}', code.co_name):
            frames.append({'helper': path.name, 'function': code.co_name, 'line': cursor.tb_lineno})
        cursor = cursor.tb_next
    return frames[-32:]


def fingerprint_comparison(before, after):
    fields = ('schema99', 'ddlSha256', 'allPublicDataSha256', 'sequenceCountersSha256',
              'publicTableCount', 'publicRowCount', 'sequenceCount', 'pharmacists', 'deviceSessions')
    def safe(values):
        result = dict.fromkeys(fields)
        for key, value in values.items():
            if key in fields[:4] and isinstance(value, str) and re.fullmatch('[a-f0-9]{64}', value):
                result[key] = value
            if key in fields[4:] and type(value) is int and value >= 0:
                result[key] = value
        return result
    return {'before': safe(before), 'after': safe(after),
            'failedFields': [key for key in fields if before.get(key) != after.get(key)],
            'unexpectedFieldCount': len((set(before) | set(after)) - set(fields))}


def cpu_plan(available):
    cpus = sorted(set(available))
    assert len(cpus) == 4 and all(type(x) is int and x >= 0 for x in cpus), 'Exactly four available logical CPUs required'
    return {'availableLogicalCpuIds': cpus, 'availableLogicalCpuCount': 4,
            'affinityApplied': False, 'physicalIsolationClaimed': False}


def numeric_cpu_topology(text, available):
    result = []
    fields = {'processor': 'logicalCpu', 'physical id': 'packageId', 'core id': 'coreId',
              'cpu family': 'family', 'model': 'model', 'stepping': 'stepping',
              'cpu cores': 'reportedCores', 'siblings': 'reportedSiblings'}
    for block in text.split('\n\n'):
        values = dict((key.strip(), value.strip()) for line in block.splitlines() if ':' in line for key, value in [line.split(':', 1)])
        if values.get('processor', '').isdigit() and int(values['processor']) in available:
            result.append({name: int(values[key]) for key, name in fields.items() if values.get(key, '').isdigit()})
    assert sorted(row['logicalCpu'] for row in result) == sorted(available)
    return result


def require_limits(item, role, plan):
    if role.startswith('artifact-extraction-'):
        return
    assert plan['availableLogicalCpuCount'] == 4 and plan['affinityApplied'] is False
    if role.startswith('backend-'):
        memory, cpus = 2048, 4
        options = [entry for entry in item['Config']['Env'] if entry.startswith('JAVA_TOOL_OPTIONS=')]
        assert options == ['JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=65.0'], 'Unexpected backend JVM budget'
        assert not any(entry.split('=', 1)[0] in ('JDK_JAVA_OPTIONS', '_JAVA_OPTIONS') and entry.split('=', 1)[-1]
                       for entry in item['Config']['Env']), 'Unexpected additional JVM options'
        env = dict(entry.split('=', 1) for entry in item['Config']['Env'] if '=' in entry)
        assert env.get('SMS_MAX_CONCURRENT_TRANSACTIONS', '4') == '4', 'Admission must retain its four permits'
    elif role == 'postgres':
        memory, cpus = 768, 2
    elif role == 'redis':
        memory, cpus = 128, 1
    else:
        assert role.startswith('k6-'), 'Unknown runtime role'
        memory, cpus = 1024, 2
    limits = item['HostConfig']
    assert limits['Memory'] == memory * 1024**2 and limits['NanoCpus'] == cpus * 1_000_000_000
    assert limits['CpusetCpus'] == '', 'CPU affinity must remain unset'


def backend_preflight(prometheus, cpu_max, memory_max):
    def metric(name):
        return list(map(float, re.findall(r'^' + name + r'(?:\{[^\n]*\})? ([0-9.eE+-]+)$', prometheus, re.M)))
    quota, period = map(int, cpu_max.split())
    memory = int(memory_max.strip())
    assert quota > 0 and period > 0 and quota == 4 * period and memory == 2048 * 1024**2
    assert metric('system_cpu_count') == [4.0]
    assert metric('hikaricp_connections_max') == [10.0]
    heap = [float(value) for labels, value in re.findall(r'^jvm_memory_max_bytes\{([^\n]*)\} ([0-9.eE+-]+)$', prometheus, re.M)
            if re.search(r'(?:^|,)area="heap"(?:,|$)', labels) and float(value) > 0]
    assert heap and all(math.isfinite(value) for value in heap), 'Finite positive heap pool maximum evidence required'
    return {'systemCpuCount': 4, 'hikariMaximumPoolSize': 10, 'cgroupCpuQuota': quota,
            'cgroupCpuPeriod': period, 'cgroupMemoryMaxBytes': memory, 'positiveHeapPoolMaxBytes': heap,
            'heapPoolValuesAreNotTotalHeapMax': True}


ROUTES = {
    '/api/posm/staff/device/register-without-sms': 'DEVICE_REGISTER',
    '/api/posm/staff/device/login': 'DEVICE_LOGIN',
    '/api/posm/staff/device/session/check': 'DEVICE_CHECK',
    '/api/posm/staff/device/logout': 'DEVICE_LOGOUT',
    '/api/posm/staff/capabilities': 'CAPABILITIES',
    '/api/posm/heartbeat': 'HEARTBEAT', '/api/posm/playlists/active': 'PLAYLIST', '/api/posm/recommend': 'RECOMMEND',
    '/api/mobile/auth/login': 'LEARNER_LOGIN', '/api/mobile/training': 'LEARNER_TRAINING',
}
EXCEPTIONS = ('AppException', 'SQLTransientConnectionException', 'SQLTimeoutException', 'PSQLException',
              'CannotCreateTransactionException', 'CannotGetJdbcConnectionException', 'CannotAcquireLockException',
              'PessimisticLockingFailureException', 'CannotSerializeTransactionException', 'DataAccessResourceFailureException',
              'UnexpectedRollbackException', 'TransactionSystemException', 'IllegalStateException',
              'IllegalArgumentException', 'NullPointerException', 'RejectedExecutionException', 'OutOfMemoryError')


def http_counts(text):
    counts = {}
    for labels, value in re.findall(r'^http_server_requests_seconds_count\{([^\n]*)\} ([0-9.eE+-]+)$', text, re.M):
        tags = dict(re.findall(r'(\w+)="([^"\\]*)"', labels))
        status = tags.get('status', '')
        if not re.fullmatch('[1-5][0-9]{2}', status):
            continue
        exception = tags.get('exception', tags.get('error', 'none')).rsplit('.', 1)[-1]
        bucket = exception if exception in EXCEPTIONS else ('NONE' if exception.lower() in ('none', 'null') else 'OTHER')
        key = (ROUTES.get(tags.get('uri'), 'OTHER'), int(status), bucket)
        counts[key] = counts.get(key, 0) + float(value)
    return counts


def http_delta(before, after):
    result = []
    old, new = http_counts(before), http_counts(after)
    for key, count in sorted(new.items()):
        delta = count - old.get(key, 0)
        assert delta >= 0
        if delta:
            result.append({'routeBucket': key[0], 'status': key[1], 'exceptionBucket': key[2], 'count': delta})
    return {'metricPresent': bool(new), 'counts': result}


def jvm_gc(text):
    names = ('jvm_gc_pause_seconds_count', 'jvm_gc_pause_seconds_sum', 'jvm_gc_pause_seconds_max',
             'jvm_gc_memory_allocated_bytes_total', 'jvm_gc_memory_promoted_bytes_total', 'jvm_memory_used_bytes')
    return {name: list(map(float, re.findall(r'^' + name + r'(?:\{[^\n]*\})? ([0-9.eE+-]+)$', text, re.M))) for name in names}


def log_taxonomy(text):
    result = {'unhandledEventMarkers': text.count('Необработанное исключение'), 'topLevelClassCounts': {}, 'causedByClassCounts': {}, 'occurrencesAreNotRequestCounts': True}
    for causal, qualified in re.findall(r'^(Caused by: )?([a-zA-Z_$][\w.$]*(?:Exception|Error))(?::|\s|$)', text, re.M):
        name = qualified.rsplit('.', 1)[-1]
        bucket = name if name in EXCEPTIONS else 'OTHER'
        field = 'causedByClassCounts' if causal else 'topLevelClassCounts'
        result[field][bucket] = result[field].get(bucket, 0) + 1
    return result
