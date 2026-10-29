from pathlib import Path
import sys
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import crm_stage as stage
import owned_legacy_caps


class LegacyCapabilityProjectionTest(unittest.TestCase):
    def test_exact_lineage_and_immutable_noop_without_new_authority(self):
        sql = owned_legacy_caps.projection_sql('crm_stage_' + 'a' * 16, 'a' * 64, 'SELECT 1;')
        for proof in ('staff_task_kiosk_links', 'staff_task_kiosk_devices', 'l.source_payload IS DISTINCT FROM to_jsonb(s)',
                      'l.expires_at IS DISTINCT FROM s.expires_at', 'd.source_system', 'source_artifact_sha256',
                      'FULL JOIN _legacy_caps_expected', 'verified_existing_noop'):
            self.assertIn(proof, sql)
        for forbidden in ('SET native_owned', 'UPDATE public.', 'DELETE ', 'TRUNCATE ',
                          'INSERT INTO public.merch_task_links', 'INSERT INTO public.pharmacist', 'wallet'):
            self.assertNotIn(forbidden, sql)
        self.assertTrue(sql.endswith('COMMIT;'))

    def test_untrusted_schema_cannot_select_other_source(self):
        for schema in ('public', 'crm_stage_' + 'b' * 16, 'crm_stage_;drop'):
            with self.subTest(schema=schema), self.assertRaises(stage.StageError):
                owned_legacy_caps.projection_sql(schema, 'a' * 64, '')


if __name__ == '__main__':
    unittest.main()
