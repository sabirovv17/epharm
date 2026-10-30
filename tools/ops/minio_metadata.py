#!/usr/bin/env python3
"""Lossless S3 object metadata companion to the mc body/policy backup (stdlib only).

Capture is read-only against S3. Restore is fenced to an inspected, isolated,
synthetically authenticated restore container. ETag/LastModified are witnesses,
not restored fields. Credentials are environment-only; errors never print them.
"""
import argparse
import datetime
import hashlib
import hmac
import ipaddress
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

NAME = 'object-metadata.json'
MAX_MANIFEST = 32 * 1024 * 1024
MAX_OBJECTS = 100000
CHUNK = 1024 * 1024
HEADERS = {'content-type', 'content-disposition', 'content-encoding', 'cache-control',
           'content-language', 'expires'}
BUCKET = re.compile(r'[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]\Z')
RESTORE_NAME = re.compile(r'epharm-restore-minio-[0-9]{8}T[0-9]{6}Z-[0-9]+(?:-[a-z0-9]+)?\Z')
HEX = re.compile(r'[a-f0-9]{64}\Z')


def require(condition, message):
    if not condition:
        raise ValueError(message)


def text(value, limit=8192):
    require(isinstance(value, str) and len(value.encode('utf8')) <= limit
            and not any(ord(c) < 32 or ord(c) == 127 for c in value), 'Invalid text field')
    return value


def key_path(value):
    text(value, 1024)
    path = PurePosixPath(value)
    require(value and not value.startswith('/') and '\\' not in value
            and all(p not in ('', '.', '..') for p in value.split('/'))
            and path.as_posix() == value, 'Unsafe object key')
    return path


def pairs(items):
    result = {}
    for key, value in items:
        require(key not in result, 'Duplicate JSON key')
        result[key] = value
    return result


def metadata_value(body):
    require(0 < len(body) <= MAX_MANIFEST, 'Invalid metadata manifest size')
    value = json.loads(body, object_pairs_hook=pairs,
                       parse_constant=lambda _: (_ for _ in ()).throw(ValueError('Nonfinite JSON')))
    require(isinstance(value, dict) and set(value) == {'schemaVersion', 'capturedAt',
            'sourceContainerId', 'buckets', 'objectCount', 'totalBytes', 'objects'}, 'Invalid metadata schema')
    require(type(value['schemaVersion']) is int and value['schemaVersion'] == 1, 'Unsupported metadata version')
    text(value['capturedAt']); require(HEX.fullmatch(value['sourceContainerId']) is not None, 'Invalid source identity')
    buckets = value['buckets']
    require(isinstance(buckets, list) and buckets and all(isinstance(b, str) and BUCKET.fullmatch(b) for b in buckets)
            and buckets == sorted(set(buckets)), 'Invalid bucket inventory')
    objects = value['objects']
    require(isinstance(objects, list) and len(objects) <= MAX_OBJECTS, 'Invalid object inventory')
    seen, total = set(), 0
    for row in objects:
        require(isinstance(row, dict) and set(row) == {'bucket', 'key', 'bytes', 'sha256',
                'metadata', 'tags', 'etag', 'lastModified'}, 'Invalid object schema')
        require(row['bucket'] in buckets, 'Unexpected object bucket'); key_path(row['key'])
        identity = (row['bucket'], row['key'])
        require(identity not in seen, 'Duplicate object'); seen.add(identity)
        require(type(row['bytes']) is int and 0 <= row['bytes'] <= 5 * 1024**3, 'Invalid object size')
        require(isinstance(row['sha256'], str) and HEX.fullmatch(row['sha256']), 'Invalid object SHA256')
        text(row['etag']); text(row['lastModified'])
        require(row['etag'] and row['lastModified'], 'Missing source witness')
        metadata = row['metadata']
        require(isinstance(metadata, dict) and 'content-type' in metadata and len(metadata) <= 128, 'Invalid headers')
        require(sum(len(k) + len(v) for k, v in metadata.items() if isinstance(k, str) and isinstance(v, str)) <= 16384,
                'Metadata headers too large')
        for k, v in metadata.items():
            require(isinstance(k, str) and (k in HEADERS or re.fullmatch(r'x-amz-meta-[a-z0-9!#$%&\'*+.^_`|~-]+', k)),
                    'Unsupported metadata header')
            text(v); require(all(ord(c) <= 255 for c in v), 'Non-HTTP header value')
        tags = row['tags']; require(isinstance(tags, list) and len(tags) <= 10, 'Invalid object tags')
        keys = []
        for tag in tags:
            require(isinstance(tag, dict) and set(tag) == {'key', 'value'}, 'Invalid tag schema')
            text(tag['key'], 128 * 4); text(tag['value'], 256 * 4)
            require(0 < len(tag['key']) <= 128 and len(tag['value']) <= 256, 'Invalid tag length')
            keys.append(tag['key'])
        require(keys == sorted(set(keys)), 'Duplicate or unsorted tags')
        total += row['bytes']
    require(type(value['objectCount']) is int and value['objectCount'] == len(objects)
            and type(value['totalBytes']) is int and value['totalBytes'] == total, 'Metadata counts mismatch')
    return value


def manifest_rows(stage):
    path = stage / 'manifest.txt'
    require(path.is_file() and not path.is_symlink() and 0 < path.stat().st_size <= 65536, 'Missing/oversized backup manifest')
    rows = {}
    for line in path.read_text().splitlines():
        k, sep, v = line.partition('=')
        require(sep and k not in rows, 'Invalid backup manifest'); rows[k] = v
    return rows


def file_hash(source):
    digest, size = hashlib.sha256(), 0
    for block in iter(lambda: source.read(CHUNK), b''):
        digest.update(block); size += len(block)
    return size, digest.hexdigest()


def local_inventory(stage):
    root = stage / 'data'
    require(root.is_dir() and not root.is_symlink(), 'Missing object data root')
    found = {}
    for path in root.rglob('*'):
        require(not path.is_symlink(), 'Symlink in object data')
        if path.is_dir():
            continue
        require(path.is_file(), 'Nonregular object data')
        relative = path.relative_to(root)
        require(len(relative.parts) >= 2 and BUCKET.fullmatch(relative.parts[0]), 'Invalid data path')
        key = '/'.join(relative.parts[1:]); key_path(key)
        with path.open('rb') as source:
            found[(relative.parts[0], key)] = file_hash(source)
    return found


def validate_stage(stage, allow_legacy=False):
    rows = manifest_rows(stage)
    path = stage / NAME
    if rows.get('minio_metadata_format') != '1':
        require(allow_legacy and 'minio_metadata_format' not in rows and not path.exists(), 'Required metadata manifest missing')
        return None
    require(path.is_file() and not path.is_symlink() and path.stat().st_size <= MAX_MANIFEST, 'Invalid metadata file')
    value = metadata_value(path.read_bytes())
    require(value['buckets'] == sorted(rows.get('buckets', '').replace(' ', '').split(',')), 'Metadata/bucket mismatch')
    require(local_inventory(stage) == {(r['bucket'], r['key']): (r['bytes'], r['sha256']) for r in value['objects']},
            'Metadata/body inventory or hash mismatch')
    return value


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


class S3:
    """SigV4 core adapted from the validated read-only capture helper; no proxies/redirects."""
    def __init__(self, endpoint, access, secret):
        parsed = urllib.parse.urlsplit(endpoint)
        require(parsed.scheme == 'http' and parsed.port == 9000 and parsed.path in ('', '/')
                and not parsed.query and not parsed.fragment and not parsed.username, 'Invalid private S3 endpoint')
        address = ipaddress.ip_address(parsed.hostname)
        require(address.is_private and not address.is_unspecified and not address.is_multicast, 'Nonprivate S3 endpoint')
        require(access and secret, 'Missing S3 credentials')
        self.endpoint, self.host, self.access, self.secret = endpoint.rstrip('/'), parsed.netloc, access, secret
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def api(self, path, query=None, method='GET', headers=None, body=None, digest=False):
        query = urllib.parse.urlencode(sorted((query or {}).items()), quote_via=urllib.parse.quote, safe='~')
        stamp = datetime.datetime.now(datetime.timezone.utc); date = stamp.strftime('%Y%m%d'); now = stamp.strftime('%Y%m%dT%H%M%SZ')
        payload_hash = hashlib.sha256(body or b'').hexdigest()
        signed_headers = {k.lower(): v for k, v in (headers or {}).items()}
        signed_headers.update({'host': self.host, 'x-amz-content-sha256': payload_hash, 'x-amz-date': now})
        signed = ';'.join(sorted(signed_headers))
        # urllib/http.client emits header values as Latin-1 octets. A header
        # read back from S3 may itself contain UTF-8 bytes in that representation;
        # re-encoding the string as UTF-8 signs different bytes from those sent.
        canonical = (method + '\n' + path + '\n' + query + '\n').encode('ascii')
        canonical += b''.join(k.encode('ascii') + b':' + b' '.join(signed_headers[k].encode('latin1').split()) + b'\n'
                              for k in sorted(signed_headers))
        canonical += ('\n' + signed + '\n' + payload_hash).encode('ascii')
        scope = date + '/us-east-1/s3/aws4_request'
        string = 'AWS4-HMAC-SHA256\n' + now + '\n' + scope + '\n' + hashlib.sha256(canonical).hexdigest()
        key = ('AWS4' + self.secret).encode()
        for item in [date, 'us-east-1', 's3', 'aws4_request']:
            key = hmac.new(key, item.encode(), hashlib.sha256).digest()
        signature = hmac.new(key, string.encode(), hashlib.sha256).hexdigest()
        signed_headers['Authorization'] = 'AWS4-HMAC-SHA256 Credential=' + self.access + '/' + scope + ', SignedHeaders=' + signed + ', Signature=' + signature
        request = urllib.request.Request(self.endpoint + path + ('?' + query if query else ''),
                                         headers=signed_headers, method=method, data=body)
        with self.opener.open(request, timeout=60) as response:
            response_headers = {k.lower(): v for k, v in response.headers.items()}
            if digest:
                return file_hash(response), response_headers
            result = response.read(MAX_MANIFEST + 1)
            require(len(result) <= MAX_MANIFEST, 'S3 response too large')
            return result, response_headers

    def list_objects(self, bucket):
        rows, token = [], None
        while True:
            query = {'list-type': '2'}
            if token: query['continuation-token'] = token
            root = ET.fromstring(self.api('/' + bucket, query)[0])
            for x in root.findall('{*}Contents'):
                rows.append({'bucket': bucket, 'key': x.findtext('{*}Key'), 'bytes': int(x.findtext('{*}Size')),
                             'etag': x.findtext('{*}ETag'), 'lastModified': x.findtext('{*}LastModified')})
            require(len(rows) <= MAX_OBJECTS, 'Too many S3 objects')
            if root.findtext('{*}IsTruncated') != 'true': break
            next_token = root.findtext('{*}NextContinuationToken')
            require(next_token and next_token != token, 'Invalid S3 pagination'); token = next_token
        return sorted(rows, key=lambda r: r['key'])

    @staticmethod
    def path(row):
        key_path(row['key'])
        require(BUCKET.fullmatch(row['bucket']) is not None, 'Invalid S3 bucket')
        return '/' + row['bucket'] + '/' + urllib.parse.quote(row['key'], safe='/~')

    def inspect(self, row, hash_body=True):
        path = self.path(row)
        _, headers = self.api(path, method='HEAD')
        require(int(headers['content-length']) == row['bytes'] and headers['etag'] == row['etag'], 'S3 object drift')
        metadata = {k: v for k, v in headers.items() if k in HEADERS or k.startswith('x-amz-meta-')}
        root = ET.fromstring(self.api(path, {'tagging': ''})[0])
        tags = sorted([{'key': t.findtext('{*}Key'), 'value': t.findtext('{*}Value', '')}
                       for t in root.findall('.//{*}Tag')], key=lambda t: t['key'])
        result = {**row, 'metadata': metadata, 'tags': tags}
        if hash_body:
            (size, sha), actual = self.api(path, headers={'if-match': row['etag']}, digest=True)
            require(size == row['bytes'] and actual['etag'] == row['etag'] and
                    metadata == {k: v for k, v in actual.items() if k in HEADERS or k.startswith('x-amz-meta-')}, 'S3 GET drift')
            result['sha256'] = sha
        return result


def capture(stage, s3, container_id):
    manifest = manifest_rows(stage)
    require(manifest.get('minio_metadata_format') == '1' and manifest.get('minio_policy_format') == '1', 'Capture requires versioned metadata/policy manifest')
    buckets = sorted(manifest.get('buckets', '').replace(' ', '').split(','))
    require(buckets and all(BUCKET.fullmatch(b) for b in buckets) and len(set(buckets)) == len(buckets), 'Invalid buckets')
    for bucket in buckets:
        versioning = ET.fromstring(s3.api('/' + bucket, {'versioning': ''})[0])
        require(versioning.findtext('{*}Status') not in ('Enabled', 'Suspended'), 'Versioned buckets require a version-aware backup')
    rows = [r for b in buckets for r in s3.list_objects(b)]
    require(len(rows) <= MAX_OBJECTS, 'Too many S3 objects')
    objects = [s3.inspect(r) for r in rows]
    require(rows == [r for b in buckets for r in s3.list_objects(b)], 'S3 inventory drift')
    for original, row in zip(objects, rows):
        again = s3.inspect(row, hash_body=False)
        require({k: v for k, v in original.items() if k != 'sha256'} == again, 'S3 metadata/tag drift')
    value = {'schemaVersion': 1, 'capturedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
             'sourceContainerId': container_id, 'buckets': buckets, 'objectCount': len(objects),
             'totalBytes': sum(r['bytes'] for r in objects), 'objects': objects}
    body = json.dumps(value, ensure_ascii=True, separators=(',', ':')).encode()
    metadata_value(body)
    require(local_inventory(stage) == {(r['bucket'], r['key']): (r['bytes'], r['sha256']) for r in objects},
            'mc body differs from captured S3 body')
    target = stage / NAME
    require(not target.exists(), 'Metadata manifest already exists')
    descriptor = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'wb') as out:
        out.write(body); out.flush(); os.fsync(out.fileno())
    return value


def verify_remote(value, s3):
    rows = [r for b in value['buckets'] for r in s3.list_objects(b)]
    require({(r['bucket'], r['key']): r['bytes'] for r in rows} ==
            {(r['bucket'], r['key']): r['bytes'] for r in value['objects']}, 'Restored object inventory mismatch')
    actual = {(r['bucket'], r['key']): s3.inspect(r) for r in rows}
    for expected in value['objects']:
        row = actual[(expected['bucket'], expected['key'])]
        require(all(row[k] == expected[k] for k in ('bytes', 'sha256', 'metadata', 'tags')), 'Restored body/metadata/tags mismatch')


def restore(value, s3):
    # Verify every mirrored body BEFORE the first metadata write. ETags may differ.
    rows = [r for b in value['buckets'] for r in s3.list_objects(b)]
    require({(r['bucket'], r['key']): r['bytes'] for r in rows} ==
            {(r['bucket'], r['key']): r['bytes'] for r in value['objects']}, 'Restore inventory mismatch')
    expected = {(r['bucket'], r['key']): r for r in value['objects']}
    for row in rows:
        require(s3.inspect(row)['sha256'] == expected[(row['bucket'], row['key'])]['sha256'], 'Restored body SHA mismatch')
    for row in rows:
        saved = expected[(row['bucket'], row['key'])]
        headers = {**saved['metadata'], 'x-amz-copy-source': S3.path(row),
                   'x-amz-copy-source-if-match': row['etag'], 'x-amz-metadata-directive': 'REPLACE',
                   'x-amz-tagging-directive': 'REPLACE',
                   'x-amz-tagging': urllib.parse.urlencode([(t['key'], t['value']) for t in saved['tags']],
                                                          quote_via=urllib.parse.quote, safe='~')}
        body, _ = s3.api(S3.path(row), method='PUT', headers=headers, body=b'')
        require(ET.fromstring(body).tag.rsplit('}', 1)[-1] == 'CopyObjectResult', 'S3 copy did not complete')
    verify_remote(value, s3)


def inspect_container(name, expected_id=None, writable=False):
    require(isinstance(name, str) and re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]+', name), 'Invalid container name')
    # Only identity/network fields, never Config.Env/auth values.
    template = '{{json .Id}}\n{{json .Name}}\n{{json .State.Running}}\n{{json .NetworkSettings.Networks}}\n{{json .NetworkSettings.Ports}}\n{{json .HostConfig.NetworkMode}}'
    lines = subprocess.check_output(['docker', 'inspect', '--format', template, name], stderr=subprocess.DEVNULL).decode().splitlines()
    identity, actual_name, running, networks, ports, mode = [json.loads(line) for line in lines]
    require(HEX.fullmatch(identity) and actual_name == '/' + name and running, 'Container identity mismatch')
    if expected_id is not None: require(identity == expected_id, 'Pinned container changed')
    ips = [v['IPAddress'] for v in networks.values() if v.get('IPAddress')]
    if writable:
        require(RESTORE_NAME.fullmatch(name) is not None and expected_id is not None
                and not any(ports.values()), 'Not an owned unexposed restore target')
        require(mode == 'none' or (len(networks) == 1 and next(iter(networks)).startswith('epharm-restore-')),
                'Restore target network not owned')
        if mode != 'none':
            internal = subprocess.check_output(['docker', 'network', 'inspect', '--format', '{{json .Internal}}', next(iter(networks))],
                                               stderr=subprocess.DEVNULL).decode().strip()
            require(internal == 'true', 'Restore network permits external egress')
    require(len(ips) <= 1, 'Ambiguous S3 container network')
    return identity, ('http://' + ips[0] + ':9000' if ips else None)


def run(action, stage, endpoint, container_id, allow_legacy=False):
    if action == 'validate': return validate_stage(stage, allow_legacy)
    value = validate_stage(stage, allow_legacy) if action == 'restore' else None
    if action == 'restore' and value is None: return None
    access = os.environ.get('MINIO_METADATA_ACCESS_KEY', '')
    secret = os.environ.get('MINIO_METADATA_SECRET_KEY', '')
    if action == 'restore':
        require(access == 'restore' and len(secret) >= 16, 'Restore requires the isolated synthetic restore principal')
        require(value['sourceContainerId'] != container_id, 'Refusing metadata mutation of source container')
    s3 = S3(endpoint, access, secret)
    if action == 'capture': return capture(stage, s3, container_id)
    restore(value, s3); return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['capture', 'validate', 'restore'])
    parser.add_argument('--stage', type=Path, required=True)
    parser.add_argument('--container')
    parser.add_argument('--target-id', help='Exact Docker container ID, mandatory for restore')
    parser.add_argument('--python-image', help='Optional cached sha256 Python image; joins only inspected S3 netns (Mac)')
    parser.add_argument('--allow-legacy', action='store_true')
    args = parser.parse_args()
    try:
        stage = args.stage.resolve(strict=True)
        require(stage == args.stage and stage.is_dir() and not args.stage.is_symlink(), 'Use a physical absolute stage')
        if args.action == 'validate':
            value = run(args.action, stage, None, None, args.allow_legacy)
        else:
            # Validate before inspecting/launching any writable target.
            if args.action == 'restore' and validate_stage(stage, args.allow_legacy) is None:
                print(json.dumps({'metadataFormat': 'legacy_not_captured', 'objects': None})); return 0
            identity, endpoint = inspect_container(args.container, args.target_id, args.action == 'restore')
            if args.python_image:
                require(re.fullmatch(r'sha256:[a-f0-9]{64}', args.python_image), 'Use a pinned cached Python image')
                worker = """import sys,json,pathlib
sys.path.insert(0,"/helper")
import minio_metadata as m
try:
 a=json.load(sys.stdin)
 m.run(a["action"],pathlib.Path("/stage"),"http://127.0.0.1:9000",a["id"],a["legacy"])
except (OSError,ValueError,TypeError,KeyError,m.ET.ParseError,RecursionError) as error:
 print("ERROR: MinIO metadata worker refused ("+type(error).__name__+")",file=sys.stderr)
 sys.exit(1)
"""
                cmd = ['docker', 'run', '--rm', '--pull=never', '--network', 'container:' + identity,
                       '-i', '-e', 'MINIO_METADATA_ACCESS_KEY', '-e', 'MINIO_METADATA_SECRET_KEY',
                       '-v', str(Path(__file__).resolve().parent) + ':/helper:ro',
                       '-v', str(stage) + ':/stage' + (':ro' if args.action == 'restore' else ''),
                       args.python_image, 'python3', '-c', worker]
                inspect_container(args.container, identity, args.action == 'restore')
                subprocess.run(cmd, input=json.dumps({'action': args.action, 'id': identity,
                               'legacy': args.allow_legacy}).encode(), check=True, stdout=subprocess.DEVNULL)
                value = validate_stage(stage, args.allow_legacy)
            else:
                require(endpoint is not None, 'No private IP; use a pinned Python netns worker')
                value = run(args.action, stage, endpoint, identity, args.allow_legacy)
        print(json.dumps({'metadataFormat': 1 if value else 'legacy_not_captured',
                          'objects': value['objectCount'] if value else None}))
        return 0
    except (OSError, ValueError, TypeError, KeyError, ET.ParseError, RecursionError, subprocess.SubprocessError) as error:
        print('ERROR: MinIO metadata validation/copy failed (' + type(error).__name__ + ')', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
