import contextlib
import copy
from http.client import IncompleteRead
import importlib.util
import io
import json
from pathlib import Path
import unittest
from unittest.mock import Mock, patch
from urllib.error import HTTPError, URLError

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('release_source', ROOT / 'tools/release/verify-release-source.py')
source = importlib.util.module_from_spec(spec)
spec.loader.exec_module(source)

COMMIT = 'a' * 40
OTHER_COMMIT = 'b' * 40


class ReleaseSourceTests(unittest.TestCase):
    def setUp(self):
        self.head = {'sha': COMMIT}
        self.workflow = {'id': 42, 'path': source.WORKFLOW_PATH, 'state': 'active'}
        self.run = {
            'id': 100, 'run_number': 7, 'run_attempt': 1, 'workflow_id': 42,
            'check_suite_id': 200, 'path': source.WORKFLOW_PATH,
            'head_sha': COMMIT, 'head_branch': 'main', 'event': 'push',
            'status': 'completed', 'conclusion': 'success',
            'repository': {'full_name': source.REPOSITORY},
            'head_repository': {'full_name': source.REPOSITORY},
        }
        self.runs = {'workflow_runs': [self.run]}
        self.gate = {
            'name': source.GATE_NAME, 'head_sha': COMMIT,
            'app': {'id': source.ACTIONS_APP_ID}, 'check_suite': {'id': 200},
            'status': 'completed', 'conclusion': 'success',
        }
        self.checks = {'total_count': 1, 'check_runs': [self.gate]}

    def validate(self):
        run = source.select_ci_run(COMMIT, self.head, self.workflow, self.runs)
        source.validate_gate(COMMIT, run, self.checks)
        return run

    def test_exact_main_with_successful_matching_ci_and_gate(self):
        self.assertEqual(self.validate()['id'], 100)

    def test_stale_or_foreign_candidate_rejected(self):
        with self.assertRaisesRegex(ValueError, 'current trusted main'):
            source.select_ci_run(OTHER_COMMIT, self.head, self.workflow, self.runs)

    def test_invalid_commit_rejected_before_api_call(self):
        for commit in ('main', 'a' * 39, 'A' * 40, 'a' * 64, '--help', COMMIT + '\n', ''):
            with self.subTest(commit=commit), patch.object(source, 'github_api') as api:
                with contextlib.redirect_stderr(io.StringIO()):
                    self.assertEqual(source.main(['--', commit]), 1)
                api.assert_not_called()

    def test_missing_disabled_or_other_workflow_rejected(self):
        for key, value in (('id', None), ('id', 0), ('id', True),
                           ('state', 'disabled_manually'), ('path', '.github/workflows/fake.yml')):
            with self.subTest(key=key):
                workflow = dict(self.workflow, **{key: value})
                with self.assertRaisesRegex(ValueError, 'workflow'):
                    source.select_ci_run(COMMIT, self.head, workflow, self.runs)

    def test_only_exact_main_push_ci_counts(self):
        for key, value in (('head_sha', OTHER_COMMIT), ('head_branch', 'codex/feature'),
                           ('event', 'pull_request'), ('workflow_id', 43)):
            with self.subTest(key=key):
                runs = {'workflow_runs': [dict(self.run, **{key: value})]}
                with self.assertRaisesRegex(ValueError, 'No exact-main push CI'):
                    source.select_ci_run(COMMIT, self.head, self.workflow, runs)

    def test_foreign_repository_or_workflow_run_rejected(self):
        for key, value in (('repository', {'full_name': 'other/epharm'}),
                           ('head_repository', {'full_name': 'other/epharm'}),
                           ('path', '.github/workflows/fake.yml')):
            with self.subTest(key=key):
                runs = {'workflow_runs': [dict(self.run, **{key: value})]}
                with self.assertRaisesRegex(ValueError, 'trusted repository/workflow'):
                    source.select_ci_run(COMMIT, self.head, self.workflow, runs)

    def test_failed_pending_cancelled_or_skipped_ci_rejected(self):
        for status, conclusion in (('in_progress', None), ('queued', None), ('completed', 'failure'),
                                   ('completed', 'cancelled'), ('completed', 'skipped')):
            with self.subTest(status=status, conclusion=conclusion):
                runs = {'workflow_runs': [dict(self.run, status=status, conclusion=conclusion)]}
                with self.assertRaisesRegex(ValueError, 'not completed successfully'):
                    source.select_ci_run(COMMIT, self.head, self.workflow, runs)

    def test_older_success_does_not_hide_newer_failed_or_pending_run(self):
        for state in ({'status': 'queued', 'conclusion': None},
                      {'status': 'completed', 'conclusion': 'failure'}):
            with self.subTest(state=state):
                newer = dict(self.run, id=101, run_number=8, **state)
                runs = {'workflow_runs': [self.run, newer]}
                with self.assertRaisesRegex(ValueError, 'not completed successfully'):
                    source.select_ci_run(COMMIT, self.head, self.workflow, runs)

    def test_successful_rerun_must_use_current_attempt_gate(self):
        self.run['run_attempt'] = 2
        self.gate['conclusion'] = 'failure'
        with self.assertRaisesRegex(ValueError, 'P0 merge gate has not completed'):
            self.validate()

    def test_missing_or_ambiguous_gate_rejected(self):
        for gates in ([], [dict(self.gate, name='other')], [self.gate, self.gate]):
            with self.subTest(gates=gates):
                checks = {'total_count': len(gates), 'check_runs': gates}
                with self.assertRaisesRegex(ValueError, 'missing or ambiguous'):
                    source.validate_gate(COMMIT, self.run, checks)

    def test_wrong_app_sha_or_suite_rejected(self):
        for key, value in (('app', {'id': 999}), ('head_sha', OTHER_COMMIT),
                           ('check_suite', {'id': 201})):
            with self.subTest(key=key):
                checks = {'total_count': 1, 'check_runs': [dict(self.gate, **{key: value})]}
                with self.assertRaisesRegex(ValueError, 'exact CI run/GitHub Actions app'):
                    source.validate_gate(COMMIT, self.run, checks)

    def test_failed_or_pending_gate_rejected_even_when_workflow_success(self):
        for status, conclusion in (('queued', None), ('completed', 'failure'), ('completed', 'neutral')):
            with self.subTest(status=status, conclusion=conclusion):
                checks = {'total_count': 1, 'check_runs': [dict(self.gate, status=status, conclusion=conclusion)]}
                with self.assertRaisesRegex(ValueError, 'not completed successfully'):
                    source.validate_gate(COMMIT, self.run, checks)

    def test_incomplete_checks_or_invalid_run_identity_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Incomplete'):
            source.validate_gate(COMMIT, self.run, dict(self.checks, total_count=101))
        for value in (None, True, '200', 0, -1):
            with self.subTest(value=value):
                runs = {'workflow_runs': [dict(self.run, check_suite_id=value)]}
                with self.assertRaisesRegex(ValueError, 'Invalid CI run identity'):
                    source.select_ci_run(COMMIT, self.head, self.workflow, runs)

    def test_empty_or_malformed_runs_fail_closed(self):
        for runs in ({}, {'workflow_runs': None}, {'workflow_runs': [None]}, {'workflow_runs': []}):
            with self.subTest(runs=runs), self.assertRaises(ValueError):
                source.select_ci_run(COMMIT, self.head, self.workflow, runs)

    def evidence(self):
        return copy.deepcopy([self.head, self.workflow, self.runs, self.checks, self.head])

    def test_cli_reads_only_fixed_github_repo_and_verifies_twice(self):
        opener = Mock()
        opener.open.side_effect = [io.BytesIO(json.dumps(payload).encode()) for payload in self.evidence()]
        with patch.object(source.urllib.request, 'build_opener', return_value=opener) as builder:
            with contextlib.redirect_stdout(io.StringIO()) as output:
                with patch.dict(source.os.environ, {}, clear=True):
                    self.assertEqual(source.main([COMMIT]), 0)
        self.assertIn(COMMIT, output.getvalue())
        paths = [call.args[0].full_url for call in opener.open.call_args_list]
        self.assertEqual(paths[0], f'https://api.github.com/repos/{source.REPOSITORY}/commits/main')
        self.assertEqual(paths[-1], paths[0])
        self.assertIn('head_sha=' + COMMIT, paths[2])
        self.assertIn('check-suites/200/check-runs', paths[3])
        for call in opener.open.call_args_list:
            self.assertEqual(call.args[0].get_method(), 'GET')
            self.assertIsNone(call.args[0].get_header('Authorization'))
            self.assertEqual(call.kwargs['timeout'], 30)
        for call in builder.call_args_list:
            self.assertIsInstance(call.args[0], source.NoRedirect)

    def test_optional_environment_token_only_in_request_header(self):
        for environment, expected in (({'GH_TOKEN': 'gh-secret'}, 'gh-secret'),
                                      ({'GITHUB_TOKEN': 'github-secret'}, 'github-secret'),
                                      ({'GH_TOKEN': 'gh-secret', 'GITHUB_TOKEN': 'other-secret'}, 'gh-secret')):
            with self.subTest(environment=list(environment)):
                opener = Mock()
                opener.open.return_value = io.BytesIO(b'{}')
                with patch.object(source.urllib.request, 'build_opener', return_value=opener):
                    with patch.dict(source.os.environ, environment, clear=True):
                        self.assertEqual(source.github_api('commits/main'), {})
                request = opener.open.call_args.args[0]
                self.assertEqual(request.get_header('Authorization'), 'Bearer ' + expected)
                self.assertNotIn(expected, request.full_url)

    def test_redirects_denied_instead_of_forwarding_bearer(self):
        request = source.urllib.request.Request('https://api.github.com/repos/sabirovv17/epharm/commits/main',
                                                headers={'Authorization': 'Bearer secret'})
        for status in (301, 302, 303, 307, 308):
            with self.subTest(status=status):
                self.assertIsNone(source.NoRedirect().redirect_request(
                    request, None, status, 'Redirect', {}, 'https://other.invalid/'))

    def test_api_response_read_is_bounded(self):
        response = Mock()
        response.read.return_value = b' ' * (source.MAX_API_BYTES + 1)
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=None)
        opener = Mock()
        opener.open.return_value = response
        with patch.object(source.urllib.request, 'build_opener', return_value=opener):
            with self.assertRaisesRegex(ValueError, 'promotion denied'):
                source.github_api('commits/main')
        response.read.assert_called_once_with(source.MAX_API_BYTES + 1)

    def test_main_moving_during_verification_rejected(self):
        responses = self.evidence()
        responses[-1] = {'sha': OTHER_COMMIT}
        with patch.object(source, 'github_api', side_effect=responses):
            with contextlib.redirect_stderr(io.StringIO()) as error:
                self.assertEqual(source.main([COMMIT]), 1)
        self.assertIn('current trusted main', error.getvalue())

    def test_network_http_json_and_shape_errors_fail_without_secret_output(self):
        secret = 'SECRET_TOKEN_MUST_NOT_APPEAR'
        cases = [
            URLError(secret), TimeoutError(secret),
            HTTPError('https://api.github.com/', 401, secret, {}, None),
            HTTPError('https://api.github.com/', 302, secret, {'Location': 'https://other.invalid/'}, None),
            IncompleteRead(secret.encode()),
            secret.encode(), b'[]',
        ]
        for result in cases:
            with self.subTest(result=type(result).__name__):
                opener = Mock()
                if isinstance(result, Exception):
                    opener.open.side_effect = result
                else:
                    opener.open.return_value = io.BytesIO(result)
                with patch.object(source.urllib.request, 'build_opener', return_value=opener):
                    with contextlib.redirect_stderr(io.StringIO()) as error:
                        self.assertEqual(source.main([COMMIT]), 1)
                self.assertNotIn(secret, error.getvalue())
                self.assertIn('promotion denied', error.getvalue())

    def test_malformed_nested_evidence_reports_safe_error(self):
        responses = self.evidence()
        responses[2]['workflow_runs'][0]['repository'] = None
        with patch.object(source, 'github_api', side_effect=responses):
            with contextlib.redirect_stderr(io.StringIO()) as error:
                self.assertEqual(source.main([COMMIT]), 1)
        self.assertIn('Invalid GitHub evidence', error.getvalue())


if __name__ == '__main__':
    unittest.main()
