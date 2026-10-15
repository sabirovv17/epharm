#!/usr/bin/env python3
"""Read-only source/CI gate for new promotions, never for an explicit rollback."""
import argparse
from http.client import HTTPException
import json
import os
import re
import sys
import urllib.request

REPOSITORY = 'sabirovv17/epharm'
WORKFLOW_PATH = '.github/workflows/ci.yml'
GATE_NAME = 'P0 / merge gate'
ACTIONS_APP_ID = 15368
MAX_API_BYTES = 2 * 1024 * 1024


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        return None


def require_commit(commit: str) -> str:
    if not re.fullmatch(r'[a-f0-9]{40}', commit):
        raise ValueError('An exact lowercase 40-hex Git commit is required')
    return commit


def validate_main(commit: str, main: dict) -> None:
    require_commit(commit)
    if main.get('sha') != commit:
        raise ValueError('Candidate is not the current trusted main commit')


def select_ci_run(commit: str, main: dict, workflow: dict, runs: dict) -> dict:
    validate_main(commit, main)
    if (workflow.get('path') != WORKFLOW_PATH or workflow.get('state') != 'active'
            or type(workflow.get('id')) is not int or workflow['id'] <= 0):
        raise ValueError('Trusted CI workflow is absent, disabled or invalid')
    items = runs.get('workflow_runs')
    if not isinstance(items, list) or any(not isinstance(run, dict) for run in items):
        raise ValueError('Invalid CI run evidence')
    candidates = [run for run in items if run.get('head_sha') == commit
                  and run.get('head_branch') == 'main' and run.get('event') == 'push'
                  and run.get('workflow_id') == workflow['id']]
    if not candidates:
        raise ValueError('No exact-main push CI run exists for the candidate')
    for run in candidates:
        if any(type(run.get(key)) is not int or run[key] <= 0
               for key in ('id', 'run_number', 'run_attempt', 'check_suite_id')):
            raise ValueError('Invalid CI run identity')
    run = max(candidates, key=lambda item: (item['run_number'], item['run_attempt']))
    if (run.get('repository', {}).get('full_name') != REPOSITORY
            or run.get('head_repository', {}).get('full_name') != REPOSITORY
            or run.get('path') != WORKFLOW_PATH):
        raise ValueError('CI run does not belong to the trusted repository/workflow')
    if run.get('status') != 'completed' or run.get('conclusion') != 'success':
        raise ValueError('Latest exact-main CI run has not completed successfully')
    return run


def validate_gate(commit: str, run: dict, checks: dict) -> None:
    items = checks.get('check_runs')
    if (not isinstance(items, list) or type(checks.get('total_count')) is not int
            or checks['total_count'] != len(items)
            or any(not isinstance(check, dict) for check in items)):
        raise ValueError('Incomplete or invalid CI check evidence')
    gates = [check for check in items if check.get('name') == GATE_NAME]
    if len(gates) != 1:
        raise ValueError('Required P0 merge gate is missing or ambiguous')
    gate = gates[0]
    if (gate.get('head_sha') != commit
            or gate.get('app', {}).get('id') != ACTIONS_APP_ID
            or gate.get('check_suite', {}).get('id') != run['check_suite_id']):
        raise ValueError('P0 gate is not from the exact CI run/GitHub Actions app')
    if gate.get('status') != 'completed' or gate.get('conclusion') != 'success':
        raise ValueError('Required P0 merge gate has not completed successfully')


def github_api(path: str) -> dict:
    try:
        headers = {'Accept': 'application/vnd.github+json',
                   'User-Agent': 'epharm-release-source-verifier',
                   'X-GitHub-Api-Version': '2022-11-28'}
        token = os.environ.get('GH_TOKEN') or os.environ.get('GITHUB_TOKEN')
        if token:
            headers['Authorization'] = f'Bearer {token}'
        request = urllib.request.Request(
            f'https://api.github.com/repos/{REPOSITORY}/{path}', headers=headers, method='GET')
        # Never forward an optional bearer token to a redirected origin.
        opener = urllib.request.build_opener(NoRedirect())
        with opener.open(request, timeout=30) as response:
            body = response.read(MAX_API_BYTES + 1)
        if len(body) > MAX_API_BYTES:
            raise ValueError('Oversized GitHub response')
        payload = json.loads(body)
    except (OSError, HTTPException, ValueError):
        # Network errors and raw API payloads can contain authentication details.
        raise ValueError('GitHub API evidence unavailable; promotion denied') from None
    if not isinstance(payload, dict):
        raise ValueError('Invalid GitHub API evidence; promotion denied')
    return payload


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('commit', help='Exact current main commit; rollback is a separate operation')
    args = parser.parse_args(argv)
    try:
        commit = require_commit(args.commit)
        head = github_api('commits/main')
        validate_main(commit, head)
        workflow = github_api('actions/workflows/ci.yml')
        runs = github_api(
            f'actions/workflows/ci.yml/runs?branch=main&event=push&head_sha={commit}&per_page=100')
        run = select_ci_run(commit, head, workflow, runs)
        checks = github_api(
            f'check-suites/{run["check_suite_id"]}/check-runs?filter=latest&per_page=100')
        validate_gate(commit, run, checks)
        validate_main(commit, github_api('commits/main'))
    except (ValueError, AttributeError, KeyError, TypeError) as error:
        if isinstance(error, ValueError):
            message = str(error)
        else:
            message = 'Invalid GitHub evidence; promotion denied'
        print(f'ERROR: {message}', file=sys.stderr)
        return 1
    print(f'Release source verified: {REPOSITORY} main {commit}; CI run {run["id"]}; {GATE_NAME} success')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
