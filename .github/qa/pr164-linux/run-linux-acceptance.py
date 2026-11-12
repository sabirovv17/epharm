#!/usr/bin/env python3
"""Private PR164 acceptance of root-approved official artifacts. No application build."""
import importlib.util
import json
import os
import pathlib
import re
import sys

from jwt_workload import JwtAcceptance


def load_legacy():
    path = pathlib.Path(__file__).resolve().parent / 'legacy-run-isolated.py'
    spec = importlib.util.spec_from_file_location('legacy_owned_qa', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def validate_identities(root):
    value = json.loads((root / 'artifacts/verified-identities.json').read_text())
    assert set(value) == {'previous', 'candidate'}
    for item in value.values():
        assert re.fullmatch(r'v[0-9]+\.[0-9]+\.[0-9]+', item['version'])
        assert re.fullmatch(r'[a-f0-9]{40}', item['commit'])
        assert type(item['runId']) is int and item['runId'] > 0 and type(item['artifactId']) is int and item['artifactId'] > 0
        assert re.fullmatch(r'sha256:[a-f0-9]{64}', item['artifactDigest'])
    previous = tuple(map(int, value['previous']['version'][1:].split('.')))
    candidate = tuple(map(int, value['candidate']['version'][1:].split('.')))
    assert candidate > previous and value['previous']['commit'] != value['candidate']['commit']
    assert value['candidate']['commit'] == os.environ['CANDIDATE_COMMIT']
    return value


def main():
    assert sys.platform.startswith('linux') and os.environ.get('GITHUB_ACTIONS') == 'true', 'Hosted Linux CI only'
    assert os.environ.get('RUNNER_ENVIRONMENT') == 'github-hosted', 'Do not run this harness on a workstation or application server'
    assert os.environ.get('ROOT_CANDIDATE_READY') == 'APPROVED_OFFICIAL_CANDIDATE'
    assert not os.environ.get('GH_TOKEN') and not os.environ.get('GITHUB_TOKEN'), 'No GitHub credentials in runtime step'
    qa = load_legacy()
    assert qa.DOCKER and qa.ROOT.is_dir() and qa.ROOT.stat().st_uid == os.getuid() and qa.ROOT.stat().st_mode & 0o077 == 0
    original_cleanup = qa.cleanup

    def reconcile_cleanup():
        try:
            original_cleanup()
            # Recover the tiny create-network/before-report window after interruption.
            for network_id in qa.docker('network', 'ls', '-q', '--no-trunc', '--filter', 'label=' + qa.LABEL + '=' + qa.OWNER).stdout.splitlines():
                network = json.loads(qa.docker('network', 'inspect', network_id).stdout)[0]
                if network['Labels'].get(qa.LABEL) != qa.OWNER or network['Name'] != qa.OWNER + '-net' or not network['Internal'] or network['Containers']:
                    qa.REPORT['cleanup']['errors'].append('Owned network identity/emptiness refused cleanup')
                    continue
                result = qa.docker('network', 'rm', network_id, check=False)
                if result.returncode:
                    qa.REPORT['cleanup']['errors'].append('Owned orphan network removal failed')
            remaining = qa.docker('network', 'ls', '-q', '--no-trunc', '--filter', 'label=' + qa.LABEL + '=' + qa.OWNER).stdout.splitlines()
            qa.REPORT['cleanup']['ownedNetworksRemaining'] = remaining
            if remaining or qa.REPORT['cleanup']['errors']:
                qa.REPORT['outcome'] = 'FAIL'
            qa.write('assessment.json', qa.REPORT)
        except Exception as error:
            qa.REPORT['outcome'] = 'FAIL'
            qa.REPORT['cleanupFailureType'] = type(error).__name__
            qa.write('assessment.json', qa.REPORT)
            raise

    qa.cleanup = reconcile_cleanup
    if sys.argv[1:] == ['--cleanup-only']:
        path = qa.ROOT / 'assessment.json'
        if not path.exists():
            return 0
        qa.REPORT = json.loads(path.read_text())
        qa.OWNER = qa.REPORT['owner']
        assert re.fullmatch(r'pr164-[a-f0-9]{12}', qa.OWNER)
        qa.OWNED = [item['containerId'] for item in qa.REPORT['resources']]
        assert all(re.fullmatch(r'[a-f0-9]{64}', item) for item in qa.OWNED)
        qa.NET = qa.REPORT.get('networkId')
        assert qa.NET is None or re.fullmatch(r'[a-f0-9]{64}', qa.NET)
        qa.cleanup()
        return 1 if qa.REPORT['cleanup']['errors'] or qa.REPORT['cleanup']['ownedContainersRemaining'] or qa.REPORT['cleanup'].get('ownedNetworksRemaining') else 0
    assert not sys.argv[1:] and not (qa.ROOT / 'assessment.json').exists(), 'Fresh evidence directory required'
    validate_identities(qa.ROOT)
    phase = qa.phase
    start_backend = qa.start_backend
    extension = JwtAcceptance(qa, pathlib.Path(__file__).resolve().parent)
    seen = {'learner': 0, 'oldAfter': 0}

    def phase_with_learner(mode):
        phase(mode)
        if mode == 'sessions':
            seen['learner'] += 1
            assert seen['learner'] == 1
            fixture = json.loads((qa.ROOT / 'fixture/fixture.private.json').read_text())
            extension.bootstrap(fixture)
            extension.load()
            extension.semantics(fixture)

    def start_with_baseline(which, image, env_path):
        start_backend(which, image, env_path)
        if which == 'old-after':
            seen['oldAfter'] += 1
            assert seen['oldAfter'] == 1
            extension.old_baseline()

    qa.phase = phase_with_learner
    qa.start_backend = start_with_baseline
    qa.REPORT['limitations'] = [value.replace('Flutter/mobile', 'Flutter/Android/iOS application builds') for value in qa.REPORT['limitations']]
    qa.REPORT['limitations'].append('New learner path uses real API-issued pharmacist Bearer JWTs and protected training overview; it remains a bounded synthetic API scenario, not a production-capacity or full curriculum/browser certification.')
    # Persist the exact owner before the first resource can be created.
    qa.write('assessment.json', qa.REPORT)
    try:
        qa.main()
        assert seen == {'learner': 1, 'oldAfter': 1}
        extension.final_candidate()
        count = qa.REPORT['phases']['sessions']['summary']['metrics']['rps_session_checks']['values']['count']
        assert count >= 2000
        qa.REPORT['staffCheckAccounting'] = {'loadSessionChecks': count, 'crossVersionStaffChecks': 1, 'totalAtLeast': count + 1, 'minimumRequired': 2001, 'note': 'Original arrival workload unchanged; count includes the real previous-version grant compatibility check, not a fabricated counter.'}
        qa.REPORT['compatibility']['sequence'] = [qa.IDENTITIES['previous']['version'], qa.IDENTITIES['candidate']['version'], qa.IDENTITIES['previous']['version'], qa.IDENTITIES['candidate']['version']]
        qa.REPORT['outcome'] = 'PASS'
        qa.REPORT['stage'] = 'complete-candidate-restored'
    except Exception as error:
        qa.REPORT['outcome'] = 'FAIL'
        qa.REPORT['failureType'] = type(error).__name__
        # Neither command arguments nor request/response objects are public diagnostics.
        print('Private isolated acceptance failed: ' + type(error).__name__, file=sys.stderr)
    finally:
        qa.cleanup()
    print(json.dumps({'outcome': qa.REPORT['outcome'], 'stage': qa.REPORT.get('stage'), 'phases': {key: value['outcome'] for key, value in qa.REPORT['phases'].items()}, 'learnerJwt': {key: value.get('outcome', 'recorded') for key, value in qa.REPORT.get('learnerJwt', {}).items() if isinstance(value, dict)}, 'cleanup': qa.REPORT.get('cleanup')}))
    return 0 if qa.REPORT['outcome'] == 'PASS' else 1


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except Exception as error:
        print('Private acceptance preflight failed: ' + type(error).__name__, file=sys.stderr)
        raise SystemExit(1)
