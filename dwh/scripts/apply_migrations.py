from __future__ import annotations

import argparse
import hashlib
from pathlib import Path

from clickhouse_http import ClickHouseClient, DWH_ROOT


MIGRATION_GLOB = "[0-9][0-9][0-9]_*.sql"


def migration_files(sql_dir: Path | None = None) -> list[Path]:
    return sorted((sql_dir or DWH_ROOT / "sql").glob(MIGRATION_GLOB))


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Apply ordered eFarm DWH migrations")
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="List migrations without connecting to ClickHouse",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.dry_run:
        for path in migration_files():
            print(path.name)
        return

    client = ClickHouseClient()
    if client.execute("SELECT 1") != "1":
        raise RuntimeError("ClickHouse health check returned an unexpected response")

    metadata_ready = client.execute(
        "SELECT count() FROM system.tables "
        "WHERE database = 'dwh_meta' AND name = 'schema_migration'"
    ) == "1"

    for path in migration_files():
        sql = path.read_text(encoding="utf-8")
        digest = hashlib.sha256(sql.encode("utf-8")).hexdigest()
        filename = path.name

        if metadata_ready:
            result = client.execute(
                "SELECT sha256 FROM dwh_meta.schema_migration FINAL "
                f"WHERE filename = {client.quote(filename)} LIMIT 1"
            )
            if result:
                if result != digest:
                    raise RuntimeError(
                        f"Migration {filename} was changed after application; "
                        "create a new migration instead"
                    )
                print(f"skip  {filename}")
                continue

        client.execute_script(sql)
        metadata_ready = client.execute(
            "SELECT count() FROM system.tables "
            "WHERE database = 'dwh_meta' AND name = 'schema_migration'"
        ) == "1"
        if metadata_ready:
            client.execute(
                "INSERT INTO dwh_meta.schema_migration (filename, sha256) VALUES "
                f"({client.quote(filename)}, {client.quote(digest)})"
            )
        print(f"apply {filename}")


if __name__ == "__main__":
    main()
