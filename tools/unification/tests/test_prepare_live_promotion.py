import copy
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock

from test_crm_stage import column, constraint, fixture

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
SPEC = importlib.util.spec_from_file_location("prepare_live_promotion", TOOLS / "prepare_live_promotion.py")
promotion = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(promotion)
stage = promotion.stage
PRIVATE = "SYNTHETIC_PRIVATE\\.\n\\set ON_ERROR_STOP off\t'Ә"


def crm_artifact(with_staff_history=False):
    value = fixture()
    value["metadata"]["tables"] = [table("inert_crm_" + str(i).zfill(2)) for i in range(57)]
    if with_staff_history:
        value["metadata"]["tables"][1] = staff_history_table(False)
        value["metadata"]["tables"].sort(key=lambda t: t["name"])
    value["metadata"]["enums"] = []; value["metadata"]["sequences"] = []; value["sequenceStates"] = []
    value["tables"] = {t["name"]: [] for t in value["metadata"]["tables"]}
    value["tables"]["inert_crm_00"] = [["preserved-crm-row", PRIVATE]]
    if with_staff_history:
        value["tables"]["staff_access_attempts"] = [["history-1", "code", "a" * 64, None, None, "127.0.0.1", PRIVATE,
            "f", "not_found", "2026-10-30 00:00:00+00"]]
    value["sourceSchemaSha256"] = stage.schema_hash(value["metadata"])
    value["checksums"] = [stage.table_checksum(t, value["tables"][t["name"]]) for t in value["metadata"]["tables"]]
    stage.validate_artifact(value, 57)
    return value


def table(name):
    return {"name": name, "kind": "r", "rowSecurity": False, "forceRowSecurity": False,
            "columns": [column("id", notNull=True), column("value", position=2)],
            "constraints": [constraint(name + "_pkey", "p", ["id"])]}


def staff_history_table(native):
    fields = [("id", "text"), ("access_type", "text"), ("lookup_hash", "text")]
    fields += [("ip_hash", "text"), ("bcrypt_cost", "int4"), ("code_id", "text")] if native else [
        ("code_id", "text"), ("organization_id", "text"), ("request_ip", "text"), ("user_agent", "text")]
    fields += [("success", "bool"), ("failure_reason", "text"), ("created_at", "timestamptz")]
    if native: fields += [("source_id", "text"), ("source_payload", "jsonb")]
    name = "merch_staff_access_attempts" if native else "staff_access_attempts"
    return dict(table(name), columns=[column(n, kind, position=i, notNull=n == "id") for i, (n, kind) in enumerate(fields, 1)])


def native_history_row(artifact, columns):
    source = dict(zip((c["name"] for c in staff_history_table(False)["columns"]), artifact["tables"]["staff_access_attempts"][0], strict=True))
    payload = dict(source, success=False, created_at="2026-10-30T00:00:00+00:00")
    values = dict(source, source_id=source["id"], source_payload=json.dumps(payload), bcrypt_cost="0",
                  ip_hash=stage.digest(("staff-access:source-ip:" + source["request_ip"]).encode()))
    return [values.get(c["name"]) for c in columns]


def snapshot():
    tables = [table(n) for n in promotion.NATIVE_TABLES]
    tables += [table("pos_sales")] + [table("original_" + str(i)) for i in range(71)] + [table("flyway_schema_history")]
    rows = {name: [] for name in promotion.NATIVE_TABLES}
    rows["unified_persons"] = [["synthetic-person", PRIVATE]]
    return {"metadata": {"tables": tables, "database": {"encoding": "UTF8", "collate": "C", "ctype": "C", "localeProvider": "c"}},
            "rows": rows, "migrations": [["090", "SQL", "V090__synthetic.sql", 123, True]], "sourceIdentitySha256": "a" * 64}


def pin():
    value = dict(database="synthetic_target", oid=123, systemIdentifier="987654321", versionNum=160014,
                 containerId="d" * 64, user="synthetic")
    value["identitySha256"] = stage.digest(stage.encoded({k: value[k] for k in value if k != "user"}))
    return value


class PreparationTest(unittest.TestCase):
    def setUp(self):
        sha = stage.schema_hash(snapshot()["metadata"])
        self.synthetic_profile = dict(schemaVersion="090", plainSchemaSha256=sha)
        self.profile_patch = mock.patch.object(promotion, "schema_profile", return_value=self.synthetic_profile)
        self.profile_patch.start(); self.addCleanup(self.profile_patch.stop)

    def test_only_exact_plain090_fingerprint_is_accepted_without_legacy_check_substitution(self):
        source = snapshot()["metadata"]
        self.assertIs(source, promotion.expected_target_metadata(source))
        changed = copy.deepcopy(source)
        changed["tables"][0]["constraints"].append(constraint("new_check", "c", ["value"], definition="CHECK (value IS NOT NULL)"))
        with self.assertRaises(stage.StageError): promotion.expected_target_metadata(changed)
        for legacy in ("088", "089"):
            profile = dict(self.synthetic_profile, schemaVersion=legacy)
            with mock.patch.object(promotion, "schema_profile", return_value=profile), self.assertRaises(stage.StageError):
                promotion.expected_target_metadata(source)
        legacy_shape = dict(self.synthetic_profile, sourceSchemaSha256=self.synthetic_profile["plainSchemaSha256"], substitutions=[])
        with mock.patch.object(promotion, "schema_profile", return_value=legacy_shape), self.assertRaises(stage.StageError):
            promotion.expected_target_metadata(source)

    def test_only_allowlisted_native_tables_are_written_and_no_source_routines_replayed(self):
        value = snapshot(); value["migrations"][0][0] = "090"
        sql, checksums = promotion.build_sql(value, crm_artifact(), "b" * 64, pin())
        copied = re.findall(r'^COPY public\."([a-z_]+)"', sql, re.M)
        self.assertEqual(set(promotion.NATIVE_TABLES), set(copied))
        self.assertEqual(76, len(copied)); self.assertEqual(76, len(checksums))
        self.assertNotRegex(sql, r"(?m)^(UPDATE|DELETE|TRUNCATE|DROP|ALTER TABLE public|CREATE FUNCTION|CREATE TRIGGER) ")
        self.assertNotIn("session_replication_role", sql)
        self.assertIn("original72 changed inside promotion", sql)
        self.assertIn("target090 Flyway checksums differ", sql)
        self.assertIn("refusing replay without changes", sql)
        self.assertNotIn("\\if", sql)
        self.assertIn("\\\\.\\n\\\\set ON_ERROR_STOP off\\t'Ә", sql)
        self.assertEqual(1, sql.count("BEGIN ISOLATION LEVEL REPEATABLE READ;"))
        self.assertTrue(sql.endswith("COMMIT;\n"))

    def test_fk_order_and_self_reference_are_supported_but_cross_table_cycles_refused(self):
        a, b = table("a"), table("b")
        a["constraints"].append(constraint("fk", "f", ["id"], foreignSchema="public", foreignTable="b"))
        self.assertEqual(["b", "a"], promotion.table_order([a, b]))
        b["constraints"].append(constraint("self", "f", ["id"], foreignSchema="public", foreignTable="b"))
        self.assertEqual(["b", "a"], promotion.table_order([a, b]))
        b["constraints"].append(constraint("cycle", "f", ["id"], foreignSchema="public", foreignTable="a"))
        with self.assertRaises(stage.StageError): promotion.table_order([a, b])

    def test_unknown_tables_old_schema_ephemeral_rows_and_duplicate_keys_fail_closed(self):
        for change in ("unknown", "old_version", "session", "staff_session", "staff_visit", "native_staff_attempt", "duplicate", "generated"):
            value = snapshot(); value["migrations"][0][0] = "090"
            if change == "unknown": value["metadata"]["tables"].append(table("unexpected"))
            if change == "old_version": value["migrations"][0][0] = "088"
            if change == "session": value["rows"]["merch_producer_sessions"] = [["not-transferable", PRIVATE]]
            if change == "staff_session": value["rows"]["merch_staff_access_sessions"] = [["not-transferable", PRIVATE]]
            if change == "staff_visit": value["rows"]["merch_staff_visits"] = [["not-transferable", PRIVATE]]
            if change == "native_staff_attempt": value["rows"]["merch_staff_access_attempts"] = [["not-transferable", PRIVATE]]
            if change == "duplicate": value["rows"]["unified_persons"] *= 2
            if change == "generated": value["metadata"]["tables"][0]["columns"][0]["identity"] = "d"
            with self.subTest(change=change), self.assertRaises(stage.StageError): promotion.validate_snapshot(value)

    def test_target_pins_must_be_complete_and_self_consistent(self):
        for key, bad in [("oid", True), ("versionNum", 180006), ("systemIdentifier", "x'"),
                         ("containerId", "latest"), ("identitySha256", "0" * 64), ("database", "db; DROP")]:
            value = pin(); value[key] = bad
            with self.subTest(key=key), self.assertRaises(stage.StageError): promotion.validate_pin(value)
        value = pin(); value["host"] = "live"
        with self.assertRaises(stage.StageError): promotion.validate_pin(value)

    def test_staff_authentication_history_requires_exact_source_ids_and_fields(self):
        artifact = crm_artifact(True); value = snapshot(); native = staff_history_table(True)
        value["metadata"]["tables"] = [native if t["name"] == native["name"] else t for t in value["metadata"]["tables"]]
        row = native_history_row(artifact, native["columns"])
        value["rows"][native["name"]] = [row]
        promotion.validate_snapshot(value)
        proof = promotion.staff_history_proof(value, artifact, "b" * 64)
        self.assertIn("a.source_payload IS DISTINCT FROM to_jsonb(s)", proof)
        fields = [c["name"] for c in native["columns"]]
        for key, invalid in [("source_id", None), ("source_id", "another-id"), ("lookup_hash", "b" * 64),
                             ("bcrypt_cost", "1"), ("ip_hash", "0" * 64), ("source_payload", "{}")]:
            changed = copy.deepcopy(value); changed["rows"][native["name"]][0][fields.index(key)] = invalid
            with self.subTest(key=key), self.assertRaises(stage.StageError):
                promotion.validate_snapshot(changed); promotion.staff_history_proof(changed, artifact, "b" * 64)
        changed = copy.deepcopy(value); changed["rows"][native["name"]] = []
        with self.assertRaises(stage.StageError): promotion.staff_history_proof(changed, artifact, "b" * 64)
        with self.assertRaises(stage.StageError): promotion.staff_history_proof(value, crm_artifact(), "b" * 64)

    def test_preparation_never_runs_generated_sql_and_outputs_are_private_and_not_replaced(self):
        value = snapshot(); value["migrations"][0][0] = "090"
        class ReadOnlySource:
            def __init__(self): self.reads = 0
            def inspect(self): return dict(identitySha256="a" * 64, versionNum=160014)
            def run(self, _): raise AssertionError("generator attempted a database write")
            def readonly(self, _):
                self.reads += 1
                if self.reads == 1: return json.dumps(value["metadata"])
                return "\n".join([json.dumps(value["metadata"]), json.dumps(dict(kind="migrations", rows=value["migrations"]))]
                                 + [json.dumps(dict(kind="row", table=n, values=row)) for n, rows in value["rows"].items() for row in rows])
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve(); root.chmod(0o700)
            inputs = {"proposal": {"newPublicTableAllowlist": list(promotion.BASE_NATIVE_TABLES)}, "crm": crm_artifact(), "pin": pin()}
            for name, body in inputs.items(): stage.private_write(root / name, stage.encoded(body))
            args = promotion.parser().parse_args(["--proposal", str(root / "proposal"), "--proposal-sha256", stage.file_hash(root / "proposal"),
                    "--crm-artifact", str(root / "crm"), "--crm-sha256", stage.file_hash(root / "crm"),
                    "--target-pin", str(root / "pin"), "--target-pin-sha256", stage.file_hash(root / "pin"),
                    "--source-container", "epharm-unify-candidate-synthetic", "--source-user", "synthetic", "--source-database", "epharm_unified_candidate",
                    "--source-identity-sha256", "a" * 64, "--output", str(root / "package")])
            result = promotion.prepare(args, database_factory=lambda *_: ReadOnlySource())
            self.assertEqual("prepared_not_applied", result["state"]); self.assertEqual(0, result["targetConnections"])
            self.assertEqual(stage.file_hash(args.output / "SHA256SUMS"), result["packageSha256"])
            self.assertNotIn(PRIVATE, json.dumps(result))
            self.assertEqual(0o700, args.output.stat().st_mode & 0o777)
            for path in args.output.iterdir(): self.assertEqual(0o600, path.stat().st_mode & 0o777)
            before = {p.name: p.read_bytes() for p in args.output.iterdir()}
            with self.assertRaises(stage.StageError): promotion.prepare(args, database_factory=lambda *_: ReadOnlySource())
            self.assertEqual(before, {p.name: p.read_bytes() for p in args.output.iterdir()})
            os.chmod(root / "pin", 0o644)
            with self.assertRaises(stage.StageError): promotion.private_input(root / "pin", stage.file_hash(root / "pin"))
            link = root / "linked"; link.symlink_to(root / "proposal")
            with self.assertRaises(OSError): promotion.private_input(link, stage.file_hash(root / "proposal"))


@unittest.skipUnless(os.environ.get("RUN_LIVE_PROMOTION_PG_TESTS") == "1", "opt-in disposable PostgreSQL proof")
class PromotionPostgresTest(unittest.TestCase):
    def test_native_only_atomic_import_replay_and_wrong_target_or_partial_state_refusal(self):
        suffix = str(time.time_ns()); names = ["epharm-unify-candidate-promotion-source-" + suffix, "epharm-stage-test-promotion-target-" + suffix]
        created = []
        try:
            for name in names:
                subprocess.run(["docker", "run", "-d", "--rm", "--pull=never", "--network=none", "--name", name,
                                "-e", "POSTGRES_DB=epharm_unified_candidate", "-e", "POSTGRES_USER=synthetic",
                                "-e", "POSTGRES_PASSWORD=synthetic-only", "postgres:16-alpine"], check=True, capture_output=True)
                created.append(name)
            for name in names:
                for _ in range(100):
                    if subprocess.run(["docker", "exec", name, "pg_isready", "-h", "127.0.0.1"], capture_output=True).returncode == 0: break
                    time.sleep(0.2)
                else: self.fail("disposable PG not ready")
            source, target = [stage.Database(n, "synthetic", "epharm_unified_candidate") for n in names]
            originals = ["pos_sales"] + ["original_" + str(i) for i in range(71)]
            ddl = "\n".join("CREATE TABLE public." + stage.qi(n) + "(id text PRIMARY KEY,value text);"
                            for n in [*originals, *promotion.NATIVE_TABLES] if n != "merch_staff_access_attempts")
            native_history = staff_history_table(True)
            ddl += "CREATE TABLE public.merch_staff_access_attempts(" + ",".join(
                stage.qi(c["name"]) + " " + c["typeName"] + (" PRIMARY KEY" if c["name"] == "id" else "")
                for c in native_history["columns"]) + ");"
            ddl += "CREATE TABLE public.flyway_schema_history(installed_rank int PRIMARY KEY,version text,type text,script text,checksum int,success boolean);INSERT INTO public.flyway_schema_history VALUES(1,'090','SQL','V090__synthetic.sql',123,true);"
            ddl += "ALTER TABLE public.merch_branches ADD parent_sale text REFERENCES public.pos_sales(id);"
            ddl += "INSERT INTO public.pos_sales VALUES('captured','captured-value');"
            for db in (source, target): db.run(ddl)
            source.run("INSERT INTO public.unified_persons VALUES('synthetic-person'," + stage.literal(PRIVATE) + ");INSERT INTO public.merch_branches VALUES('branch','source','captured');")
            artifact = crm_artifact(True); artifact_sha = stage.digest(stage.encoded(artifact)); schema = "crm_stage_" + artifact_sha[:16]
            try:
                source.run(stage.load_sql(artifact, schema, artifact_sha))
            except stage.StageError:
                # Synthetic fixture diagnostics stay private; never print COPY bodies or credentials.
                diagnostic = subprocess.run(["docker", "logs", names[0]], capture_output=True)
                path = Path(tempfile.gettempdir()) / ("epharm-promotion090-fixture-" + suffix + ".log")
                stage.private_write(path, diagnostic.stdout + diagnostic.stderr)
                raise
            source.run(promotion.copy_table(native_history, [native_history_row(artifact, native_history["columns"])]))
            source.run("INSERT INTO public.merch_staff_access_attempts(id) VALUES('native-ephemeral');")
            source_info = source.inspect()
            with self.assertRaises(stage.StageError): promotion.capture(source, source_info["identitySha256"], artifact, artifact_sha)
            source.run("DELETE FROM public.merch_staff_access_attempts WHERE id='native-ephemeral';")
            data = promotion.capture(source, source_info["identitySha256"], artifact, artifact_sha)
            sha = stage.schema_hash(data["metadata"])
            profile_patch = mock.patch.object(promotion, "schema_profile", return_value=dict(schemaVersion="090", plainSchemaSha256=sha))
            profile_patch.start(); self.addCleanup(profile_patch.stop)
            info = target.inspect()
            target_pin = {k: info[k] for k in ("database", "oid", "systemIdentifier", "versionNum", "identitySha256")}
            target_pin.update(user="synthetic", containerId=subprocess.check_output(["docker", "inspect", names[1], "--format", "{{.Id}}"], text=True).strip())
            # An original sale arrives AFTER source capture. The package must not replace it.
            target.run("INSERT INTO public.pos_sales VALUES('post-capture-sale','must-survive');")
            metadata = stage.parse_json(target.readonly(stage.metadata_sql()))
            before = [stage.parse_json(line) for line in target.readonly("\n".join("SELECT row_to_json(f) FROM (" + promotion.facts_query(t) + ") f;" for t in metadata["tables"] if t["name"] in originals)).splitlines()]
            sql, _ = promotion.build_sql(data, artifact, artifact_sha, target_pin)
            target.run("DELETE FROM public.pos_sales WHERE id='captured';")
            with self.assertRaises(stage.StageError): target.run(sql)
            self.assertEqual("f", target.readonly("SELECT to_regnamespace(" + stage.literal(schema) + ") IS NOT NULL;").strip())
            self.assertEqual("0", target.readonly("SELECT count(*) FROM public.merch_branches;").strip())
            self.assertEqual("must-survive", target.readonly("SELECT value FROM public.pos_sales WHERE id='post-capture-sale';").strip())
            target.run("INSERT INTO public.pos_sales VALUES('captured','captured-value');")
            wrong_history = copy.deepcopy(data)
            columns = [c["name"] for t in wrong_history["metadata"]["tables"] if t["name"] == "merch_staff_access_attempts" for c in t["columns"]]
            row = wrong_history["rows"]["merch_staff_access_attempts"][0]
            payload = json.loads(row[columns.index("source_payload")]); payload["created_at"] = "2026-10-29T00:00:00+00:00"
            row[columns.index("source_payload")] = json.dumps(payload)
            with self.assertRaises(stage.StageError): target.run(promotion.build_sql(wrong_history, artifact, artifact_sha, target_pin)[0])
            self.assertEqual("0", target.readonly("SELECT count(*) FROM public.merch_staff_access_attempts;").strip())
            self.assertEqual("f", target.readonly("SELECT to_regnamespace(" + stage.literal(schema) + ") IS NOT NULL;").strip())
            result = target.run(sql); self.assertIn('"mode" : "imported_and_verified"', result)
            self.assertEqual("history-1", target.readonly("SELECT source_id FROM public.merch_staff_access_attempts;").strip())
            with self.assertRaises(stage.StageError): target.run(sql)
            after = [stage.parse_json(line) for line in target.readonly("\n".join("SELECT row_to_json(f) FROM (" + promotion.facts_query(t) + ") f;" for t in metadata["tables"] if t["name"] in originals)).splitlines()]
            self.assertEqual(before, after)
            self.assertEqual("must-survive", target.readonly("SELECT value FROM public.pos_sales WHERE id='post-capture-sale';").strip())
            self.assertEqual("0", target.readonly("SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname=" + stage.literal(schema) + ";").strip())
            changed = copy.deepcopy(target_pin); changed["oid"] = str(int(changed["oid"]) + 1)
            changed["identitySha256"] = stage.digest(stage.encoded({k: changed[k] for k in ("database", "oid", "systemIdentifier", "versionNum", "containerId")}))
            with self.assertRaises(stage.StageError): target.run(promotion.build_sql(data, artifact, artifact_sha, changed)[0])
            target.run("UPDATE public.unified_persons SET value='divergent';")
            with self.assertRaises(stage.StageError): target.run(sql)
            self.assertEqual("divergent", target.readonly("SELECT value FROM public.unified_persons;").strip())
            self.assertEqual("2", target.readonly("SELECT count(*) FROM public.pos_sales;").strip())
        finally:
            for name in created: subprocess.run(["docker", "rm", "-fv", name], capture_output=True)


if __name__ == "__main__": unittest.main()
