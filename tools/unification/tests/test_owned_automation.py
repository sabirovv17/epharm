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
import owned_automation as automation
from test_crm_stage import column, constraint, fixture

TOOLS = Path(__file__).resolve().parents[1]
ROOT = TOOLS.parents[1]
SOURCE_HASH = 'a' * 64
BRANCHES = [f'10000000-0000-4000-8000-{n:012d}' for n in range(1, 6)]
USERS = [f'20000000-0000-4000-8000-{n:012d}' for n in range(1, 5)]
PLAN = '30000000-0000-4000-8000-000000000001'
DISPATCH = '40000000-0000-4000-8000-000000000001'
ITEM = '50000000-0000-4000-8000-000000000001'
SHELF = '60000000-0000-4000-8000-000000000001'
PLAN_ITEM = '70000000-0000-4000-8000-000000000001'
PRIVATE_REF = 'SYNTHETIC-PRIVATE-reference/photo.png?unchanged=1'


def synthetic_artifact(photo=False):
    """A real typed 57-table snapshot, with explicit ambiguous/inactive/non-MAS membership cases."""
    metadata = copy.deepcopy(fixture()['metadata'])
    metadata.update(tables=[], enums=[], sequences=[], triggers=[], routines=[])
    rows = {}

    def table(name, fields, data):
        columns = [column(n, kind, position=i, notNull=n == 'id') for i, (n, kind) in enumerate(fields, 1)]
        metadata['tables'].append(dict(name=name, kind='r', columns=columns,
                                      constraints=[constraint(name + '_pkey', 'p', ['id'])]))
        rows[name] = data

    table('media_plans', [('id', 'uuid'), ('reviewed_at', 'timestamptz')], [[PLAN, '2026-10-20 01:02:03.123456+00']])
    table('staff_task_dispatches', [('id', 'uuid'), ('branch_id', 'uuid'), ('media_plan_id', 'uuid'),
          ('status', 'text'), ('recurring_cycle_number', 'int4'), ('cycle_starts_at', 'timestamptz'),
          ('next_cycle_at', 'timestamptz')], [[DISPATCH, BRANCHES[0], PLAN, 'submitted', '3',
                                           '2026-10-21 01:02:03+00', '2026-10-28 01:02:03+00']])
    table('staff_tasks', [('id', 'uuid'), ('dispatch_id', 'uuid'), ('media_plan_item_id', 'uuid'), ('shelf_id', 'uuid'),
          ('recurring_cycle_number', 'int4'), ('automation_kind', 'text'), ('reference_image_url', 'text')],
          [[ITEM, DISPATCH, PLAN_ITEM, SHELF, '3', 'weekly_placement_photo_report', PRIVATE_REF]])
    table('app_users', [('id', 'uuid'), ('active', 'bool'), ('role', 'text')],
          [[USERS[0], 't', 'mass_manager'], [USERS[1], 't', 'mass_manager'],
           [USERS[2], 'f', 'mass_manager'], [USERS[3], 't', 'advertiser']])
    members = [(BRANCHES[0], USERS[0]), (BRANCHES[1], USERS[0]), (BRANCHES[1], USERS[1]),
               (BRANCHES[2], USERS[2]), (BRANCHES[3], USERS[3]), (BRANCHES[4], USERS[1])]
    table('member_branch_access', [('id', 'uuid'), ('branch_id', 'uuid'), ('user_id', 'uuid')],
          [[f'80000000-0000-4000-8000-{n:012d}', branch, user] for n, (branch, user) in enumerate(members, 1)])
    for name in ('placement_item_client_photos', 'advertising_place_photos'):
        table(name, [('id', 'uuid')], [[ITEM]] if photo and name == 'advertising_place_photos' else [])
    for n in range(50):
        table('preserved_' + f'{n:02d}', [('id', 'text'), ('value', 'text')], [['opaque-' + str(n), 'original-' + str(n)]])
    metadata['tables'].sort(key=lambda t: t['name'])
    result = dict(formatVersion=1, capturedAt='2026-10-29T00:00:00Z', sourceIdentitySha256='b' * 64,
                  sourceSchemaSha256=stage.schema_hash(metadata), sourceBackupSha256='c' * 64,
                  metadata=metadata, sequenceStates=[], tables=rows,
                  checksums=[stage.table_checksum(t, rows[t['name']]) for t in metadata['tables']])
    stage.validate_artifact(result, 57)
    return result


class OwnedAutomationTest(unittest.TestCase):
    def test_verified_snapshot_and_delivery_provenance_precede_projection_and_no_worker_money_or_person(self):
        sql = automation.projection_sql('crm_stage_' + SOURCE_HASH[:16], SOURCE_HASH, 'DO $v$ BEGIN NULL; END $v$;')
        self.assertLess(sql.index('DO $v$'), sql.index('UPDATE public.merch_media_plans'))
        for expected in ('verified delivery projection required', 'projection provenance differs',
                         'photo route projection requires explicit verification', 'source ownership proof differs',
                         'owner audit proof differs', 'native_automation_generated', 'verified_existing_noop'):
            self.assertIn(expected, sql)
        for forbidden in ('INSERT INTO public.merch_task_dispatches', 'INSERT INTO public.merch_task_items',
                          'INSERT INTO public.merch_task_links', 'INSERT INTO public.pharmacist_wallet',
                          'INSERT INTO public.unified_persons', 'ensure_placement_staff_tasks(', 'DELETE ', 'TRUNCATE '):
            self.assertNotIn(forbidden, sql)
        self.assertTrue(sql.startswith('BEGIN ISOLATION LEVEL REPEATABLE READ;'))
        self.assertTrue(sql.endswith('COMMIT;'))

    def test_exact_source_namespace_current_mas_unique_membership_and_original_refs(self):
        sql = automation.projection_sql('crm_stage_' + SOURCE_HASH[:16], SOURCE_HASH, '')
        for expected in ("d.source_system IS NOT DISTINCT FROM 'MERCH_CRM'", 'd.source_id IS NOT DISTINCT FROM s.id', 'd.dispatch_id IS NOT DISTINCT FROM s.dispatch_id',
                         'd.source_payload @> to_jsonb(s)', 'reference_image_ref=s.reference_image_url',
                         'cycle_starts_at=s.cycle_starts_at', 'next_cycle_at=s.next_cycle_at',
                         "u.role::text='mass_manager'", "a.active AND a.source_role='mass_manager'",
                         'HAVING count(*)=1', 'o.admin_account_id IS NOT NULL OR o.person_id IS NOT NULL'):
            self.assertIn(expected, sql)
        owner_insert = sql.split('INSERT INTO public.merch_automation_branch_owners', 1)[1].split(';', 1)[0]
        self.assertNotIn('person_id', owner_insert)
        self.assertNotIn('admin_account_id', owner_insert)

    def test_bad_namespaces_and_partial_snapshot_fail_closed(self):
        for schema in ('public', 'crm_stage_' + 'b' * 16, 'crm_stage_;drop'):
            with self.subTest(schema=schema), self.assertRaises(stage.StageError):
                automation.projection_sql(schema, SOURCE_HASH, '')
        artifact = synthetic_artifact()
        artifact['metadata']['tables'].pop()
        with self.assertRaises(stage.StageError): stage.validate_artifact(artifact, 57)


@unittest.skipUnless(os.environ.get('RUN_OWNED_AUTOMATION_PG_TESTS') == '1', 'opt-in disposable PostgreSQL16 fixture')
class OwnedAutomationPostgresTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temp.name).resolve(); cls.root.chmod(0o700)
        cls.container_id = None
        cls.addClassCleanup(cls.cleanup)
        image = subprocess.run(['docker', 'image', 'inspect', 'postgres:16', '--format', '{{.Id}}'], capture_output=True, text=True, check=True).stdout.strip()
        if not re.fullmatch(r'sha256:[0-9a-f]{64}', image): raise AssertionError('cached image pin required')
        cls.name = 'epharm-stage-test-automation-' + str(time.time_ns())
        env = cls.root / 'postgres.env'
        env.write_text('POSTGRES_USER=synthetic\nPOSTGRES_DB=epharm_unified_candidate\nPOSTGRES_PASSWORD=synthetic-fixture-only\n')
        env.chmod(0o600)
        result = subprocess.run(['docker', 'run', '--pull=never', '-d', '--network=none', '--name', cls.name,
                                 '--env-file', str(env), image], capture_output=True, text=True, check=True)
        cls.container_id = result.stdout.strip()
        for _ in range(100):
            logs = subprocess.run(['docker', 'logs', cls.container_id], capture_output=True, text=True, check=True)
            if (logs.stdout + logs.stderr).count('database system is ready to accept connections') >= 2: break
            time.sleep(0.1)
        else: raise AssertionError('synthetic PG16 startup timed out')
        cls.db = stage.Database(cls.name, 'synthetic', 'epharm_unified_candidate')
        cls.target = cls.db.inspect()
        cls.db.run('''CREATE TABLE admin_users(id uuid PRIMARY KEY); CREATE TABLE unified_persons(id uuid PRIMARY KEY);
          CREATE TABLE merch_branches(id uuid PRIMARY KEY);
          CREATE TABLE merch_media_plan_items(id uuid PRIMARY KEY); CREATE TABLE merch_shelves(id uuid PRIMARY KEY);
          CREATE TABLE merch_media_plans(id uuid PRIMARY KEY,source_system text,source_origin text,source_id uuid,source_payload jsonb,reviewed_at timestamptz);
          CREATE TABLE merch_task_dispatches(id uuid PRIMARY KEY,source_system text,source_origin text,source_id uuid,source_payload jsonb,
            branch_id uuid REFERENCES merch_branches,media_plan_id uuid,status text,automation_kind text);
          CREATE TABLE merch_task_items(id uuid PRIMARY KEY,source_id uuid,source_payload jsonb,dispatch_id uuid REFERENCES merch_task_dispatches);
          CREATE TABLE unified_external_accounts(id uuid PRIMARY KEY,source_system text,source_origin text,source_payload jsonb,active boolean,source_role text);
          CREATE TABLE unified_import_runs(operation_key text PRIMARY KEY,source_sha256 text,identity_sha256 text,result_json jsonb);
          CREATE TABLE merch_task_links(id text PRIMARY KEY,token_hash text);
          CREATE TABLE pharmacist_wallet_entries(id text PRIMARY KEY,amount bigint);
        ''')
        migrations = ROOT / 'admin-panel/backend/src/main/resources/db/migration'
        cls.db.run(next(migrations.glob('V076__*.sql')).read_text())

    @classmethod
    def cleanup(cls):
        if cls.container_id:
            subprocess.run(['docker', 'rm', '-f', '-v', cls.container_id], capture_output=True, check=True)
        cls.temp.cleanup()

    def setUp(self):
        self.prepare(synthetic_artifact())

    def prepare(self, artifact):
        if getattr(type(self), 'loaded_schema', None):
            self.db.run('DROP SCHEMA ' + stage.qi(type(self).loaded_schema) + ' CASCADE;')
        self.artifact = artifact
        self.path = self.root / 'artifact.json'; self.path.write_bytes(stage.encoded(artifact)); self.path.chmod(0o600)
        self.source_hash = stage.file_hash(self.path)
        self.schema = 'crm_stage_' + self.source_hash[:16]
        type(self).loaded_schema = self.schema
        self.db.run('''TRUNCATE merch_automation_owner_audit,merch_automation_branch_owners,merch_task_items,merch_task_dispatches,
          merch_media_plans,merch_media_plan_items,merch_shelves,merch_branches,unified_external_accounts,unified_import_runs,
          merch_task_links,pharmacist_wallet_entries;''')
        self.db.run(stage.load_sql(artifact, self.schema, self.source_hash))
        q = stage.qi(self.schema)
        self.db.run("INSERT INTO merch_branches SELECT DISTINCT branch_id FROM " + q + ".member_branch_access;"
                    "INSERT INTO merch_shelves VALUES('" + SHELF + "'); INSERT INTO merch_media_plan_items VALUES('" + PLAN_ITEM + "');"
                    "INSERT INTO merch_media_plans SELECT id,'MERCH_CRM','https://crm.inkar.kz',id,to_jsonb(s),NULL FROM " + q + ".media_plans s;"
                    "INSERT INTO merch_task_dispatches(id,source_system,source_origin,source_id,source_payload,branch_id,media_plan_id,status,automation_kind) SELECT id,'MERCH_CRM','https://crm.inkar.kz',id,to_jsonb(s),branch_id,media_plan_id,status,'weekly_placement_photo_report' FROM " + q + ".staff_task_dispatches s;"
                    "INSERT INTO merch_task_items(id,source_id,source_payload,dispatch_id) SELECT id,id,to_jsonb(s)||'{\"_unification_checklist_source\":[]}'::jsonb,dispatch_id FROM " + q + ".staff_tasks s;"
                    "INSERT INTO unified_external_accounts SELECT id,'MERCH_CRM','https://crm.inkar.kz',to_jsonb(u),active,role FROM " + q + ".app_users u;"
                    "INSERT INTO merch_task_links VALUES('sentinel','SYNTHETIC-PRIVATE-tokenhash'); INSERT INTO pharmacist_wallet_entries VALUES('sentinel',123);"
                    "INSERT INTO unified_import_runs VALUES('owned_merch_delivery:" + self.source_hash[:24] + "','" + self.source_hash + "','" + self.source_hash + "','{}');")

    def cli(self):
        report = self.root / ('report-' + str(time.time_ns()) + '.json')
        command = [sys.executable, str(TOOLS / 'owned_automation.py'), '--container', self.name, '--user', 'synthetic',
                   '--database', 'epharm_unified_candidate', '--artifact', str(self.path), '--artifact-sha256', self.source_hash,
                   '--target-sha256', self.target['identitySha256'], '--report', str(report)]
        result = subprocess.run(command, capture_output=True, text=True)
        combined = result.stdout + result.stderr + report.read_text()
        for secret in (PRIVATE_REF, *BRANCHES, *USERS, PLAN, DISPATCH, ITEM, 'SYNTHETIC-PRIVATE-tokenhash'):
            self.assertNotIn(secret, combined)
        self.assertNotIn('Traceback', combined)
        self.assertEqual(0o600, report.stat().st_mode & 0o777)
        return result

    def projected(self):
        return stage.parse_json(self.readonly("SELECT json_build_array((SELECT count(*) FROM merch_media_plans WHERE reviewed_at IS NOT NULL),(SELECT count(*) FROM merch_automation_branch_owners),(SELECT count(*) FROM merch_automation_owner_audit),(SELECT count(*) FROM unified_import_runs WHERE operation_key LIKE 'owned_merch_automation:%'));"))

    def readonly(self, sql):
        return self.db.readonly('SET LOCAL search_path=pg_catalog,public;' + sql)

    def original_sentinels(self):
        return self.readonly("SELECT encode(sha256(convert_to((SELECT jsonb_agg(to_jsonb(l)) FROM merch_task_links l)::text||(SELECT jsonb_agg(to_jsonb(w)) FROM pharmacist_wallet_entries w)::text,'UTF8')),'hex');").strip()

    def test_projects_exact_cycles_dates_refs_unique_current_owner_and_replay_without_side_effects(self):
        before = self.original_sentinels()
        first = self.cli(); self.assertEqual(0, first.returncode, first.stderr)
        result = json.loads(first.stdout)
        self.assertEqual('materialized_and_verified', result['mode'])
        self.assertEqual((2, 1), (result['verifiedSourceOwnerBranches'], result['ambiguousOwnerBranches']))
        self.assertEqual([1, 2, 2, 1], self.projected())
        self.assertEqual('t', self.readonly("SELECT d.recurring_cycle_number=s.recurring_cycle_number AND d.cycle_starts_at=s.cycle_starts_at AND d.next_cycle_at=s.next_cycle_at AND d.status=s.status AND NOT d.native_automation_generated FROM merch_task_dispatches d JOIN " + stage.qi(self.schema) + ".staff_task_dispatches s ON s.id=d.id;").strip())
        self.assertEqual('t', self.readonly("SELECT i.media_plan_item_id=s.media_plan_item_id AND i.shelf_id=s.shelf_id AND i.reference_image_ref=s.reference_image_url AND i.recurring_cycle_number=s.recurring_cycle_number FROM merch_task_items i JOIN " + stage.qi(self.schema) + ".staff_tasks s ON s.id=i.id;").strip())
        self.assertEqual('0', self.readonly('SELECT count(*) FROM merch_automation_branch_owners WHERE person_id IS NOT NULL OR admin_account_id IS NOT NULL;').strip())
        repeated = self.cli(); self.assertEqual(0, repeated.returncode, repeated.stderr)
        self.assertEqual('verified_existing_noop', json.loads(repeated.stdout)['mode'])
        self.assertEqual([1, 2, 2, 1], self.projected()); self.assertEqual(before, self.original_sentinels())
        self.db.readonly(stage.verification_sql(self.artifact, self.schema, self.source_hash, True))

    def test_all57_source_checksum_drift_and_existing_cycle_conflict_abort_entire_projection(self):
        q = stage.qi(self.schema)
        self.db.run('UPDATE ' + q + ".preserved_49 SET value='tampered';")
        self.assertNotEqual(0, self.cli().returncode); self.assertEqual([0, 0, 0, 0], self.projected())
        self.db.run('UPDATE ' + q + ".preserved_49 SET value='original-49'; UPDATE merch_task_dispatches SET recurring_cycle_number=99;")
        self.assertNotEqual(0, self.cli().returncode); self.assertEqual([0, 0, 0, 0], self.projected())
        self.assertEqual('99', self.readonly('SELECT recurring_cycle_number FROM merch_task_dispatches;').strip())

    def test_missing_old_run_wrong_provenance_namespace_and_replay_audit_fail_closed(self):
        self.db.run('DELETE FROM unified_import_runs;')
        self.assertNotEqual(0, self.cli().returncode); self.assertEqual([0, 0, 0, 0], self.projected())
        self.db.run("INSERT INTO unified_import_runs VALUES('owned_merch_delivery:" + self.source_hash[:24] + "','wrong','" + self.source_hash + "','{}');")
        self.assertNotEqual(0, self.cli().returncode); self.assertEqual([0, 0, 0, 0], self.projected())
        self.db.run("UPDATE unified_import_runs SET source_sha256='" + self.source_hash + "'; UPDATE merch_media_plans SET source_system='EPHARM';")
        self.assertNotEqual(0, self.cli().returncode); self.assertEqual([0, 0, 0, 0], self.projected())
        self.db.run("UPDATE merch_media_plans SET source_system='MERCH_CRM'; UPDATE merch_task_items SET source_id=NULL;")
        self.assertNotEqual(0, self.cli().returncode); self.assertEqual([0, 0, 0, 0], self.projected())
        self.db.run('UPDATE merch_task_items SET source_id=id;')
        self.assertEqual(0, self.cli().returncode)
        self.db.run('TRUNCATE merch_automation_owner_audit;')
        self.assertNotEqual(0, self.cli().returncode); self.assertEqual([1, 2, 0, 1], self.projected())

    def test_verified_future_photo_snapshot_and_revoked_current_registry_never_guess_routes_or_owner(self):
        self.prepare(synthetic_artifact(photo=True))
        self.db.readonly(stage.verification_sql(self.artifact, self.schema, self.source_hash, True))
        self.assertNotEqual(0, self.cli().returncode); self.assertEqual([0, 0, 0, 0], self.projected())
        self.prepare(synthetic_artifact())
        self.db.run('UPDATE unified_external_accounts SET active=false;')
        result = self.cli(); self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(0, json.loads(result.stdout)['verifiedSourceOwnerBranches'])
        self.assertEqual([1, 0, 0, 1], self.projected())


if __name__ == '__main__': unittest.main()
