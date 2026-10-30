import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tarfile
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[3]
WORKFLOW = ROOT / '.github/workflows/release-images.yml'
COMMIT = 'a' * 40


def run_block(step_id):
    lines = WORKFLOW.read_text().splitlines()
    start = lines.index('        id: ' + step_id)
    start = next(i for i in range(start, len(lines)) if lines[i] == '        run: |') + 1
    end = next((i for i in range(start, len(lines)) if lines[i] and not lines[i].startswith('          ')), len(lines))
    return '\n'.join(line[10:] for line in lines[start:end]) + '\n'


class ReleaseImagesWorkflowTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        self.runner = self.root / 'runner'
        self.runner.mkdir()
        self.env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ['PATH'],
                        RUNNER_TEMP=str(self.runner), COMMIT=COMMIT, RELEASE_ID='v0.2.0',
                        GITHUB_REPOSITORY='sabirovv17/epharm', GITHUB_REF='refs/heads/main',
                        GITHUB_SHA=COMMIT, COMMAND_LOG=str(self.root / 'commands.jsonl'))
        self.command('git', '''
            import os, pathlib, sys
            args=sys.argv[1:]
            if args[0]=='archive':
                sys.stdout.buffer.write(pathlib.Path(os.environ['ARCHIVE_FIXTURE']).read_bytes())
            elif args==['rev-parse','HEAD']:print(os.environ['COMMIT'])
            elif args[0]=='rev-parse' and args[1].endswith('^{commit}'):print(os.environ.get('TAG_COMMIT',os.environ['COMMIT']))
            elif args[0]=='rev-parse':print(os.environ.get('TAG_OBJECT','b'*40))
            else:raise SystemExit(2)
        ''')
        self.command('docker', "print('linux/x86_64')")
        self.command('gh', '''
            import json, os, sys
            with open(os.environ['COMMAND_LOG'],'a') as f:f.write('gh\\n')
            if os.environ.get('API_FAIL'):raise SystemExit(19)
            print(os.environ.get('TAG_RESPONSE','[]'))
        ''')
        self.command('python3', '''
            import os, sys
            if len(sys.argv)>1 and sys.argv[1]=='tools/release/verify-release-source.py':
                with open(os.environ['COMMAND_LOG'],'a') as f:f.write('source-gate\\n')
                raise SystemExit(17 if os.environ.get('SOURCE_GATE_FAIL') else 0)
            os.execv(REAL_PYTHON,[REAL_PYTHON,*sys.argv[1:]])
        '''.replace('REAL_PYTHON', repr(sys.executable)))

    def command(self, name, body):
        path = self.bin / name
        path.write_text('#!' + sys.executable + '\n' + textwrap.dedent(body))
        path.chmod(0o700)

    def execute(self, script, **changes):
        return subprocess.run(['bash', '-c', script], env=dict(self.env, **changes), cwd=self.root,
                              capture_output=True, text=True, timeout=15)

    def gate(self, **changes):
        block = run_block('source_gate')
        body = block.split("<<'SH'\n", 1)[1].split('\nSH\n', 1)[0]
        return self.execute(body, **changes)

    def archive(self, entries):
        path = self.root / 'fixture.tar.gz'
        with tarfile.open(path, 'w:gz') as archive:
            for name, body in entries.items():
                info = tarfile.TarInfo(name)
                info.size = len(body)
                archive.addfile(info, io.BytesIO(body))
        return self.execute(run_block('archive'), ARCHIVE_FIXTURE=str(path))

    def test_valid_main_and_release_candidate_identity(self):
        for release in ('v0.2.0', 'v0.2.0-rc.1'):
            with self.subTest(release=release):
                self.assertEqual(self.execute(run_block('identity'), RELEASE_ID=release).returncode, 0)

    def test_foreign_repository_nonmain_or_different_dispatch_sha_rejected(self):
        for field, value in (('GITHUB_REPOSITORY', 'other/epharm'), ('GITHUB_REF', 'refs/heads/codex/test'),
                             ('GITHUB_SHA', 'b' * 40)):
            with self.subTest(field=field):
                self.assertNotEqual(self.execute(run_block('identity'), **{field: value}).returncode, 0)

    def test_sha_version_token_and_command_injections_never_execute_or_echo(self):
        marker = self.root / 'must-not-exist'
        values = ('main', 'A' * 40, COMMIT + '\n', 'ghp_synthetic_secret',
                  'v0.2.0-' + 'a' * 128, '$(touch ' + str(marker) + ')', 'v0.2.0; touch ' + str(marker))
        for field in ('COMMIT', 'RELEASE_ID'):
            for value in values:
                with self.subTest(field=field, value=value):
                    result = self.execute(run_block('identity'), **{field: value})
                    self.assertNotEqual(result.returncode, 0)
                    self.assertNotIn('ghp_synthetic_secret', result.stdout + result.stderr)
                    self.assertFalse(marker.exists())

    def test_existing_lightweight_or_annotated_tag_must_resolve_to_exact_commit(self):
        for object_sha in (COMMIT, 'b' * 40):
            response = json.dumps([{'ref': 'refs/tags/v0.2.0', 'object': {'sha': object_sha}}])
            with self.subTest(object_sha=object_sha):
                self.assertEqual(self.gate(TAG_RESPONSE=response, TAG_OBJECT=object_sha).returncode, 0)
                self.assertNotEqual(self.gate(TAG_RESPONSE=response, TAG_OBJECT=object_sha,
                                             TAG_COMMIT='c' * 40).returncode, 0)
        self.assertEqual(self.gate().returncode, 0)  # An absent version tag is not created.

    def test_tag_change_or_api_failure_does_not_mean_absent_tag(self):
        response = json.dumps([{'ref': 'refs/tags/v0.2.0', 'object': {'sha': 'c' * 40}}])
        self.assertNotEqual(self.gate(TAG_RESPONSE=response).returncode, 0)
        self.assertNotEqual(self.gate(API_FAIL='true').returncode, 0)
        self.assertNotEqual(self.gate(TAG_RESPONSE='{}').returncode, 0)

    def test_failed_main_push_gate_stops_before_tag_lookup(self):
        self.assertNotEqual(self.gate(SOURCE_GATE_FAIL='true').returncode, 0)
        self.assertEqual((self.root / 'commands.jsonl').read_text(), 'source-gate\n')

    def test_git_archive_roundtrip_records_exact_file_bytes(self):
        entries = {'admin-panel/backend/Dockerfile': b'FROM synthetic',
                   'admin-panel/frontend/src/main.tsx': b'export {}'}
        result = self.archive(entries)
        self.assertEqual(result.returncode, 0, result.stderr)
        files = json.loads((self.runner / 'release-images/source-files.json').read_text())
        self.assertEqual(files, {name: hashlib.sha256(body).hexdigest() for name, body in entries.items()})
        for name, body in entries.items():
            self.assertEqual((self.runner / 'release-source' / name).read_bytes(), body)

    def test_private_dotenv_and_path_escape_cannot_enter_build_context(self):
        for name in ('admin-panel/backend/.env.prod', 'admin-panel/frontend/.env.local', '../escape'):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                runner = Path(directory)
                original = self.runner
                self.runner = runner
                self.env['RUNNER_TEMP'] = directory
                try:
                    self.assertNotEqual(self.archive({name: b'synthetic-sensitive-marker'}).returncode, 0)
                    self.assertFalse(any(p.name.startswith('.env') for p in runner.rglob('*')))
                finally:
                    self.runner = original
                    self.env['RUNNER_TEMP'] = str(original)

    def test_no_overlay_into_preexisting_source(self):
        source = self.runner / 'release-source'
        source.mkdir()
        preserved = source / 'preserve'
        preserved.write_bytes(b'earlier-history')
        self.assertNotEqual(self.archive({'other': b'value'}).returncode, 0)
        self.assertEqual(preserved.read_bytes(), b'earlier-history')

    def test_readonly_manual_workflow_preserves_gate_and_immutable_upload(self):
        text = WORKFLOW.read_text()
        self.assertIn('  workflow_dispatch:', text)
        self.assertNotRegex(text, re.compile(r'^  (push|pull_request|workflow_run):', re.M))
        self.assertIn('  contents: read\n  actions: read\n  checks: read', text)
        self.assertIn('          persist-credentials: false', text)
        self.assertIn('          overwrite: false', text)
        self.assertIn('          if-no-files-found: error', text)
        self.assertEqual(text.count('bash "$RUNNER_TEMP/release-source-gate.sh"'), 2)
        first = text.index('bash "$RUNNER_TEMP/release-source-gate.sh"')
        last = text.rindex('bash "$RUNNER_TEMP/release-source-gate.sh"')
        self.assertLess(first, text.index('        id: archive'))
        self.assertGreater(last, text.index('        id: build'))
        self.assertLess(last, text.index('      - uses: actions/upload-artifact@'))
        for forbidden in ('continue-on-error:', '|| true', 'packages: write', 'docker push', 'ghcr.io',
                          'docker compose', 'ssh ', 'secrets.', '${{ inputs.'):
            for step in ('identity', 'archive', 'build'):
                self.assertNotIn(forbidden, run_block(step))

    def test_build_labels_platform_artifacts_and_actual_jar_guards(self):
        script = run_block('build')
        builds = [line for line in script.splitlines() if line.startswith('docker build ')]
        self.assertEqual(len(builds), 2)
        self.assertIn('backend', builds[0])
        self.assertIn('frontend', builds[1])
        for command in builds:
            self.assertIn('--platform linux/amd64', command)
            self.assertIn('RELEASE_ID=$RELEASE_ID', command)
            self.assertIn('RELEASE_COMMIT=$COMMIT', command)
        for required in ('org.opencontainers.image.version', 'org.opencontainers.image.revision',
                         'verify-merch-artifact.py', 'verify-admin-artifact.py', 'verify-financial-transition.py',
                         '--schema-protected --access-protected', 'verify-image-archive.py',
                         'source-files.json', 'app.jar backend.tar.gz frontend.tar.gz source.tar.gz'):
            self.assertIn(required, script)
        self.assertIn('backendConfigSha256=sys.argv[1][7:],frontendConfigSha256=sys.argv[2][7:]', script)
        self.assertIn('config_ids+=("$(python3', script)
        self.assertIn('Caddyfile docker-compose.prod.yml tools admin-panel/backend admin-panel/frontend', run_block('archive'))
        self.assertNotIn('docs', run_block('archive'))
        self.assertIn("'dockerEngine':engine", script)
        self.assertIn('{{.ServerVersion}}', script)
        self.assertIn('{{.Driver}}', script)
        self.assertNotIn('setup-docker-action', WORKFLOW.read_text())

    def test_actual_archive_config_digests_are_persisted_and_malformed_output_denied(self):
        body = run_block('build').rsplit("<<'PY'\n", 1)[1].split('\nPY', 1)[0]
        output = self.runner / 'release-images'
        output.mkdir()
        path = output / 'provenance.json'
        path.write_text(json.dumps({'commit': COMMIT}))
        digests = ['sha256:' + 'b' * 64, 'sha256:' + 'c' * 64]
        result = subprocess.run([sys.executable, '-c', body, *digests], env=self.env,
                                capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, 0, result.stderr)
        before = path.read_bytes()
        value = json.loads(before)
        self.assertEqual(value['backendConfigSha256'], 'b' * 64)
        self.assertEqual(value['frontendConfigSha256'], 'c' * 64)
        result = subprocess.run([sys.executable, '-c', body, digests[0], 'unverified-image-id'],
                                env=self.env, capture_output=True, text=True, timeout=10)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(path.read_bytes(), before)


if __name__ == '__main__':
    unittest.main()
