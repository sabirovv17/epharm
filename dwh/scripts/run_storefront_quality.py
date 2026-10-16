from __future__ import annotations

import argparse
import sys
from dataclasses import dataclass

from clickhouse_http import ClickHouseClient


@dataclass(frozen=True)
class Check:
    name: str
    entity: str
    sql: str
    warning_only: bool = False


CHECKS = [
    Check(
        "storefront_product_mapping_is_unique",
        "dwh_core.dim_product_mapping",
        """SELECT count() FROM
           (
               SELECT identifier_value
               FROM dwh_core.dim_product_mapping FINAL
               WHERE source_system = 'storefront'
                 AND identifier_type = 'storefront_product_id'
                 AND is_current
               GROUP BY identifier_value
               HAVING uniqExact(product_key) > 1
           )""",
    ),
    Check(
        "storefront_confirmed_ware_mapping_is_unique",
        "dwh_raw.storefront_catalog_product",
        """SELECT count() FROM
           (
               SELECT lower(ware_id)
               FROM dwh_raw.storefront_catalog_product FINAL
               WHERE notEmpty(ware_id) AND notEmpty(mapping_source) AND active
               GROUP BY lower(ware_id)
               HAVING uniqExact(source_product_id) > 1
           )""",
    ),
    Check(
        "storefront_published_snapshot_is_complete",
        "dwh_core.storefront_snapshot_manifest",
        """SELECT
               toUInt64((SELECT count() FROM dwh_core.storefront_snapshot_manifest FINAL) = 0)
               + toUInt64(
                   (SELECT argMax(offer_count, captured_at)
                    FROM dwh_core.storefront_snapshot_manifest FINAL)
                   !=
                   (SELECT count()
                    FROM dwh_core.fact_storefront_offer_snapshot FINAL
                    WHERE captured_at =
                        (SELECT max(captured_at)
                         FROM dwh_core.storefront_snapshot_manifest FINAL))
               )
               + toUInt64(
                   (SELECT argMax(warehouse_stock_count, captured_at)
                    FROM dwh_core.storefront_snapshot_manifest FINAL)
                   !=
                   (SELECT count()
                    FROM dwh_core.fact_storefront_warehouse_snapshot FINAL
                    WHERE captured_at =
                        (SELECT max(captured_at)
                         FROM dwh_core.storefront_snapshot_manifest FINAL))
               )
               + toUInt64(
                   (SELECT argMax(product_category_count, captured_at)
                    FROM dwh_core.storefront_snapshot_manifest FINAL)
                   !=
                   (SELECT count()
                    FROM dwh_core.bridge_product_category_snapshot FINAL
                    WHERE captured_at =
                        (SELECT max(captured_at)
                         FROM dwh_core.storefront_snapshot_manifest FINAL))
               )""",
    ),
    Check(
        "storefront_offer_product_is_mapped",
        "dwh_core.fact_storefront_offer_snapshot",
        """SELECT count()
           FROM dwh_core.fact_storefront_offer_snapshot FINAL AS f
           LEFT JOIN dwh_core.dim_product FINAL AS p ON p.product_key = f.product_key
           WHERE p.product_key = ''""",
    ),
    Check(
        "storefront_offer_pharmacy_is_mapped",
        "dwh_core.fact_storefront_offer_snapshot",
        """SELECT count()
           FROM dwh_core.fact_storefront_offer_snapshot FINAL AS f
           LEFT JOIN dwh_core.dim_pharmacy FINAL AS p ON p.pharmacy_key = f.pharmacy_key
           WHERE p.pharmacy_key = ''""",
    ),
    Check(
        "storefront_order_items_have_header",
        "dwh_core.fact_order_item",
        """SELECT count()
           FROM dwh_core.fact_order_item FINAL AS i
           LEFT JOIN dwh_core.fact_order FINAL AS o ON o.order_key = i.order_key
           WHERE i.source_system = 'storefront' AND o.order_key = ''""",
    ),
    Check(
        "storefront_order_quantity_matches",
        "dwh_raw.storefront_site_order",
        """SELECT count()
           FROM dwh_raw.storefront_site_order FINAL AS o
           LEFT JOIN
           (
               SELECT source_order_id, sum(quantity) AS quantity
               FROM dwh_raw.storefront_site_order_item FINAL
               GROUP BY source_order_id
           ) AS i ON i.source_order_id = o.source_order_id
           WHERE abs(toDecimal64(o.item_count, 4) - ifNull(i.quantity, 0)) > 0.0001""",
    ),
    Check(
        "storefront_order_line_has_price",
        "dwh_core.fact_order_item",
        """SELECT count()
           FROM dwh_core.fact_order_item FINAL
           WHERE source_system = 'storefront' AND is_price_missing""",
        warning_only=True,
    ),
    Check(
        "storefront_checkpoint_is_fresh",
        "dwh_meta.ingestion_checkpoint",
        """SELECT if(
                 dateDiff(
                     'hour',
                     maxIf(watermark, source_system = 'storefront' AND job_name = 'storefront_incremental'),
                     now()
                 ) > 2,
                 1,
                 0
             )
           FROM dwh_meta.ingestion_checkpoint FINAL""",
        warning_only=True,
    ),
    Check(
        "storefront_raw_has_no_direct_customer_pii_columns",
        "system.columns",
        """SELECT count()
           FROM system.columns
           WHERE database = 'dwh_raw'
             AND table IN ('storefront_site_order', 'storefront_site_order_item', 'storefront_customer_event')
             AND lower(name) IN ('phone', 'email', 'customer_name', 'delivery_address', 'comment')""",
    ),
]


def parse_args() -> argparse.Namespace:
    return argparse.ArgumentParser(description="Run storefront DWH quality checks").parse_args()


def main() -> None:
    parse_args()
    client = ClickHouseClient()
    failed = 0
    for check in CHECKS:
        failed_count = int(client.execute(check.sql))
        status = "passed" if failed_count == 0 else "warning" if check.warning_only else "failed"
        if status == "failed":
            failed += 1
        details = f"failed_count={failed_count}; threshold=0"
        client.execute(
            "INSERT INTO dwh_meta.data_quality_check "
            "(check_name, entity, status, failed_count, threshold, details) VALUES ("
            f"{client.quote(check.name)}, {client.quote(check.entity)}, {client.quote(status)}, "
            f"{failed_count}, 0, {client.quote(details)})"
        )
        print(f"{status:7} {check.name}: {failed_count}")
    if failed:
        print(f"Storefront quality gate failed: {failed} critical check(s)", file=sys.stderr)
        raise SystemExit(1)


if __name__ == "__main__":
    main()
