from __future__ import annotations

import argparse
import sys
import uuid
from dataclasses import dataclass
from datetime import date

from clickhouse_http import ClickHouseClient


@dataclass(frozen=True)
class Check:
    name: str
    entity: str
    sql: str
    threshold: int = 0
    warning_only: bool = False


def checks_for(snapshot_date: date) -> list[Check]:
    day = snapshot_date.isoformat()
    return [
        Check(
            "source_partition_not_empty",
            "dwh_raw.standardn_pharmacy_daily",
            "SELECT if(count() = 0, 1, 0) FROM dwh_raw.standardn_pharmacy_daily FINAL "
            f"WHERE snapshot_date = toDate('{day}')",
        ),
        Check(
            "ware_id_is_present",
            "dwh_raw.standardn_pharmacy_daily",
            "SELECT count() FROM dwh_raw.standardn_pharmacy_daily FINAL "
            f"WHERE snapshot_date = toDate('{day}') AND empty(trim(ware_id))",
        ),
        Check(
            "profile_id_is_valid",
            "dwh_raw.standardn_pharmacy_daily",
            "SELECT count() FROM dwh_raw.standardn_pharmacy_daily FINAL "
            f"WHERE snapshot_date = toDate('{day}') AND profile_id = 0",
        ),
        Check(
            "stock_product_is_mapped",
            "dwh_core.fact_stock_snapshot",
            "SELECT count() FROM dwh_core.fact_stock_snapshot FINAL AS f "
            "LEFT JOIN dwh_core.dim_product FINAL AS p ON p.product_key = f.product_key "
            f"WHERE f.snapshot_date = toDate('{day}') AND p.product_key = ''",
        ),
        Check(
            "stock_pharmacy_is_mapped",
            "dwh_core.fact_stock_snapshot",
            "SELECT count() FROM dwh_core.fact_stock_snapshot FINAL AS f "
            "LEFT JOIN dwh_core.dim_pharmacy FINAL AS p ON p.pharmacy_key = f.pharmacy_key "
            f"WHERE f.snapshot_date = toDate('{day}') AND p.pharmacy_key = ''",
        ),
        Check(
            "available_stock_is_non_negative",
            "dwh_core.fact_stock_snapshot",
            "SELECT count() FROM dwh_core.fact_stock_snapshot FINAL "
            f"WHERE snapshot_date = toDate('{day}') AND quantity_available < 0",
        ),
        Check(
            "source_is_fresh",
            "dwh_raw.standardn_pharmacy_daily",
            "SELECT if(dateDiff('day', toDate('"
            + day
            + "'), today()) > 2, 1, 0)",
            warning_only=True,
        ),
    ]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run DWH quality checks")
    parser.add_argument("--date", type=date.fromisoformat, help="YYYY-MM-DD; default latest raw date")
    parser.add_argument("--job-run-id", type=uuid.UUID)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    client = ClickHouseClient()
    snapshot_date = args.date
    if snapshot_date is None:
        raw = client.execute(
            "SELECT toString(max(snapshot_date)) FROM dwh_raw.standardn_pharmacy_daily"
        )
        if not raw or raw == "1970-01-01":
            raise RuntimeError("No Standard-N data is available for quality checks")
        snapshot_date = date.fromisoformat(raw)

    failed_critical = 0
    for check in checks_for(snapshot_date):
        failed_count = int(client.execute(check.sql))
        if failed_count <= check.threshold:
            status = "passed"
        elif check.warning_only:
            status = "warning"
        else:
            status = "failed"
            failed_critical += 1
        details = (
            f"snapshot_date={snapshot_date.isoformat()}; "
            f"failed_count={failed_count}; threshold={check.threshold}"
        )
        job_run_id = "NULL" if args.job_run_id is None else f"toUUID('{args.job_run_id}')"
        client.execute(
            "INSERT INTO dwh_meta.data_quality_check "
            "(job_run_id, check_name, entity, status, failed_count, threshold, details) VALUES ("
            f"{job_run_id}, {client.quote(check.name)}, {client.quote(check.entity)}, "
            f"{client.quote(status)}, {failed_count}, {check.threshold}, {client.quote(details)})"
        )
        print(f"{status:7} {check.name}: {failed_count}")

    if failed_critical:
        print(f"Data-quality gate failed: {failed_critical} critical check(s)", file=sys.stderr)
        raise SystemExit(1)


if __name__ == "__main__":
    main()
