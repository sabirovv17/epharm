from __future__ import annotations

import argparse
import os
import re
import uuid
from datetime import date, datetime, timezone
from pathlib import Path

from clickhouse_http import ClickHouseClient, DWH_ROOT, load_env_file


IDENTIFIER = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")


def validate_identifier(value: str, label: str) -> str:
    if not IDENTIFIER.fullmatch(value):
        raise ValueError(f"Invalid ClickHouse {label}: {value!r}")
    return value


def render_job(template: str, snapshot_date: date, database: str, table: str) -> str:
    database = validate_identifier(database, "database")
    table = validate_identifier(table, "table")
    return (
        template.replace("{{snapshot_date}}", snapshot_date.isoformat())
        .replace("{{source_database}}", database)
        .replace("{{source_table}}", table)
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Load one Standard-N day into the eFarm DWH")
    parser.add_argument(
        "--date",
        type=date.fromisoformat,
        help="Snapshot date in YYYY-MM-DD; defaults to the latest source partition",
    )
    return parser.parse_args()


def insert_run(
    client: ClickHouseClient,
    job_run_id: str,
    snapshot_date: date,
    status: str,
    *,
    rows_read: int = 0,
    rows_written: int = 0,
    error: str = "",
) -> None:
    finished = "NULL" if status == "running" else "now64(3)"
    client.execute(
        "INSERT INTO dwh_meta.etl_job_run "
        "(job_run_id, job_name, source_system, window_start, window_end, "
        "started_at, finished_at, status, rows_read, rows_written, error_message) VALUES ("
        f"toUUID({client.quote(job_run_id)}), 'standardn_daily', 'standardn', "
        f"toDateTime64({client.quote(snapshot_date.isoformat() + ' 00:00:00')}, 3, 'UTC'), "
        f"toDateTime64({client.quote(snapshot_date.isoformat() + ' 23:59:59')}, 3, 'UTC'), "
        f"now64(3), {finished}, {client.quote(status)}, {rows_read}, {rows_written}, "
        f"{client.quote(error[:4000])})"
    )


def main() -> None:
    args = parse_args()
    load_env_file()
    client = ClickHouseClient()
    database = validate_identifier(
        os.getenv("CLICKHOUSE_SOURCE_DATABASE", "pharmacy_analytics"), "database"
    )
    table = validate_identifier(
        os.getenv("CLICKHOUSE_SOURCE_TABLE", "fact_pharmacy_daily"), "table"
    )

    snapshot_date = args.date
    if snapshot_date is None:
        latest = client.execute(f"SELECT toString(max(snapshot_date)) FROM {database}.{table}")
        if not latest or latest == "1970-01-01":
            raise RuntimeError(f"Source {database}.{table} has no snapshot partitions")
        snapshot_date = date.fromisoformat(latest)

    job_run_id = str(uuid.uuid4())
    source_filter = f"snapshot_date = toDate({client.quote(snapshot_date.isoformat())})"
    rows_read = int(client.execute(f"SELECT count() FROM {database}.{table} WHERE {source_filter}"))
    if rows_read == 0:
        raise RuntimeError(f"No Standard-N rows found for {snapshot_date.isoformat()}")

    insert_run(client, job_run_id, snapshot_date, "running", rows_read=rows_read)
    try:
        template = (DWH_ROOT / "sql" / "jobs" / "standardn_daily.sql").read_text(
            encoding="utf-8"
        )
        client.execute_script(render_job(template, snapshot_date, database, table))
        rows_written = int(
            client.execute(
                "SELECT "
                "(SELECT count() FROM dwh_core.fact_stock_snapshot FINAL "
                f"WHERE snapshot_date = toDate({client.quote(snapshot_date.isoformat())}) "
                "AND source_system = 'standardn') + "
                "(SELECT count() FROM dwh_core.fact_sales_daily FINAL "
                f"WHERE sale_date = toDate({client.quote(snapshot_date.isoformat())}) "
                "AND source_system = 'standardn')"
            )
        )
        insert_run(
            client,
            job_run_id,
            snapshot_date,
            "succeeded",
            rows_read=rows_read,
            rows_written=rows_written,
        )
        client.execute(
            "INSERT INTO dwh_meta.ingestion_checkpoint "
            "(source_system, job_name, cursor_value, watermark) VALUES ("
            f"'standardn', 'standardn_daily', {client.quote(snapshot_date.isoformat())}, "
            f"toDateTime64({client.quote(snapshot_date.isoformat() + ' 23:59:59')}, 3, 'UTC'))"
        )
        print(
            f"Standard-N {snapshot_date.isoformat()}: "
            f"read={rows_read}, core_rows={rows_written}, job_run_id={job_run_id}"
        )
    except Exception as exc:
        insert_run(
            client,
            job_run_id,
            snapshot_date,
            "failed",
            rows_read=rows_read,
            error=str(exc),
        )
        raise


if __name__ == "__main__":
    main()
