#!/usr/bin/env python3
"""Download and verify two official release artifacts; token is host-only."""
import datetime as dt
import hashlib
import json
import os
import pathlib
import re
import sys
import tarfile
import urllib.error
import urllib.parse
import urllib.request
import zipfile

os.umask(0o077)
REPO = 'sabirovv17/epharm'
API = 'https://api.github.com'
EXPECTED = {'app.jar', 'backend.tar.gz', 'frontend.tar.gz', 'source.tar.gz', 'source-files.json', 'provenance.json', 'SHA256SUMS'}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def sha(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def api(path):
    request = urllib.request.Request(API + '/repos/' + REPO + path, headers={
        'Authorization': 'Bearer ' + os.environ['GITHUB_TOKEN'],
        'Accept': 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28',
    })
    with urllib.request.build_opener(NoRedirect()).open(request, timeout=60) as response:
        body = response.read(2 * 1024**2 + 1)
    assert len(body) <= 2 * 1024**2
    return json.loads(body)


def anonymous_blob_request(location):
    parts = urllib.parse.urlsplit(location)
    assert parts.scheme == 'https' and parts.hostname and parts.hostname.endswith('.blob.core.windows.net')
    assert parts.port in (None, 443) and parts.username is None and parts.password is None and not parts.fragment
    # No bearer header, and no secondary redirect is accepted from the CDN.
    return urllib.request.build_opener(NoRedirect()).open(urllib.request.Request(location), timeout=180)


def fetch(version, commit, run_id, root, expected_artifact_id):
    assert re.fullmatch(r'[a-f0-9]{40}', commit) and re.fullmatch(r'[0-9]+', run_id)
    run = api('/actions/runs/' + run_id)
    workflow = api('/actions/workflows/release-images.yml')
    assert run['repository']['full_name'] == REPO
    assert run['head_repository']['full_name'] == REPO
    assert run['head_sha'] == commit and run['head_branch'] == 'main'
    assert run['event'] == 'workflow_dispatch' and run['status'] == 'completed' and run['conclusion'] == 'success'
    assert run['path'] == '.github/workflows/release-images.yml' and run['workflow_id'] == workflow['id']
    name = 'epharm-images-' + version + '-' + commit
    artifacts = api('/actions/runs/' + run_id + '/artifacts?per_page=100')
    assert artifacts['total_count'] <= 100
    matches = [item for item in artifacts['artifacts'] if item['name'] == name]
    assert len(matches) == 1
    artifact = matches[0]
    assert artifact['id'] == expected_artifact_id
    assert not artifact['expired']
    assert dt.datetime.fromisoformat(artifact['expires_at'].replace('Z', '+00:00')) > dt.datetime.now(dt.timezone.utc)
    assert re.fullmatch(r'sha256:[a-f0-9]{64}', artifact['digest'])
    assert artifact['workflow_run']['id'] == int(run_id) and artifact['workflow_run']['head_sha'] == commit
    target = root / version
    target.mkdir(mode=0o700)
    archive = target / 'official.zip'
    request = urllib.request.Request(API + '/repos/' + REPO + '/actions/artifacts/' + str(artifact['id']) + '/zip', headers={
        'Authorization': 'Bearer ' + os.environ['GITHUB_TOKEN'], 'Accept': 'application/vnd.github+json',
    })
    # Follow the signed blob URL without forwarding GitHub's Authorization header.
    try:
        response = urllib.request.build_opener(NoRedirect()).open(request, timeout=60)
    except urllib.error.HTTPError as error:
        assert error.code in (301, 302, 303, 307)
        location = error.headers['Location']
        response = anonymous_blob_request(location)
    total = 0
    with response, archive.open('xb') as output:
        while block := response.read(1024 * 1024):
            total += len(block)
            assert total < 4 * 1024**3
            output.write(block)
    assert total == artifact['size_in_bytes'] and 'sha256:' + sha(archive) == artifact['digest']
    with zipfile.ZipFile(archive) as zipped:
        entries = zipped.infolist()
        assert len(entries) == len(EXPECTED) and {entry.filename for entry in entries} == EXPECTED
        assert sum(entry.file_size for entry in entries) < 6 * 1024**3
        for entry in entries:
            assert entry.file_size < 3 * 1024**3 and (entry.external_attr >> 16) & 0o170000 != 0o120000
            with zipped.open(entry) as source, (target / entry.filename).open('xb') as output:
                while block := source.read(1024 * 1024):
                    output.write(block)
    hashes = {}
    for line in (target / 'SHA256SUMS').read_text().splitlines():
        digest, name = line.split('  ', 1)
        assert name in EXPECTED - {'SHA256SUMS'} and name not in hashes and re.fullmatch(r'[a-f0-9]{64}', digest)
        assert sha(target / name) == digest
        hashes[name] = digest
    assert set(hashes) == EXPECTED - {'SHA256SUMS'}
    provenance = json.loads((target / 'provenance.json').read_text())
    assert provenance['releaseId'] == version and provenance['commit'] == commit
    assert str(provenance['runId']) == run_id and int(provenance['runAttempt']) == run['run_attempt']
    for component in ('backend', 'frontend'):
        assert provenance['images'][component]['tag'] == 'epharm/' + component + ':' + version
    source_hashes = json.loads((target / 'source-files.json').read_text())
    with tarfile.open(target / 'source.tar.gz') as source:
        actual = {}
        for member in source.getmembers():
            path = pathlib.PurePosixPath(member.name)
            assert not path.is_absolute() and '..' not in path.parts and (member.isdir() or member.isfile())
            if member.isfile():
                assert member.name not in actual and not path.name.startswith('.env')
                actual[member.name] = hashlib.sha256(source.extractfile(member).read()).hexdigest()
        assert actual == source_hashes
    archive.unlink()  # Preserve the verified payload, not a duplicate multi-hundred-MB ZIP.
    return {'version': version, 'commit': commit, 'runId': int(run_id), 'runAttempt': run['run_attempt'],
            'artifactId': artifact['id'], 'artifactDigest': artifact['digest'], 'artifactBytes': total,
            'artifactExpiresAt': artifact['expires_at'], 'sha256': hashes, 'directory': str(target)}


def main():
    assert os.environ.get('GITHUB_REPOSITORY') == REPO
    config = json.loads(pathlib.Path(os.environ['QA_ARTIFACT_CONFIG']).read_text())
    assert set(config) == {'previous', 'candidate'}
    assert config['candidate']['commit'] == os.environ['CANDIDATE_COMMIT']
    for item in config.values():
        assert re.fullmatch(r'v[0-9]+[.][0-9]+[.][0-9]+', item['version'])
        assert re.fullmatch(r'[a-f0-9]{40}', item['commit'])
        assert isinstance(item['artifactId'], int) and item['artifactId'] > 0
        assert isinstance(item['imagesRun'], int) and item['imagesRun'] > 0
    root = pathlib.Path(os.environ['QA_PRIVATE_ROOT']) / 'artifacts'
    root.mkdir(mode=0o700, parents=True, exist_ok=False)
    identities = {role: fetch(item['version'], item['commit'], str(item['imagesRun']), root, item['artifactId']) for role, item in config.items()}
    (root / 'verified-identities.json').write_text(json.dumps(identities, indent=2) + chr(10))
    print(json.dumps({'outcome': 'official-artifacts-verified', 'artifacts': {
        key: {name: value[name] for name in ('version', 'commit', 'runId', 'runAttempt', 'artifactId', 'artifactDigest')}
        for key, value in identities.items()}}))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # HTTP exception text may contain a signed download URL. Never print it.
        print('Official artifact verification failed: ' + type(error).__name__, file=sys.stderr)
        raise SystemExit(1)
