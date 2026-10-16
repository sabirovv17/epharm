from __future__ import annotations

import re
import unittest
from pathlib import Path


DWH_ROOT = Path(__file__).resolve().parents[1]
SQL_ROOT = DWH_ROOT / "sql"


class WarehouseContractTest(unittest.TestCase):
    def read(self, filename: str) -> str:
        return (SQL_ROOT / filename).read_text(encoding="utf-8").lower()

    def test_required_layers_are_created(self) -> None:
        sql = self.read("001_databases.sql")
        for database in ("dwh_raw", "dwh_core", "dwh_marts", "dwh_meta"):
            self.assertIn(f"create database if not exists {database}", sql)

    def test_required_dimensions_exist(self) -> None:
        sql = self.read("004_dimensions.sql")
        for table in (
            "dim_branch",
            "dim_pharmacy",
            "dim_product",
            "dim_employee",
            "dim_sales_channel",
            "dim_training_program",
            "dim_training_course",
            "dim_product_mapping",
            "dim_pharmacy_mapping",
        ):
            self.assertRegex(sql, rf"create table if not exists dwh_core\.{table}\b")

    def test_required_facts_exist(self) -> None:
        sql = self.read("005_facts.sql")
        for table in (
            "fact_sales_receipt",
            "fact_sales_item",
            "fact_stock_snapshot",
            "fact_order",
            "fact_order_item",
            "fact_bonus_accrual",
            "fact_merch_task",
            "fact_merch_report",
            "fact_training_assignment",
            "fact_training_progress",
            "fact_training_result",
        ):
            self.assertRegex(sql, rf"create table if not exists dwh_core\.{table}\b")

    def test_facts_have_lineage_and_load_timestamp(self) -> None:
        sql = self.read("005_facts.sql")
        tables = re.split(r"create table if not exists ", sql)[1:]
        self.assertGreater(len(tables), 0)
        for definition in tables:
            table_name = definition.split("(", 1)[0].strip()
            self.assertIn("source_system", definition, table_name)
            self.assertIn("loaded_at", definition, table_name)

    def test_standardn_identifier_remains_string_in_raw(self) -> None:
        sql = self.read("003_raw.sql")
        self.assertRegex(sql, r"ware_id\s+string")
        self.assertNotRegex(sql, r"ware_id\s+uuid")

    def test_marts_keep_daily_bridge_separate_from_receipts(self) -> None:
        sql = self.read("006_marts.sql")
        self.assertIn("dwh_marts.sales_by_day", sql)
        self.assertIn("dwh_marts.standardn_sales_by_day", sql)

    def test_storefront_contract_exists_without_direct_customer_pii(self) -> None:
        raw = self.read("008_storefront.sql")
        marts = self.read("009_storefront_marts.sql")
        for table in (
            "storefront_catalog_product",
            "storefront_catalog_variant",
            "storefront_pharmacy_offer",
            "storefront_warehouse_stock",
            "storefront_site_order",
            "storefront_site_order_item",
            "storefront_customer_event",
        ):
            self.assertIn(f"dwh_raw.{table}", raw)
        for forbidden in ("phone string", "email string", "delivery_address string"):
            self.assertNotIn(forbidden, raw)
        self.assertIn("dwh_core.fact_storefront_order", raw)
        self.assertIn("dwh_marts.storefront_orders_by_day", marts)
        self.assertIn("dwh_marts.storefront_funnel_by_day", marts)


if __name__ == "__main__":
    unittest.main()
