#!/usr/bin/env python3
"""Run metadata SQL against Firebird 2.5.9 in disposable, offline test databases.

Requires a running developer Docker daemon. Never uses a pharmacy connection,
host ports, host mounts, existing volumes, or real credentials.
"""

from pathlib import Path
import re
import subprocess
import uuid


IMAGE = (
    "jacobalberty/firebird:v2.5.9-sc@"
    "sha256:00b80810d5fc005e296f17967691a7270888655816f116da76de024d17288429"
)
PILOT = Path(__file__).resolve().parent
FIXTURE = """
SET BAIL ON;
SET AUTODDL OFF;
CREATE TABLE WAREBASE (ID INTEGER NOT NULL PRIMARY KEY, QUANTITY NUMERIC(15, 3), DERIVED_QUANTITY COMPUTED BY (QUANTITY * 2));
CREATE TABLE PARTS (ID INTEGER NOT NULL PRIMARY KEY, NAME VARCHAR(64));
CREATE TABLE RESERVATIONS (WARE_ID INTEGER, QUANTITY NUMERIC(15, 3));
CREATE INDEX IX_WAREBASE_QUANTITY ON WAREBASE (QUANTITY);
CREATE VIEW VW_WAREBASE_KASSA AS SELECT ID, QUANTITY FROM WAREBASE;
CREATE VIEW VW_WAREBASE AS SELECT ID, QUANTITY FROM WAREBASE;
COMMIT;
"""
PROBE = """
SET LIST ON;
SET TRANSACTION READ ONLY READ COMMITTED RECORD_VERSION NO WAIT;
SELECT RDB$GET_CONTEXT('SYSTEM', 'ENGINE_VERSION') AS ENGINE_VERSION,
       RDB$GET_CONTEXT('SYSTEM', 'READ_ONLY') AS DIAGNOSTIC_READ_ONLY
FROM RDB$DATABASE;
SELECT 'EPHARM_STANDALONE_METADATA_COMPLETE' AS DIAGNOSTIC_COMPLETE FROM RDB$DATABASE;
ROLLBACK;
QUIT;
"""


def run_sql(sql: str) -> tuple[int, str]:
    name = "epharm-stock-metadata-test-" + uuid.uuid4().hex[:12]
    command = [
        "docker", "run", "--rm", "-i", "--name", name,
        "--platform", "linux/amd64", "--network", "none", "--no-healthcheck",
        "--cpus", "1", "--memory", "256m", "--pids-limit", "128",
        # Synthetic credentials, exclusively for this disposable fixture.
        "-e", "ISC_USER=SYSDBA", "-e", "ISC_PASSWORD=TestOnly",
        "-e", "FIREBIRD_DATABASE=pilot.fdb", IMAGE,
        "/usr/local/firebird/bin/isql", "-bail", "-q", "/firebird/data/pilot.fdb",
    ]
    try:
        result = subprocess.run(
            command, input=sql, text=True, capture_output=True, timeout=45
        )
        return result.returncode, result.stdout + result.stderr
    except subprocess.TimeoutExpired:
        # Stop only the test container we created, even if isql cannot finish.
        subprocess.run(
            ["docker", "rm", "-f", name], capture_output=True, timeout=10
        )
        raise


def require(condition: bool, message: str, output: str) -> None:
    if not condition:
        raise AssertionError(message + "\n" + output)


def main() -> None:
    source = (PILOT / "cashier-stock-diagnostics.ps1").read_text(encoding="utf-8-sig")
    match = re.search(r"function Get-MetadataSql \{\s*return @'\n(.*?)\n'@\s*\}", source, re.S)
    if not match:
        raise AssertionError("Cannot extract the exact diagnostic SQL")
    stock_match = re.search(r"function Get-StockSchemaSql \{\s*return @'\n(.*?)\n'@\s*\}", source, re.S)
    if not stock_match:
        raise AssertionError("Cannot extract the exact targeted schema SQL")

    # Reproduce the reported parser failure: the old alias really is invalid.
    code, output = run_sql("SELECT 1 AS POSITION FROM RDB$DATABASE;\nQUIT;\n")
    require(code != 0 and "-104" in output and "POSITION" in output,
            "Firebird 2.5.9 must reject the former alias", output)
    print("PASS: Firebird 2.5.9 reproduces the former POSITION parser error")

    batches = [
        ("embedded diagnostic", match.group(1), "EPHARM_METADATA_COMPLETE_V4"),
        ("targeted stock schema", stock_match.group(1), "EPHARM_STOCK_SCHEMA_COMPLETE_V7"),
        ("standalone metadata.sql", (PILOT / "metadata.sql").read_text() + PROBE,
         "EPHARM_STANDALONE_METADATA_COMPLETE"),
    ]
    for label, sql, marker in batches:
        code, output = run_sql(FIXTURE + sql)
        require(code == 0 and "SQLSTATE" not in output and "Statement failed" not in output,
                label + " must run without SQL errors", output)
        for expression in (
            r"(?m)^ENGINE_VERSION\s+2\.5\.9\s*$",
            r"(?m)^DIAGNOSTIC_READ_ONLY\s+TRUE\s*$",
            r"(?m)^FIELD_ORDINAL\s+0\s*$",
            r"(?m)^INDEX_FIELD_ORDINAL\s+0\s*$",
            r"(?m)^INDEX_NAME\s+IX_WAREBASE_QUANTITY\s*$",
        ):
            require(re.search(expression, output) is not None,
                    label + " missing evidence: " + expression, output)
        require(marker in output, label + " did not complete", output)
        if label == "targeted stock schema":
            for total_label, row_label in (
                ("TARGET_FIELDS_TOTAL", "FIELD_ORDINAL"),
                ("TARGET_INDEX_SEGMENTS_TOTAL", "INDEX_FIELD_ORDINAL"),
                ("TARGET_CONSTRAINTS_TOTAL", "CONSTRAINT_NAME"),
                ("TARGET_DEPENDENCIES_TOTAL", "DEPENDENT_NAME"),
            ):
                total = re.search(r"(?m)^" + total_label + r"\s+(\d+)\s*$", output)
                require(total is not None, "Missing count: " + total_label, output)
                count = len(re.findall(r"(?m)^" + row_label + r"\s+\S+[^\r\n]*$", output))
                require(count == int(total.group(1)), "Incorrect completeness count: " + total_label, output)
            require("VIEW_SQL" in output and "DEPENDENCY_OBJECT" in output and "CONSTRAINT_TYPE" in output,
                    "Targeted metadata must include view definitions, dependencies and constraints", output)
            require(re.search(r"(?m)^IS_COMPUTED\s+1\s*$", output) is not None,
                    "Targeted metadata must identify computed fields", output)
        print("PASS: " + label + " completes on Firebird 2.5.9; fields, indexes, READ ONLY verified")

    # Firebird 2.5 can retain compiled logic after its source text is removed.
    # These writes affect only the disposable synthetic fixture, never a pharmacy.
    missing_sources = """
UPDATE RDB$RELATIONS SET RDB$VIEW_SOURCE = NULL WHERE RDB$RELATION_NAME = 'VW_WAREBASE';
UPDATE RDB$FIELDS SET RDB$COMPUTED_SOURCE = NULL
WHERE RDB$FIELD_NAME IN (SELECT RDB$FIELD_SOURCE FROM RDB$RELATION_FIELDS
                        WHERE RDB$RELATION_NAME = 'WAREBASE' AND RDB$FIELD_NAME = 'DERIVED_QUANTITY');
COMMIT;
"""
    code, output = run_sql(FIXTURE + missing_sources + stock_match.group(1))
    require(code == 0 and "SQLSTATE" not in output,
            "Missing-source fixture must still be readable", output)
    unavailable = re.findall(r"(?m)^SOURCE_UNAVAILABLE\s+1\s*$", output)
    require(len(unavailable) == 2, "Both unavailable source definitions must be detected", output)
    print("PASS: surviving view/computed BLR with missing source text is explicitly detected")


if __name__ == "__main__":
    main()
