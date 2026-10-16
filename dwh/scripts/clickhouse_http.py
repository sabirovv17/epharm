from __future__ import annotations

import base64
import json
import os
import re
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


DWH_ROOT = Path(__file__).resolve().parents[1]


def load_env_file(path: Path | None = None) -> None:
    """Load simple KEY=VALUE pairs without overriding process environment."""
    env_path = path or DWH_ROOT / ".env"
    if not env_path.exists():
        return
    for raw_line in env_path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip("\"").strip("'"))


class ClickHouseError(RuntimeError):
    pass


class ClickHouseClient:
    def __init__(self) -> None:
        load_env_file()
        self.url = os.getenv("CLICKHOUSE_URL", "http://127.0.0.1:18123").rstrip("/")
        self.user = os.getenv("CLICKHOUSE_USER", "dwh_admin")
        self.password = os.getenv("CLICKHOUSE_PASSWORD", "change-me-before-use")

    def execute(self, sql: str, *, multiquery: bool = False) -> str:
        params = {"default_format": "TabSeparatedRaw"}
        if multiquery:
            params["multiquery"] = "1"
        return self._post(params, sql.encode("utf-8"))

    def insert_json_rows(
        self,
        table: str,
        rows: list[dict[str, object]],
        *,
        batch_size: int = 2_000,
    ) -> int:
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*\.[A-Za-z_][A-Za-z0-9_]*", table):
            raise ValueError(f"Invalid ClickHouse table name: {table!r}")
        inserted = 0
        for start in range(0, len(rows), batch_size):
            batch = rows[start : start + batch_size]
            payload = "\n".join(
                json.dumps(row, ensure_ascii=False, separators=(",", ":"), default=str)
                for row in batch
            ).encode("utf-8")
            self._post(
                {
                    "query": f"INSERT INTO {table} FORMAT JSONEachRow",
                    "date_time_input_format": "best_effort",
                    "input_format_json_read_numbers_as_strings": "1",
                    "input_format_defaults_for_omitted_fields": "1",
                },
                payload,
                content_type="application/x-ndjson; charset=utf-8",
            )
            inserted += len(batch)
        return inserted

    def _post(
        self,
        params: dict[str, str],
        payload: bytes,
        *,
        content_type: str = "text/plain; charset=utf-8",
    ) -> str:
        endpoint = f"{self.url}/?{urllib.parse.urlencode(params)}"
        request = urllib.request.Request(
            endpoint,
            data=payload,
            method="POST",
            headers={
                "Authorization": "Basic "
                + base64.b64encode(f"{self.user}:{self.password}".encode()).decode(),
                "Content-Type": content_type,
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                return response.read().decode("utf-8").strip()
        except urllib.error.HTTPError as exc:
            details = exc.read().decode("utf-8", errors="replace").strip()
            raise ClickHouseError(f"ClickHouse HTTP {exc.code}: {details}") from exc
        except urllib.error.URLError as exc:
            raise ClickHouseError(f"Cannot connect to ClickHouse at {self.url}: {exc.reason}") from exc

    def execute_script(self, sql: str) -> None:
        """Execute a migration/job script one statement at a time."""
        for statement in split_sql_statements(sql):
            self.execute(statement)

    @staticmethod
    def quote(value: str) -> str:
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"


def split_sql_statements(sql: str) -> list[str]:
    statements: list[str] = []
    buffer: list[str] = []
    quote: str | None = None
    escaped = False
    in_line_comment = False

    for char in sql:
        if in_line_comment:
            buffer.append(char)
            if char == "\n":
                in_line_comment = False
            continue
        if quote:
            buffer.append(char)
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote:
                quote = None
            continue
        if char in ("'", '"', "`"):
            quote = char
            buffer.append(char)
        elif char == "-" and buffer and buffer[-1] == "-":
            in_line_comment = True
            buffer.append(char)
        elif char == ";":
            statement = "".join(buffer).strip()
            if statement:
                statements.append(statement)
            buffer = []
        else:
            buffer.append(char)

    tail = "".join(buffer).strip()
    if tail:
        statements.append(tail)
    return statements
