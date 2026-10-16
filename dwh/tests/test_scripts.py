from __future__ import annotations

import sys
import unittest
from datetime import date
from pathlib import Path


DWH_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(DWH_ROOT / "scripts"))

from apply_migrations import migration_files  # noqa: E402
from clickhouse_http import split_sql_statements  # noqa: E402
from sync_standardn_daily import render_job, validate_identifier  # noqa: E402
from sync_storefront import (  # noqa: E402
    ZERO_HASH,
    analytics_consent,
    hash_identifier,
    orders_and_items,
    parse_timestamp,
    safe_page_path,
)


class ScriptTest(unittest.TestCase):
    def test_migrations_are_sorted_and_complete(self) -> None:
        names = [path.name for path in migration_files()]
        self.assertEqual(names, sorted(names))
        self.assertEqual(names[0], "001_databases.sql")
        self.assertEqual(names[-1], "009_storefront_marts.sql")

    def test_statement_splitter_ignores_semicolons_in_strings_and_comments(self) -> None:
        sql = "SELECT 'a;b'; -- keep; comment\nSELECT 2;"
        self.assertEqual(split_sql_statements(sql), ["SELECT 'a;b'", "-- keep; comment\nSELECT 2"])

    def test_job_renderer_replaces_only_valid_identifiers(self) -> None:
        rendered = render_job(
            "SELECT * FROM {{source_database}}.{{source_table}} "
            "WHERE d='{{snapshot_date}}'",
            date(2026, 10, 3),
            "pharmacy_analytics",
            "fact_pharmacy_daily",
        )
        self.assertEqual(
            rendered,
            "SELECT * FROM pharmacy_analytics.fact_pharmacy_daily WHERE d='2026-10-03'",
        )

    def test_job_renderer_rejects_identifier_injection(self) -> None:
        with self.assertRaises(ValueError):
            validate_identifier("source; DROP DATABASE dwh_core", "database")

    def test_storefront_customer_identifiers_are_pseudonymized(self) -> None:
        first = hash_identifier("customer-1", "a-private-test-salt")
        second = hash_identifier("customer-1", "a-private-test-salt")
        self.assertEqual(first, second)
        self.assertEqual(len(first), 64)
        self.assertNotIn("customer-1", first)
        self.assertEqual(hash_identifier("", "a-private-test-salt"), ZERO_HASH)

    def test_storefront_order_metadata_is_normalized_without_pii(self) -> None:
        timestamp = parse_timestamp("2026-10-04T10:00:00Z")
        headers, items = orders_and_items(
            [
                {
                    "id": "order-1",
                    "source_system": "storefront",
                    "source_order_id": "order-1",
                    "display_number": 100,
                    "placed_at": timestamp,
                    "updated_at": timestamp,
                    "total_amount": "2000.00",
                    "currency_code": "kzt",
                    "status": "new",
                    "status_version": 1,
                    "item_count": 2,
                    "delivery_method": "pickup",
                    "customer_id": "customer-1",
                    "is_demo": False,
                    "fulfillment_status": "submitted",
                    "fulfillment_version": 1,
                    "metadata": {
                        "phone": "+77000000000",
                        "email": "secret@example.com",
                        "delivery_address": "private address",
                        "pharmacy_id": "ph-1",
                        "line_items": [
                            {"product_id": "prod-1", "quantity": 2, "unit_price": 1000}
                        ],
                    },
                }
            ],
            "00000000-0000-0000-0000-000000000001",
            "a-private-test-salt",
        )
        serialized = str(headers) + str(items)
        self.assertNotIn("+77000000000", serialized)
        self.assertNotIn("secret@example.com", serialized)
        self.assertNotIn("private address", serialized)
        self.assertEqual(items[0]["quantity"], "2")

    def test_analytics_consent_requires_explicit_grant(self) -> None:
        self.assertTrue(analytics_consent({"analytics": "granted"}))
        self.assertFalse(analytics_consent({"analytics": "denied"}))
        self.assertFalse(analytics_consent({}))

    def test_storefront_page_path_drops_query_and_fragment(self) -> None:
        self.assertEqual(
            safe_page_path("https://shop.example/catalog?email=secret@example.com#details"),
            "/catalog",
        )


if __name__ == "__main__":
    unittest.main()
