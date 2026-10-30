import copy
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import crm_stage as stage
import owned_staff_access as staff
from test_crm_stage import column, constraint, fixture

ROOT = Path(__file__).resolve().parents[3]
ORG = '10000000-0000-4000-8000-000000000001'
BRANCH = '20000000-0000-4000-8000-000000000001'
CODE = '30000000-0000-4000-8000-000000000001'
ATTEMPT = '40000000-0000-4000-8000-000000000001'
STAMP = '2026-10-01 00:00:00+00'


def artifact(all_branches=False):
    metadata = copy.deepcopy(fixture()['metadata'])
    metadata.update(tables=[], enums=[], sequences=[], triggers=[], routines=[])
    definitions = {
        'organizations': [('id', 'uuid'), ('name', 'text')],
        'branches': [('id', 'uuid'), ('organization_id', 'uuid'), ('name', 'text')],
        'staff_access_codes': [(name, kind) for name, kind in (
            ('id', 'uuid'), ('organization_id', 'uuid'), ('placement_month', 'date'), ('code_hash', 'text'),
            ('deadline_at', 'timestamptz'), ('status', 'text'), ('created_by', 'uuid'), ('revoked_by', 'uuid'),
            ('created_at', 'timestamptz'), ('updated_at', 'timestamptz'), ('last_used_at', 'timestamptz'),
            ('revoked_at', 'timestamptz'), ('max_uses', 'int4'), ('use_count', 'int4'), ('note', 'text'),
            ('qr_token_hash', 'text'), ('qr_created_at', 'timestamptz'), ('qr_last_used_at', 'timestamptz'),
            ('qr_use_count', 'int4'))],
        'staff_access_code_branches': [('code_id', 'uuid'), ('branch_id', 'uuid'), ('created_at', 'timestamptz')],
        'staff_access_attempts': [('id', 'uuid'), ('access_type', 'text'), ('lookup_hash', 'text'),
            ('code_id', 'uuid'), ('organization_id', 'uuid'), ('request_ip', 'text'), ('user_agent', 'text'),
            ('success', 'bool'), ('failure_reason', 'text'), ('created_at', 'timestamptz')],
        'user_sessions': [('id', 'uuid'), ('session_kind', 'text'), ('revoked_at', 'timestamptz'), ('expires_at', 'timestamptz')],
        'visit_reports': [('id', 'uuid')],
    }
    row_values = {
        'organizations': [dict(id=ORG, name='Synthetic organization')],
        'branches': [dict(id=BRANCH, organization_id=ORG, name='Synthetic branch')],
        'staff_access_codes': [dict(id=CODE, organization_id=ORG, placement_month='2026-10-01',
            code_hash='$2b$10$' + 'a' * 53, deadline_at=STAMP, status='active', created_at=STAMP,
            updated_at=STAMP, max_uses='1', use_count='0', qr_use_count='0', note='Preserved synthetic note')],
        'staff_access_code_branches': [] if all_branches else [dict(code_id=CODE, branch_id=BRANCH, created_at=STAMP)],
        'staff_access_attempts': [dict(id=ATTEMPT, access_type='code', lookup_hash='b' * 64,
            code_id=CODE, organization_id=ORG, request_ip='127.0.0.1', user_agent='Synthetic test', success='f',
            failure_reason='synthetic_reason', created_at=STAMP)],
        'user_sessions': [], 'visit_reports': [],
    }
    for number in range(50):
        name = 'preserved_' + str(number).zfill(2)
        definitions[name] = [('id', 'text')]
        row_values[name] = []
    rows = {}
    for name, fields in definitions.items():
        pk = ['code_id', 'branch_id'] if name == 'staff_access_code_branches' else ['id']
        metadata['tables'].append(dict(name=name, kind='r',
            columns=[column(field, kind, position=index, notNull=field in pk) for index, (field, kind) in enumerate(fields, 1)],
            constraints=[constraint(name + '_pkey', 'p', pk)]))
        rows[name] = [[row.get(field) for field, _ in fields] for row in row_values[name]]
    metadata['tables'].sort(key=lambda table: table['name'])
    return dict(formatVersion=1, capturedAt='2026-10-01T00:00:00Z', sourceIdentitySha256='a' * 64,
        sourceBackupSha256='b' * 64, sourceSchemaSha256=stage.schema_hash(metadata), metadata=metadata,
        sequenceStates=[], tables=rows,
        checksums=[stage.table_checksum(table, rows[table['name']]) for table in metadata['tables']])


class StaffAccessProjectionTest(unittest.TestCase):
    def test_pristine_provenance_and_no_credential_or_business_replay(self):
        sql = staff.projection_sql('crm_stage_' + 'a' * 16, 'a' * 64, 'SELECT 123;')
        for required in ('owned_merch_context:', 'active legacy staff sessions', 'nonempty source visit history',
                         'staff_access_code_branches', 'source_payload', 'FULL JOIN', 'manual_revoked_at',
                         'NULL::uuid', 'verified_existing_noop', 'issuedCredentials', 'issuedSessions'):
            self.assertIn(required, sql)
        for forbidden in ('UPDATE public.', 'DELETE ', 'TRUNCATE ', 'INSERT INTO public.pharmacists',
                          'INSERT INTO public.pharmacist_wallet', 'INSERT INTO public.merch_staff_access_sessions',
                          'INSERT INTO public.merch_staff_visits'):
            self.assertNotIn(forbidden, sql)
        self.assertTrue(sql.endswith('COMMIT;'))

    def test_untrusted_namespace_rejected(self):
        for name in ('public', 'crm_stage_' + 'b' * 16, 'crm_stage_;drop'):
            with self.subTest(name=name), self.assertRaises(stage.StageError):
                staff.projection_sql(name, 'a' * 64, '')


@unittest.skipUnless(os.environ.get('RUN_OWNED_STAFF_PG_TESTS') == '1', 'opt-in isolated PG16 shared access projection')
class StaffAccessProjectionPostgresTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temp.name).resolve()
        cls.root.chmod(0o700)
        cls.name = 'epharm-stage-test-automation-staff-' + str(time.time_ns())
        cls.container_id = None
        cls.addClassCleanup(cls.cleanup)
        image = subprocess.check_output(['docker', 'image', 'inspect', 'postgres:16-alpine', '--format', '{{.Id}}'], text=True).strip()
        assert re.fullmatch(r'sha256:[a-f0-9]{64}', image)
        env = cls.root / 'pg.env'
        env.write_text('POSTGRES_USER=synthetic\nPOSTGRES_DB=epharm_unified_candidate\nPOSTGRES_PASSWORD=synthetic-test-only\n')
        env.chmod(0o600)
        cls.container_id = subprocess.check_output(['docker', 'run', '--pull=never', '-d', '--network=none',
            '--name', cls.name, '--env-file', str(env), image], text=True).strip()
        for _ in range(100):
            if subprocess.run(['docker', 'exec', cls.name, 'pg_isready', '-h', '127.0.0.1', '-U', 'synthetic'], capture_output=True).returncode == 0:
                break
            time.sleep(.1)
        cls.db = stage.Database(cls.name, 'synthetic', 'epharm_unified_candidate')
        cls.target = cls.db.inspect()
        cls.db.run('''CREATE TABLE admin_users(id uuid PRIMARY KEY);
          CREATE TABLE merch_organizations(id uuid PRIMARY KEY,source_system text,source_payload jsonb);
          CREATE TABLE merch_branches(id uuid PRIMARY KEY,organization_id uuid REFERENCES merch_organizations,source_system text,source_payload jsonb);
          CREATE TABLE merch_task_dispatches(id uuid PRIMARY KEY);
          CREATE TABLE merch_task_attempts(id uuid PRIMARY KEY,executor_person_id uuid,executor_account_id uuid,pharmacist_id text,binding_id uuid,capability_link_id uuid);
          CREATE TABLE unified_import_runs(operation_key text PRIMARY KEY,source_sha256 text,identity_sha256 text,result_json jsonb);
          CREATE TABLE pharmacist_wallet_entries(id text PRIMARY KEY,amount bigint);
          INSERT INTO pharmacist_wallet_entries VALUES('sentinel',123);''')
        cls.db.run((ROOT / 'admin-panel/backend/src/main/resources/db/migration/V090__merch_shared_staff_access.sql').read_text())

    @classmethod
    def cleanup(cls):
        if cls.container_id:
            subprocess.run(['docker', 'rm', '-fv', cls.container_id], capture_output=True)
        cls.temp.cleanup()

    def prepare(self, all_branches=False):
        self.value = artifact(all_branches)
        raw = stage.encoded(self.value)
        self.digest = stage.digest(raw)
        self.schema = 'crm_stage_' + self.digest[:16]
        self.path = self.root / ('snapshot-' + str(time.time_ns()) + '.json')
        stage.private_write(self.path, raw)
        self.db.run('TRUNCATE merch_organizations,unified_import_runs CASCADE;DROP SCHEMA IF EXISTS ' + stage.qi(self.schema) + ' CASCADE;')
        self.db.run(stage.load_sql(self.value, self.schema, self.digest))
        q = stage.qi(self.schema)
        self.db.run("INSERT INTO merch_organizations SELECT id,'MERCH_CRM',to_jsonb(s) FROM " + q + ".organizations s;"
            "INSERT INTO merch_branches SELECT id,organization_id,'MERCH_CRM',to_jsonb(s) FROM " + q + ".branches s;"
            "INSERT INTO unified_import_runs VALUES('owned_merch_context:" + self.digest[:24] + "','" + self.digest + "','" + self.digest + "','{}');")

    def cli(self):
        report = self.root / ('report-' + str(time.time_ns()) + '.json')
        return subprocess.run([sys.executable, str(ROOT / 'tools/unification/owned_staff_access.py'), '--container',
            self.name, '--user', 'synthetic', '--database', 'epharm_unified_candidate', '--artifact', str(self.path),
            '--artifact-sha256', self.digest, '--target-sha256', self.target['identitySha256'], '--report', str(report)],
            text=True, capture_output=True)

    def test_exact_history_and_noop_preserve_wallet_and_issue_nothing(self):
        self.prepare()
        first = self.cli()
        self.assertEqual(first.returncode, 0, first.stderr)
        result = json.loads(first.stdout)
        self.assertEqual(result['codes'], 1)
        self.assertEqual(result['scopeRows'], 1)
        self.assertEqual(result['historicalAttempts'], 1)
        self.assertEqual(result['issuedCredentials'], 0)
        self.assertEqual(self.cli().returncode, 0)
        self.assertEqual(self.db.readonly("SELECT code_lookup_hash IS NULL AND created_by_account_id IS NULL AND NOT all_org_branches FROM public.merch_staff_access_codes;").strip(), 't')
        self.assertEqual(self.db.readonly('SELECT amount FROM public.pharmacist_wallet_entries;').strip(), '123')
        self.assertEqual(self.db.readonly('SELECT count(*) FROM public.merch_staff_access_sessions;').strip(), '0')

    def test_explicit_source_all_branch_semantics_and_immutable_history(self):
        self.prepare(all_branches=True)
        self.assertEqual(self.cli().returncode, 0)
        self.assertEqual(self.db.readonly('SELECT all_org_branches FROM public.merch_staff_access_codes;').strip(), 't')
        with self.assertRaises(stage.StageError):
            self.db.run('DELETE FROM merch_staff_access_attempts')
        with self.assertRaises(stage.StageError):
            self.db.run("UPDATE merch_staff_access_codes SET source_payload='{}'")
        self.assertEqual(self.cli().returncode, 0)

    def test_changed_native_history_fails_without_overwrite(self):
        self.prepare()
        self.assertEqual(self.cli().returncode, 0)
        self.db.run("UPDATE merch_staff_access_codes SET note='Later controlled change'")
        self.assertNotEqual(self.cli().returncode, 0)
        self.assertEqual(self.db.readonly('SELECT note FROM public.merch_staff_access_codes;').strip(), 'Later controlled change')

    def test_active_source_session_and_nonempty_visit_are_not_discarded(self):
        for table in ('user_sessions', 'visit_reports'):
            self.prepare()
            q = stage.qi(self.schema)
            # Run the generated projection directly: these altered-source fixtures must refuse before writing.
            if table == 'user_sessions':
                self.db.run("INSERT INTO " + q + ".user_sessions VALUES(gen_random_uuid(),'staff_access',NULL,now()+interval '1 hour')")
            else:
                self.db.run('INSERT INTO ' + q + '.visit_reports VALUES(gen_random_uuid())')
            with self.assertRaises(stage.StageError):
                self.db.run(staff.projection_sql(self.schema, self.digest, 'SELECT 1;'))
            self.assertEqual(self.db.readonly('SELECT count(*) FROM public.merch_staff_access_codes;').strip(), '0')


if __name__ == '__main__':
    unittest.main()
