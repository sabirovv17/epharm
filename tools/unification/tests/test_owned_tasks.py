from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import crm_stage as stage
import owned_tasks


class OwnedTasksTest(unittest.TestCase):
    def test_projection_retains_checklist_proof_and_separates_unverified_actor_namespaces(self):
        projections = {target: fields for target, source, fields, join in owned_tasks.projections('crm_stage_' + 'a' * 16)}
        item = projections['merch_task_items']
        self.assertIn('_unification_checklist_source', item['source_payload'])
        self.assertIn('ORDER BY c.position,c.id', item['checklist'])
        dispatch = projections['merch_task_dispatches']
        self.assertEqual(dispatch['source_created_by'], 's.created_by')
        self.assertNotIn('created_by', dispatch)
        self.assertNotIn('assigned_pharmacist_id', dispatch)
        self.assertEqual(item['status'], 's.status')
        self.assertEqual(projections['merch_task_links']['token_hash'], 's.token_hash')
        self.assertNotIn('native_owned', projections['merch_task_links'])

    def test_import_refuses_unknown_attempts_and_never_replays_money_or_automation(self):
        sql = owned_tasks.import_sql('crm_stage_'+'a'*16, 'a'*64,
            {key: 0 for key in owned_tasks.SOURCE_TABLES}, 'DO $v$ BEGIN NULL; END $v$;')
        self.assertTrue(sql.startswith('BEGIN ISOLATION LEVEL REPEATABLE READ;'))
        self.assertTrue(sql.endswith('COMMIT;'))
        self.assertIn('verified_existing_noop', sql)
        self.assertIn('standalone task requires explicit disposition', sql)
        self.assertIn('unexpected reconstructed attempt', sql)
        self.assertIn('unexpected reward during task import', sql)
        for word in ('UPDATE ', 'DELETE ', 'TRUNCATE ', 'INSERT INTO public.pharmacist_wallet', 'ensure_placement_staff_tasks('):
            self.assertNotIn(word, sql)

    def test_schema_injection_is_rejected(self):
        for bad in ('public', 'crm_stage_'+'a'*15, 'crm_stage_'+'x'*16, 'crm_stage_; DROP TABLE users'):
            with self.subTest(schema=bad), self.assertRaises(stage.StageError): owned_tasks.projections(bad)


if __name__ == '__main__': unittest.main()
