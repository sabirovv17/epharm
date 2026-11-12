"""Owned-run CPU placement and numeric diagnostics; no product instrumentation."""
import re


def cpu_plan(available):
    cpus = sorted(set(available))
    assert len(cpus) >= 4 and all(type(x) is int and x >= 0 for x in cpus), 'Four available logical CPUs required'
    return {'backend': cpus[:2], 'support': cpus[2:4], 'availableLogicalCpuCount': len(cpus), 'physicalIsolationClaimed': False}


def cpuset_text(cpus):
    return ','.join(map(str, cpus))


def cpuset_values(text):
    assert re.fullmatch(r'[0-9]+(?:-[0-9]+)?(?:,[0-9]+(?:-[0-9]+)?)*', text)
    result = set()
    for part in text.split(','):
        bounds = list(map(int, part.split('-')))
        assert len(bounds) == 1 or bounds[0] <= bounds[1] <= bounds[0] + 256
        result.update(range(bounds[0], bounds[-1] + 1))
    return result


def require_limits(item, role, plan):
    if role.startswith('artifact-extraction-'):
        return
    if role.startswith('backend-'):
        memory, cpus, placement = 1536, 2, 'backend'
    elif role == 'postgres':
        memory, cpus, placement = 768, 2, 'support'
    elif role == 'redis':
        memory, cpus, placement = 128, 1, 'support'
    else:
        assert role.startswith('k6-'), 'Unknown runtime role'
        memory, cpus, placement = 1024, 2, 'support'
    limits = item['HostConfig']
    assert limits['Memory'] == memory * 1024**2 and limits['NanoCpus'] == cpus * 1_000_000_000
    assert cpuset_values(limits['CpusetCpus']) == set(plan[placement]), 'Actual CPU placement mismatch'


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
