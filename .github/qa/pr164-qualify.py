#!/usr/bin/env python3
"""Private branch identity/artifact guard. No builds, VM or production actions."""
import argparse
import copy
import hashlib
import io
import json
import os
import pathlib
import re
import subprocess
import urllib.request
import zipfile

REPO = 'sabirovv17/epharm'
BRANCH = 'refs/heads/codex/pr164-qualification-20261112'
MAIN = 'addb2c97bf752038688e91a6902369da5194009b'
PREVIOUS = '464352af46872de6edcb474b5b78390325a4b076'
BRIDGE_FILES = {'CustomerDisplay.exe', 'CustomerDisplay.dll', 'CustomerDisplay.deps.json', 'CustomerDisplay.runtimeconfig.json', 'QRCoder.dll'}
ROOT = pathlib.Path(__file__).resolve().parents[2]
CONFIG = ROOT / '.github/qa/pr164-candidate.json'


def validate(c):
    assert c['schemaVersion'] == 1
    assert set(c['mode']) == {'windows', 'linux'} and all(type(v) is bool for v in c['mode'].values())
    assert c['mode']['windows'] or c['mode']['linux']
    assert c['exactMain'] == MAIN and c['releaseId'] == 'v0.2.16'
    assert c['main']['commit'] == MAIN and c['main']['version'] == '1.0.85'
    assert c['main']['ciRun'] == 43153930079 and c['main']['ciWindowsJob'] == 130969355166
    assert c['previous']['commit'] == PREVIOUS and c['previous']['version'] == '1.0.84'
    assert c['qualification']['fullMainCiRequiredBeforePromotion'] is True
    assert c['qualification']['windowsOnlyMayStartWhileMainCiRuns'] is True
    for item in (c['main'], c['previous']):
        assert all(type(item[k]) is int and item[k] > 0 for k in ('artifactId', 'ciRun'))
        assert all(re.fullmatch('[a-f0-9]{64}', item[k]) for k in ('outerSha256', 'sha256', 'dllSha256'))
    assert c['server']['previous'] == {'version': 'v0.2.15', 'commit': PREVIOUS, 'imagesRun': 43010008595, 'artifactId': 13850451121}
    candidate = c['server']['candidate']
    assert candidate['version'] == c['releaseId'] and candidate['commit'] == MAIN
    assert all(type(candidate[k]) is int and candidate[k] >= 0 for k in ('artifactId', 'imagesRun'))
    if c['mode']['linux']:
        assert candidate['artifactId'] > 0 and candidate['imagesRun'] > 0, 'Linux must have root-approved official image coordinates'
    return c


def git(*args):
    return subprocess.check_output(['git', '-C', str(ROOT), *args], text=True).strip()


def source_guard():
    subprocess.run(['git', '-C', str(ROOT), 'merge-base', '--is-ancestor', MAIN, 'HEAD'], check=True)
    allowed = ('.github/qa/', 'tools/release/ci-display-')
    for path in git('diff', '--name-only', MAIN, 'HEAD').splitlines():
        assert path == '.github/workflows/release-images.yml' or path.startswith(allowed), 'Private QA branch contains a product/CI change'
    assert git('rev-parse', MAIN + ':.github/workflows/ci.yml') == git('rev-parse', 'HEAD:.github/workflows/ci.yml')
    assert '<Version>1.0.85</Version>' in git('show', MAIN + ':App/CustomerDisplay.csproj')


def api(path):
    token = os.environ.get('GH_TOKEN')
    assert token, 'Read-only token only in guard step'
    request = urllib.request.Request('https://api.github.com/repos/' + REPO + path, headers={
        'Authorization': 'Bearer ' + token, 'Accept': 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28'})
    with urllib.request.urlopen(request, timeout=30) as response:
        body = response.read(2 * 1024**2 + 1)
    assert len(body) <= 2 * 1024**2
    return json.loads(body)


def live_windows(c):
    assert api('/git/ref/heads/main')['object']['sha'] == MAIN, 'Current main moved; re-review required'
    observations = {}
    for role in ('main', 'previous'):
        item = c[role]
        run = api('/actions/runs/' + str(item['ciRun']))
        assert run['repository']['full_name'] == REPO and run['head_repository']['full_name'] == REPO
        assert run['head_sha'] == item['commit'] and run['head_branch'] == 'main' and run['event'] == 'push'
        assert run['path'] == '.github/workflows/ci.yml'
        if role == 'previous':
            assert run['status'] == 'completed' and run['conclusion'] == 'success'
        else:
            assert run['status'] in ('in_progress', 'completed')
            assert run['conclusion'] in (None, 'success'), 'A failed/cancelled main is never accepted'
            job = api('/actions/jobs/' + str(item['ciWindowsJob']))
            assert job['run_id'] == item['ciRun'] and job['head_sha'] == MAIN
            assert job['name'] == 'posm / Windows production build' and job['status'] == 'completed' and job['conclusion'] == 'success'
        artifact = api('/actions/artifacts/' + str(item['artifactId']))
        assert artifact['workflow_run']['id'] == item['ciRun'] and artifact['workflow_run']['head_sha'] == item['commit']
        assert artifact['name'] == 'epharm-posm-' + item['commit'] and not artifact['expired']
        assert artifact['digest'] == 'sha256:' + item['outerSha256']
        observations[role] = {'ciStatus': run['status'], 'ciConclusion': run['conclusion'], 'artifactId': item['artifactId'], 'artifactDigest': artifact['digest']}
    return {'status': 'PASS_FOR_PRIVATE_WINDOWS_QUALIFICATION_ONLY', 'observations': observations, 'fullMainCiRequiredBeforePromotion': True, 'productionPublicationAllowed': False}


def verify_local_bridge(item, directory):
    directory = pathlib.Path(directory)
    outer = directory / 'official-outer.zip'
    assert hashlib.sha256(outer.read_bytes()).hexdigest() == item['outerSha256']
    bridge_name = 'epharm-posm-' + item['version'] + '-' + item['commit'][:12] + '-compat.zip'
    with zipfile.ZipFile(outer) as archive:
        assert archive.testzip() is None
        names = archive.namelist()
        assert set(names) == {bridge_name, bridge_name + '.sha256', 'provenance.json'} and len(names) == 3
        bridge = archive.read(bridge_name)
        metadata = json.loads(archive.read('provenance.json'))
        assert metadata['version'] == item['version'] and metadata['commit'] == item['commit'] and metadata['sha256'] == item['sha256']
    assert hashlib.sha256(bridge).hexdigest() == item['sha256']
    with zipfile.ZipFile(io.BytesIO(bridge)) as archive:
        assert archive.testzip() is None and set(archive.namelist()) == BRIDGE_FILES and len(archive.infolist()) == 5
        assert hashlib.sha256(archive.read('CustomerDisplay.dll')).hexdigest() == item['dllSha256']


def self_test(c):
    validate(c)
    for mutate in (lambda x: x['mode'].update(windows='true'), lambda x: (x['mode'].update(linux=True), x['server']['candidate'].update(imagesRun=0, artifactId=0)), lambda x: x.update(exactMain=PREVIOUS), lambda x: x['main'].update(version='1.0.84'), lambda x: x['qualification'].update(fullMainCiRequiredBeforePromotion=False)):
        changed = copy.deepcopy(c)
        mutate(changed)
        try:
            validate(changed)
        except (AssertionError, KeyError):
            continue
        raise AssertionError('Invalid private config was accepted')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=('self-test', 'guard', 'live-windows', 'linux-config', 'local-bridges'))
    parser.add_argument('--main-dir')
    parser.add_argument('--previous-dir')
    args = parser.parse_args()
    c = validate(json.loads(CONFIG.read_text()))
    if args.mode == 'self-test':
        self_test(c)
    elif args.mode == 'local-bridges':
        source_guard()
        verify_local_bridge(c['main'], args.main_dir)
        verify_local_bridge(c['previous'], args.previous_dir)
    else:
        assert os.environ.get('GITHUB_REPOSITORY') == REPO and os.environ.get('GITHUB_REF') == BRANCH
        assert os.environ.get('GITHUB_EVENT_NAME') == 'workflow_dispatch'
        assert os.environ.get('EXACT_COMMIT') == MAIN and os.environ.get('RELEASE_ID') == c['releaseId']
        source_guard()
        assert not git('status', '--porcelain', '--untracked-files=normal'), 'Private runtime checkout must be clean'
        if args.mode == 'guard':
            with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
                output.write('windows=' + str(c['mode']['windows']).lower() + '\nlinux=' + str(c['mode']['linux']).lower() + '\n')
        elif args.mode == 'live-windows':
            proof = pathlib.Path(os.environ['RUNNER_TEMP']) / 'display-pilot-proof'
            proof.mkdir(exist_ok=False)
            (proof / 'official-artifact-guard.json').write_text(json.dumps(live_windows(c), indent=2) + '\n')
        elif args.mode == 'linux-config':
            assert c['mode']['linux'] is True
            # Kept outside frozen helper files and consumed through the existing interface.
            path = pathlib.Path(os.environ['RUNNER_TEMP']) / 'pr164-linux-artifacts.json'
            path.write_text(json.dumps(c['server'], indent=2) + '\n')
            with open(os.environ['GITHUB_ENV'], 'a') as output:
                output.write('QA_ARTIFACT_CONFIG=' + str(path) + '\nCANDIDATE_COMMIT=' + MAIN + '\n')
                output.write('QA_PRIVATE_ROOT=' + str(pathlib.Path(os.environ['RUNNER_TEMP']) / 'pr164-isolated-qa') + '\n')
    print('PR164_PRIVATE_GUARD_PASS:' + args.mode)


if __name__ == '__main__':
    main()
