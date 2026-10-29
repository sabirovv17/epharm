import sys
from pathlib import Path
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import crm_stage as stage
import owned_extended as extended


class ExtendedProjectionTest(unittest.TestCase):
    def test_rejects_unpinned_staging_before_generating_sql(self):
        for schema,digest in [('public','a'*64),('crm_stage_'+'b'*16,'a'*64),('crm_stage_'+'a'*16,'invalid')]:
            with self.assertRaises(stage.StageError):extended.projection_sql(schema,digest,'SELECT 1;')

    def test_retains_exact_source_money_ids_and_historical_products_without_replaying_business_actions(self):
        entries={source:fields for _,source,_,fields in extended.projections()}
        self.assertEqual(entries['supplier_budget_plan_items']['unit_price'],'s.unit_price')
        self.assertEqual(entries['supplier_budget_plans']['request_ids'],'s.request_ids')
        self.assertEqual(entries['placement_item_product_details']['products'],'s.products')
        self.assertEqual(entries['supplier_company_members']['producer_id'],'s.supplier_user_id')
        self.assertEqual(entries['staff_task_stock_observations']['item_id'],'s.task_id')
        sql=extended.projection_sql('crm_stage_'+'a'*16,'a'*64,'SELECT 12345;')
        self.assertLess(sql.index('SELECT 12345;'),sql.index('INSERT INTO public.merch_supplier_pricing_tiers'))
        self.assertIn('verified weekly projection required',sql)
        self.assertIn('extended provenance differs',sql)
        self.assertIn('extended source fields differ',sql)
        self.assertIn('AND p.submitted_by=b.supplier_user_id',sql)
        self.assertIn('unresolvedBudgetReferences',sql)
        for table in ('pharmacists','unified_persons','admin_users','pharmacist_wallet_entries','merch_task_dispatches','merch_task_links'):
            self.assertNotIn('INSERT INTO public.'+table+'(',sql)
            self.assertNotIn('UPDATE public.'+table+' ',sql)

    def test_source_instructions_and_photos_need_explicit_private_file_lineage(self):
        sql=extended.projection_sql('crm_stage_'+'a'*16,'a'*64,'SELECT 1;')
        self.assertIn('private media projection requires verified object mapping',sql)
        self.assertIn('pharmacy_training_materials',sql)
        self.assertNotIn('https://www.',sql)
        self.assertNotIn('DELETE ',sql)
        self.assertIn('verified_existing_noop',sql)


if __name__=='__main__':unittest.main()
