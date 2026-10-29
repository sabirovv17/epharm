from pathlib import Path
import sys
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import owned_delivery
import crm_stage as stage


class OwnedDeliveryTest(unittest.TestCase):
    def test_projection_requires_verified_task_import_and_never_reissues_bearer_or_ack(self):
        sql=owned_delivery.projection_sql('crm_stage_'+'a'*16,'a'*64,'DO $verify$ BEGIN NULL; END $verify$;')
        self.assertIn('verified task import required',sql)
        self.assertIn("s.issued_channel='pharmapay_posm' AND b.pharmacy_binding_verified",sql)
        self.assertIn('LEFT JOIN public.merch_media_plans p ON p.id=s.media_plan_id',sql)
        self.assertIn('l.token_hash IS DISTINCT FROM s.token_hash',sql)
        self.assertNotIn('SET token_hash',sql)
        self.assertNotIn('INSERT INTO public.merch_task_delivery_receipts',sql)
        self.assertNotIn('INSERT INTO public.merch_task_links',sql)
        self.assertNotIn('pharmacist_wallet',sql)
        self.assertTrue(sql.endswith('COMMIT;'))

    def test_namespace_drift_or_injection_is_rejected(self):
        for schema in ('crm_stage_'+'b'*16,'public','crm_stage_;drop'):
            with self.subTest(schema=schema),self.assertRaises(stage.StageError):
                owned_delivery.projection_sql(schema,'a'*64,'')


if __name__=='__main__':unittest.main()
