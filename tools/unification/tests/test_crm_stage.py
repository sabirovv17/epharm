import contextlib
import copy
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock


SPEC = importlib.util.spec_from_file_location("crm_stage", Path(__file__).resolve().parents[1] / "crm_stage.py")
stage = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(stage)
SYNTHETIC_SECRET = "SYNTHETIC-HASH-DO-NOT-LOG"


def column(name, kind="text", **extra):
    result = {"name": name, "position": 1, "typeSchema": "pg_catalog", "typeName": kind,
              "typeModifier": -1, "notNull": False, "identity": "", "generated": "", "default": None,
              "collationSchema": "pg_catalog" if kind == "text" else None,
              "collationName": "default" if kind == "text" else None,
              "characterLength": None, "numericPrecision": None, "numericScale": None}
    result.update(extra)
    return result


def constraint(name, kind, columns, **extra):
    result = {"name": name, "kind": kind, "columns": columns, "foreignSchema": None, "foreignTable": None,
              "foreignColumns": [], "deleteSetColumns": [], "updateAction": " ", "deleteAction": " ", "match": " ",
              "deferrable": False, "deferred": False, "validated": True, "nullsNotDistinct": False, "definition": "inert original definition"}
    result.update(extra)
    return result


def fixture():
    accounts = {"name": "accounts", "kind": "r", "columns": [
        column("id", "uuid", notNull=True), column("password_hash"),
        column("fixed", "bpchar", typeModifier=8, characterLength=4),
        column("amount", "numeric", typeModifier=(14 << 16) + 3 + 4, numericPrecision=14, numericScale=3),
        column("role", "user_role", typeSchema="public"), column("dates", "_date"),
        column("ids", "_uuid"), column("ip", "inet"), column("details", "jsonb"),
        column("event_time", "timestamptz"), column("request_number", "int8", identity="d", notNull=True)],
        "constraints": [constraint("accounts_pkey", "p", ["id"]),
                        constraint("accounts_request_unique", "u", ["request_number"]),
                        constraint("positive_amount", "c", ["amount"], definition="CHECK (amount >= 0)")]}
    nodes = {"name": "nodes", "kind": "r", "columns": [column("id", notNull=True), column("account_id", "uuid"), column("parent_id")],
             "constraints": [constraint("nodes_account_fkey", "f", ["account_id"], foreignSchema="public", foreignTable="accounts",
                                        foreignColumns=["id"], updateAction="a", deleteAction="n", match="s"),
                             constraint("nodes_parent_fkey", "f", ["parent_id"], foreignSchema="public", foreignTable="nodes",
                                        foreignColumns=["id"], updateAction="a", deleteAction="r", match="s"),
                             constraint("nodes_pkey", "p", ["id"])]}
    for table in (accounts, nodes):
        for position, item in enumerate(table["columns"], 1):
            item["position"] = position
    metadata = {"database": {"name": "synthetic", "version": "18.6", "versionNum": 180006, "encoding": "UTF8",
                              "collate": "en_US.utf8", "ctype": "en_US.utf8", "localeProvider": "c", "sizeBytes": 1024},
                "tables": [accounts, nodes], "enums": [{"name": "user_role", "labels": ["admin", "staff"]}],
                "sequences": [{"name": "request_number_seq", "start": 1, "increment": 1}],
                "inheritanceCount": 0, "largeObjectCount": 0,
                "triggers": [{"definition": "CREATE TRIGGER forbidden_legacy_trigger"}],
                "routines": [{"definition": "CREATE FUNCTION forbidden_legacy_function()"}],
                "indexes": [], "views": [], "policies": [], "rules": [], "schemas": [], "extensions": []}
    rows = {"accounts": [["11111111-2222-4333-8444-555555555555", SYNTHETIC_SECRET, "x   ", "1234567.120", "admin",
                           '{2026-10-01,NULL}', '{11111111-2222-4333-8444-555555555555}', '192.0.2.1/24',
                           '{"file": "storage/test/path.txt", "n": 1.20}', '2026-10-29 01:02:03.123456+00', '7']],
            "nodes": [["A", "11111111-2222-4333-8444-555555555555", "B"], ["B", None, "A"]]}
    return {"formatVersion": 1, "capturedAt": "2026-10-29T00:00:00Z", "sourceIdentitySha256": "a" * 64,
            "sourceSchemaSha256": stage.schema_hash(metadata), "sourceBackupSha256": "b" * 64, "metadata": metadata,
            "sequenceStates": [{"name": "request_number_seq", "lastValue": 7, "isCalled": True}],
            "tables": rows, "checksums": [stage.table_checksum(t, rows[t["name"]]) for t in metadata["tables"]]}


class FakeDatabase:
    database = "epharm_synthetic_candidate"

    def __init__(self):
        self.schema_exists = False
        self.writes, self.reads = [], []
        self.info = {"identitySha256": "c" * 64, "versionNum": 160014, "sha256Available": True,
                     "collate": "en_US.utf8", "ctype": "en_US.utf8", "localeProvider": "c", "freeBytes": stage.MIN_HEADROOM + 1024 * 1024}

    def inspect(self):
        return self.info

    def readonly(self, sql):
        self.reads.append(sql)
        return ("t" if self.schema_exists else "f") if sql.startswith("SELECT to_regnamespace") else ""

    def run(self, sql):
        self.writes.append(sql)
        self.schema_exists = True
        return ""


class CrmStageTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.artifact = fixture()
        self.path = self.root / "source.json"
        self.report = self.root / "report.json"
        self.db = FakeDatabase()
        self.save()

    def save(self):
        self.path.write_bytes(stage.encoded(self.artifact))
        self.path.chmod(0o600)
        self.sha = stage.file_hash(self.path)

    def load(self, **options):
        args = dict(db=self.db, artifact_path=self.path, expected_sha=self.sha,
                    expected_target="c" * 64, expected_tables=2, min_headroom=stage.MIN_HEADROOM, report=self.report)
        args.update(options)
        return stage.load_artifact(**args)

    def assert_rejected(self, label, **options):
        with self.assertRaisesRegex(stage.StageError, label):
            self.load(**options)
        self.assertEqual([], self.db.writes)

    def test_typed_load_preserves_values_and_verifies_before_commit(self):
        result = self.load()
        self.assertEqual("imported_and_verified", result["mode"])
        sql = self.db.writes[0]
        self.assertIn('"fixed" pg_catalog."bpchar"(4)', sql)
        self.assertIn("x   \t1234567.120", sql)
        self.assertIn("COPY ", sql)
        self.assertIn("\\.\n", sql)
        self.assertLess(sql.index("staging typed row checksum mismatch"), sql.index("COMMIT;"))
        self.assertLess(sql.index("COPY "), sql.index("FOREIGN KEY"))
        self.assertNotIn("\nCREATE TRIGGER", sql)
        self.assertNotIn("\nCREATE FUNCTION", sql)
        self.assertNotIn("\nCREATE SEQUENCE", sql)
        self.assertFalse(result["functionalMergeAccepted"])
        self.assertNotIn(SYNTHETIC_SECRET, self.report.read_text())
        self.assertEqual(self.artifact["checksums"], result["tableChecksums"])

    def test_repeat_is_readonly_noop_and_checks_manifest_and_rows(self):
        self.load()
        result = self.load()
        self.assertEqual("verified_existing_noop", result["mode"])
        self.assertEqual(1, len(self.db.writes))
        verification = self.db.reads[-1]
        self.assertIn("staging manifest mismatch", verification)
        self.assertIn("staging provenance mismatch", verification)
        self.assertIn("staging typed row checksum mismatch", verification)

    def test_real_cli_contract_emits_only_sanitized_summary(self):
        out, err = io.StringIO(), io.StringIO()
        with mock.patch.object(stage, "Database", return_value=self.db), contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = stage.main(["load", "--container", "epharm-stage-test-target", "--user", "synthetic", "--database", self.db.database,
                               "--artifact", str(self.path), "--expected-artifact-sha256", self.sha,
                               "--expected-target-identity-sha256", "c" * 64, "--expected-tables", "2", "--report", str(self.report)])
        self.assertEqual(0, code, err.getvalue())
        self.assertEqual(2, json.loads(out.getvalue())["tables"])
        self.assertNotIn(SYNTHETIC_SECRET, out.getvalue() + err.getvalue())

    def test_original_source_database_is_never_a_target(self):
        self.db.database = "epharm"
        self.assert_rejected("dedicated")
        self.assertEqual([], self.db.reads)

    def test_wrong_target_identity_fails_before_write(self):
        self.assert_rejected("candidate identity differs", expected_target="d" * 64)

    def test_bad_source_hash_fails_before_write(self):
        self.assert_rejected("artifact checksum differs", expected_sha="d" * 64)

    def test_storage_budget_and_minimum_headroom_cannot_be_weakened(self):
        self.db.info["freeBytes"] = stage.MIN_HEADROOM + self.path.stat().st_size * 4 - 1
        self.assert_rejected("storage guard failed")
        self.assert_rejected("below 1 GiB", min_headroom=stage.MIN_HEADROOM - 1)

    def test_wrong_target_major_or_locale_fails(self):
        self.db.info["versionNum"] = 180006
        self.assert_rejected("PostgreSQL16")
        self.db.info["versionNum"] = 160014
        self.db.info["collate"] = "different-locale"
        self.assert_rejected("locales differ")

    def test_artifact_cannot_be_overwritten_by_report(self):
        self.assert_rejected("must not replace", report=self.path)

    def test_missing_dependent_table_and_unknown_type_fail_closed(self):
        self.artifact["metadata"]["tables"][1]["constraints"][0]["foreignTable"] = "missing"
        self.artifact["sourceSchemaSha256"] = stage.schema_hash(self.artifact["metadata"])
        self.save()
        self.assert_rejected("outside the preserved")

    def test_generated_or_unknown_columns_fail_closed(self):
        self.artifact["metadata"]["tables"][0]["columns"][1]["generated"] = "s"
        self.save()
        self.assert_rejected("generated columns")
        self.artifact["metadata"]["tables"][0]["columns"][1].update(generated="", typeName="unreviewed_type")
        self.save()
        self.assert_rejected("unsupported builtin column type")

    def test_lost_row_or_changed_file_reference_fails_source_checksum(self):
        self.artifact["tables"]["nodes"].pop()
        self.save()
        self.assert_rejected("row/primary-key checksums differ")
        self.artifact = fixture()
        self.artifact["tables"]["accounts"][0][8] = '{"file":"storage/other.txt"}'
        self.save()
        self.assert_rejected("row/primary-key checksums differ")

    def test_duplicate_source_primary_key_fails_without_logging_value(self):
        self.artifact["tables"]["nodes"].append(copy.deepcopy(self.artifact["tables"]["nodes"][0]))
        self.save()
        self.assert_rejected("duplicate source primary key")

    def test_private_artifact_permissions_are_required(self):
        self.path.chmod(0o644)
        self.assert_rejected("mode 0600")

    def test_sequence_state_and_full_metadata_are_inert_and_retained(self):
        sql = stage.load_sql(self.artifact, "crm_stage_synthetic", "a" * 64)
        self.assertIn("sourceBackupSha256", sql)
        self.assertIn("sequenceStates", sql)
        self.assertIn("forbidden_legacy_trigger", sql)
        self.assertIn("positive_amount", sql)
        self.assertNotIn(" ADD CONSTRAINT \"positive_amount\"", sql)
        self.assertNotIn("GENERATED BY DEFAULT", sql)
        self.artifact["sequenceStates"].clear()
        self.save()
        self.assert_rejected("sequence inventory differs")

    def test_null_empty_padding_and_unicode_have_distinct_framed_hashes(self):
        self.assertEqual(5, len({stage.digest(stage.row_frame([v])) for v in (None, "", "x", "x   ", "Ә") }))
        self.assertNotEqual(stage.row_frame(["a", "b:c"]), stage.row_frame(["a:b", "c"]))

    def test_copy_text_escapes_terminator_null_and_control_characters(self):
        self.assertEqual("\\N", stage.copy_value(None))
        self.assertEqual("\\\\N", stage.copy_value("\\N"))
        self.assertEqual("\\\\.", stage.copy_value("\\."))
        self.assertEqual("a\\tb\\nc\\rd\\ve\\ff\\bg", stage.copy_value("a\tb\nc\rd\ve\ff\bg"))

    def test_postgres_errors_never_expose_copy_context(self):
        result = subprocess.CompletedProcess([], 1, "", "ERROR:  23505\nCONTEXT: " + SYNTHETIC_SECRET)
        with mock.patch.object(stage.subprocess, "run", return_value=result):
            db = stage.Database("epharm-stage-test-target", "synthetic", "synthetic")
            with self.assertRaises(stage.StageError) as error:
                db.run("synthetic")
        self.assertIn("23505", str(error.exception))
        self.assertNotIn(SYNTHETIC_SECRET, str(error.exception))

    def test_source_metadata_and_snapshot_queries_are_readonly(self):
        db = stage.Database("epharm-stage-test-source", "synthetic", "synthetic")
        with mock.patch.object(db, "run", return_value="") as run:
            db.readonly("SELECT 1;")
        self.assertTrue(run.call_args.args[0].startswith("BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;"))
        query = stage.data_query(self.artifact["metadata"], "public")
        self.assertIn("pg_catalog.format('%s'", query)
        self.assertNotIn("::text FROM", query.split("'values'")[1].split("array_to_json")[0])

    def test_verification_pins_foreign_namespace_and_inert_schema(self):
        signature = stage.expected_signature(self.artifact["metadata"], "crm_stage_synthetic")
        self.assertEqual(0, signature["userTriggers"])
        foreign = signature["tables"][1]["constraints"][0]
        self.assertEqual("crm_stage_synthetic", foreign["foreignSchema"])
        query = stage.signature_query("crm_stage_synthetic")
        self.assertIn("'foreignSchema'", query)
        self.assertIn("'default'", query)


if __name__ == "__main__":
    unittest.main()
