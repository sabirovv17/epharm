import contextlib
import copy
import importlib.util
import io
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


TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
SPEC = importlib.util.spec_from_file_location("bootstrap_hq", TOOLS / "bootstrap_hq.py")
bootstrap = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(bootstrap)
stage = bootstrap.stage
ACCOUNT = "11111111-1111-4111-8111-111111111111"
PERSON = "22222222-2222-4222-8222-222222222222"
PRIVATE = "SYNTHETIC-PRIVATE-PROOF-$bootstrap$-'"


def grant(module="management", subsection="accounts", action="view", **scope):
    return dict(module=module, subsection=subsection, action=action, allPharmacies=True, **scope)


def route(module="management", subsection="accounts", action="view", kind="SENSITIVE_DATA", mode="ALL_ONLY"):
    return {"api": "GET /synthetic/" + module + "/" + subsection + "/" + action,
            "permission": dict(module=module, subsection=subsection, action=action, kind=kind), "mode": mode}


def payload():
    return dict(schemaVersion=1, accountId=ACCOUNT, personId=PERSON, displayName="Synthetic verified HQ",
                verificationReference=PRIVATE, jobTitle="Explicit synthetic title", grants=[grant()])


def facts(created=False):
    return dict(accountCount=1, accountEligible=True, adminRowSha256="b" * 64,
                profileCount=int(created), personCount=int(created), exactProfile=created, exactPerson=created,
                otherPersonBindings=0, identityAuditCount=int(created), accessAuditCount=int(created))


class FakeDatabase:
    def __init__(self):
        self.reads, self.writes = [], []
        self.info = dict(identitySha256="a" * 64, database=bootstrap.DATABASE, oid=123,
                         systemIdentifier="987654321", versionNum=160014, sha256Available=True)
        self.current = facts()

    def inspect(self):
        return self.info

    def readonly(self, sql):
        self.reads.append(sql)
        return json.dumps(self.current)

    def run(self, sql):
        self.writes.append(sql)
        self.current = facts(True)
        return json.dumps(dict(created=True, facts=self.current))


class BootstrapHqTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.root.chmod(0o700)
        self.input = self.root / "identity.json"
        self.routes = self.root / "routes.json"
        self.input.write_bytes(stage.encoded(payload()))
        self.input.chmod(0o600)
        self.routes.write_bytes(stage.encoded([route()]))
        self.db = FakeDatabase()

    def args(self, *extra):
        return bootstrap.parser().parse_args([
            "--container", "epharm-stage-test-bootstrap-synthetic", "--user", "synthetic",
            "--input", str(self.input), "--routes", str(self.routes),
            "--plan-sha256", stage.file_hash(self.input), "--routes-sha256", stage.file_hash(self.routes),
            "--target-sha256", self.db.info["identitySha256"], *extra])

    def execute(self, *extra):
        return bootstrap.run(self.args(*extra), database_factory=lambda *_: self.db)

    def validate(self, value, routes=None):
        return bootstrap.validate_payload(value, bootstrap.permission_catalog(routes or [route()]))

    def test_default_plan_is_readonly_and_metadata_only(self):
        result = self.execute()
        self.assertEqual("planned_create", result["mode"])
        self.assertEqual((0, 0, 0), (result["personsAdded"], result["profilesAdded"], result["auditsAdded"]))
        self.assertEqual([], self.db.writes)
        self.assertEqual(1, len(self.db.reads))
        for private in (ACCOUNT, PERSON, PRIVATE, "Synthetic verified HQ", "Explicit synthetic title"):
            self.assertNotIn(private, json.dumps(result))

    def test_exact_repeat_requires_both_bootstrap_markers_and_no_other_person_binding(self):
        self.db.current = facts(True)
        self.assertEqual("planned_noop", self.execute()["mode"])
        for field, value in [("identityAuditCount", 0), ("accessAuditCount", 2), ("exactPerson", False),
                             ("profileCount", 0), ("otherPersonBindings", 1), ("accountEligible", False)]:
            with self.subTest(field=field):
                changed = facts(True); changed[field] = value
                with self.assertRaises(stage.StageError):
                    bootstrap.disposition(changed)
        for field in ("profileCount", "personCount", "identityAuditCount", "accessAuditCount"):
            changed = facts(); changed[field] = 1
            with self.assertRaises(stage.StageError):
                bootstrap.disposition(changed)

    def test_payload_exact_schema_ids_and_text_guards(self):
        for field, value in [("schemaVersion", True), ("accountId", ACCOUNT.upper().replace("11111111", "ABCDEFAB", 1)),
                             ("personId", "not-a-uuid"), ("displayName", " "), ("verificationReference", "proof\n"),
                             ("jobTitle", "x\x00y"), ("grants", []), ("grants", [grant()] * 201)]:
            with self.subTest(field=field, value=repr(value)[:40]):
                changed = payload(); changed[field] = value
                with self.assertRaises(stage.StageError):
                    self.validate(changed)
        changed = payload(); changed["email"] = "DO-NOT-GUESS"
        with self.assertRaises(stage.StageError): self.validate(changed)
        changed = payload(); del changed["verificationReference"]
        with self.assertRaises(stage.StageError): self.validate(changed)
        with self.assertRaisesRegex(stage.StageError, "duplicate JSON"):
            stage.parse_json('{"schemaVersion":1,"schemaVersion":1}')

    def test_typed_all_and_no_identity_lists_or_mixed_scopes(self):
        changed = payload()
        for subsection in ("tasks", "placements", "producers"):
            entry = dict(module="merchandising", subsection=subsection, action="view", allMerchBranches=True,
                         allProducers=subsection != "tasks")
            changed["grants"] = [entry]
            self.assertEqual([entry | dict(allPharmacies=False, pharmacyIds=[], merchBranchIds=[], producerIds=[])],
                             self.validate(changed, [route("merchandising", subsection, "view", "ORDINARY")])["grants"])
        for extra in (dict(pharmacyIds=["opaque"]), dict(allMerchBranches=True), dict(allProducers=True),
                      dict(allPharmacies=False), dict(allPharmacies=1), dict(producerIds="")):
            changed["grants"] = [grant() | extra]
            with self.subTest(extra=extra), self.assertRaises(stage.StageError): self.validate(changed)
        changed["grants"] = [grant(), grant()]
        with self.assertRaisesRegex(stage.StageError, "repeated"): self.validate(changed)

    def test_permission_catalog_fail_closed_and_explicit_prerequisites(self):
        for definition in (route(action="not_ready", mode="NOT_READY"), route(action="credentials", kind="CREDENTIAL_EXPORT")):
            changed = payload(); changed["grants"] = [grant(action=definition["permission"]["action"])]
            with self.assertRaises(stage.StageError): self.validate(changed, [definition])
        changed = payload(); changed["grants"] = [grant(action="unknown")]
        with self.assertRaises(stage.StageError): self.validate(changed)
        for key, kind in [("export", "EXPORT"), ("manage", "ACCESS_ADMINISTRATION")]:
            changed["grants"] = [grant(action=key)]
            with self.assertRaisesRegex(stage.StageError, "prerequisite"):
                self.validate(changed, [route(), route(action=key, kind=kind)])
            changed["grants"].append(grant())
            self.assertEqual(2, len(self.validate(changed, [route(), route(action=key, kind=kind)])["grants"]))
        for definitions in ([route(), route()], [route(kind="UNKNOWN")], [route(kind="FINANCIAL")]):
            with self.assertRaises(stage.StageError): bootstrap.permission_catalog(definitions)
        self.assertEqual(12, len(bootstrap.MANUAL))

    def test_credential_bootstrap_requires_exact_opt_in_explicit_grant_and_registry_view(self):
        definitions = [route(), route("pharmacists", "registry", "view", "ORDINARY")]
        allowed = bootstrap.permission_catalog(definitions, include_credential_export=True)
        approved = grant("pharmacists", "registry", "export_credentials")
        view = grant("pharmacists", "registry", "view")
        changed = payload(); changed["grants"] = [approved, view]
        with self.assertRaises(stage.StageError):
            bootstrap.validate_payload(changed, allowed)
        changed["grants"] = [approved]
        with self.assertRaisesRegex(stage.StageError, "prerequisite"):
            bootstrap.validate_payload(changed, allowed, include_credential_export=True)
        changed["grants"] = [approved, view]
        result = bootstrap.validate_payload(changed, allowed, include_credential_export=True)
        self.assertEqual(2, len(result["grants"]))
        unchanged = payload()
        result = bootstrap.validate_payload(unchanged, allowed, include_credential_export=True)
        self.assertFalse(any(bootstrap.permission_key(g) == bootstrap.CREDENTIAL_EXPORT for g in result["grants"]))
        unexpected = ("pharmacists", "registry", "another_credentials")
        allowed[unexpected] = "CREDENTIAL_EXPORT"
        changed["grants"] = [grant(*unexpected), view]
        with self.assertRaises(stage.StageError):
            bootstrap.validate_payload(changed, allowed, include_credential_export=True)

    def test_all_200_explicit_grants_are_bounded_without_implicit_additions(self):
        definitions = [route("training", "courses", "action_" + str(i), "ORDINARY") for i in range(200)]
        changed = payload(); changed["grants"] = [grant("training", "courses", "action_" + str(i)) for i in range(200)]
        self.assertEqual(200, len(self.validate(changed, definitions)["grants"]))

    def test_private_input_and_immutable_report_inode_guards(self):
        self.input.chmod(0o644)
        with self.assertRaises(stage.StageError): self.execute()
        self.input.chmod(0o600)
        linked = self.root / "linked.json"; linked.symlink_to(self.input)
        with self.assertRaises(OSError): bootstrap.read_file(linked, private=True)
        report = self.root / "report.json"
        self.execute("--report", str(report))
        self.assertEqual(0o600, report.stat().st_mode & 0o777)
        with self.assertRaises(FileExistsError): self.execute("--report", str(report))
        self.root.chmod(0o755)
        with self.assertRaises(stage.StageError): self.execute("--report", str(self.root / "new.json"))

    def test_wrong_pins_source_names_major_and_apply_report_refuse(self):
        for argument, value in [("--container", "epharm-unify-pg-20261029"), ("--database", "epharm"),
                                ("--plan-sha256", "c" * 64), ("--routes-sha256", "c" * 64),
                                ("--target-sha256", "c" * 64)]:
            with self.subTest(argument=argument), self.assertRaises(stage.StageError): self.execute(argument, value)
        with self.assertRaises(stage.StageError): self.execute("--apply")
        self.db.info["versionNum"] = 180006
        with self.assertRaises(stage.StageError): self.execute()
        self.assertEqual([], self.db.writes)

    def test_apply_sql_is_one_transaction_first_authority_account_profile_no_admin_updates(self):
        normalized = self.validate(payload())
        sql = bootstrap.apply_sql(normalized, self.db.info)
        self.assertTrue(sql.startswith("BEGIN;")); self.assertTrue(sql.endswith("COMMIT;"))
        self.assertLess(sql.index("pg_advisory_xact_lock(817109,1)"), sql.index("FOR NO KEY UPDATE OF a"))
        self.assertLess(sql.index("FOR NO KEY UPDATE OF a"), sql.index("FOR UPDATE OF p"))
        for forbidden in ("UPDATE public.admin_users", "DELETE ", "TRUNCATE ", "ALTER ", "primary-owner"):
            self.assertNotIn(forbidden, sql)
        self.assertLess(sql.index(PRIVATE.replace("'", "''")), sql.index("DO $bootstrap$"))
        self.assertIn("to_jsonb(a)::text", sql)
        result = self.execute("--apply", "--report", str(self.root / "apply.json"))
        self.assertEqual("created_and_verified", result["mode"])
        self.assertEqual((1, 1, 2), (result["personsAdded"], result["profilesAdded"], result["auditsAdded"]))
        self.assertEqual(1, len(self.db.writes))

    def test_cli_error_has_no_private_content_or_traceback(self):
        self.input.write_text('{"private":"' + PRIVATE.replace('"', '') + '"}')
        output, error = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(error):
            code = bootstrap.main(["--container", "bad", "--user", "synthetic", "--input", str(self.input),
                "--routes", str(self.routes), "--plan-sha256", "a" * 64, "--routes-sha256", "b" * 64, "--target-sha256", "c" * 64])
        self.assertEqual(1, code); self.assertEqual("", output.getvalue())
        self.assertNotIn(PRIVATE, error.getvalue()); self.assertNotIn("Traceback", error.getvalue())


@unittest.skipUnless(os.environ.get("RUN_BOOTSTRAP_HQ_PG_TESTS") == "1", "opt-in disposable PostgreSQL16 fixture")
class BootstrapHqPostgresTest(unittest.TestCase):
    """Only a newly created, ID-owned, synthetic network-none container is ever mutated."""

    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temp.name).resolve(); cls.root.chmod(0o700)
        cls.container_id = None
        cls.addClassCleanup(cls.cleanup)
        image = subprocess.run(["docker", "image", "inspect", "postgres:16", "--format", "{{.Id}}"], capture_output=True, text=True, check=True).stdout.strip()
        if not re.fullmatch("sha256:[a-f0-9]{64}", image): raise AssertionError("cached PG16 image must be pinned")
        cls.name = "epharm-stage-test-bootstrap-" + str(time.time_ns())
        env = cls.root / "postgres.env"
        env.write_text("POSTGRES_USER=synthetic\nPOSTGRES_DB=" + bootstrap.DATABASE + "\nPOSTGRES_PASSWORD=synthetic-disposable-only\n")
        env.chmod(0o600)
        created = subprocess.run(["docker", "run", "--pull=never", "-d", "--network=none", "--name", cls.name,
                                  "--env-file", str(env), image], capture_output=True, text=True, check=True)
        cls.container_id = created.stdout.strip()
        for _ in range(100):
            logs = subprocess.run(["docker", "logs", cls.container_id], capture_output=True, text=True, check=True)
            if (logs.stdout + logs.stderr).count("database system is ready to accept connections") >= 2: break
            time.sleep(0.1)
        else: raise AssertionError("synthetic PG16 did not become ready")
        cls.db = stage.Database(cls.name, "synthetic", bootstrap.DATABASE)
        cls.target = cls.db.inspect()
        cls.db.run("""CREATE TABLE public.admin_users(id uuid PRIMARY KEY,role text NOT NULL,status text NOT NULL,
          access_mode text NOT NULL DEFAULT 'LEGACY', password_hash text NOT NULL, original_metadata jsonb, updated_at timestamptz DEFAULT now());
          CREATE TABLE public.unified_persons(id uuid PRIMARY KEY,verification_reference text NOT NULL,display_name varchar(255));
          CREATE TABLE public.unified_admin_profiles(account_id uuid PRIMARY KEY REFERENCES public.admin_users,
            person_id uuid NOT NULL REFERENCES public.unified_persons,job_title varchar(255) NOT NULL DEFAULT '',version bigint NOT NULL DEFAULT 0,
            grants_json text NOT NULL DEFAULT '[]', updated_at timestamptz NOT NULL DEFAULT now());
          CREATE TABLE public.unified_admin_access_audit(id uuid PRIMARY KEY,actor_id uuid NOT NULL,target_id uuid NOT NULL,
            operation varchar(32) NOT NULL,target_version bigint NOT NULL,created_at timestamptz NOT NULL DEFAULT now());
          CREATE TABLE public.unified_identity_audit(id uuid PRIMARY KEY,actor_id uuid NOT NULL REFERENCES public.admin_users,
            entity_type varchar(64) NOT NULL,entity_id text NOT NULL,operation varchar(64) NOT NULL,created_at timestamptz NOT NULL DEFAULT now());
          CREATE TABLE public.pharmacist_person_links(pharmacist_id text PRIMARY KEY,person_id uuid NOT NULL REFERENCES public.unified_persons);
          CREATE TABLE public.unified_external_account_bindings(source_account_id uuid PRIMARY KEY,person_id uuid NOT NULL REFERENCES public.unified_persons);
        """)

    @classmethod
    def cleanup(cls):
        if cls.container_id:
            # The immutable ID was returned by this test's own successful docker run, never a supplied candidate name.
            subprocess.run(["docker", "rm", "-f", "-v", cls.container_id], capture_output=True, check=True)
        cls.temp.cleanup()

    def setUp(self):
        self.db.run("TRUNCATE public.unified_admin_access_audit,public.unified_identity_audit,public.unified_admin_profiles,public.pharmacist_person_links,public.unified_external_account_bindings,public.unified_persons,public.admin_users;")
        self.db.run("INSERT INTO public.admin_users(id,role,status,password_hash,original_metadata) VALUES('" + ACCOUNT + "','HQ_HEAD','active','SYNTHETIC-HASH-NOT-A-REAL-CREDENTIAL','{\"allOriginalFields\":true}');")
        self.input = self.root / "input.json"; self.input.write_bytes(stage.encoded(payload())); self.input.chmod(0o600)
        self.routes = self.root / "routes.json"; self.routes.write_bytes(stage.encoded([route()]))

    def cli(self, apply=False, changes=None):
        if changes:
            value = payload(); value.update(changes); self.input.write_bytes(stage.encoded(value))
        report = self.root / ("report-" + str(time.time_ns()) + ".json")
        command = [sys.executable, str(TOOLS / "bootstrap_hq.py"), "--container", self.name, "--user", "synthetic",
                   "--input", str(self.input), "--routes", str(self.routes), "--plan-sha256", stage.file_hash(self.input),
                   "--routes-sha256", stage.file_hash(self.routes), "--target-sha256", self.target["identitySha256"],
                   "--report", str(report)] + (["--apply"] if apply else [])
        result = subprocess.run(command, capture_output=True, text=True)
        for secret in (PRIVATE, ACCOUNT, PERSON, "SYNTHETIC-HASH", "Explicit synthetic title", "Synthetic verified HQ"):
            self.assertNotIn(secret, result.stdout + result.stderr + (report.read_text() if report.exists() else ""))
        self.assertNotIn("Traceback", result.stderr)
        self.assertEqual(0o600, report.stat().st_mode & 0o777)
        return result

    def counts(self):
        return stage.parse_json(self.db.readonly("SELECT json_build_array((SELECT count(*) FROM public.unified_persons),(SELECT count(*) FROM public.unified_admin_profiles),(SELECT count(*) FROM public.unified_identity_audit),(SELECT count(*) FROM public.unified_admin_access_audit));"))

    def test_plan_no_writes_apply_noop_whole_admin_row_and_conflict(self):
        before = self.db.readonly("SELECT encode(sha256(convert_to(to_jsonb(a)::text,'UTF8')),'hex') FROM public.admin_users a;").strip()
        planned = self.cli(); self.assertEqual(0, planned.returncode, planned.stderr)
        self.assertEqual("planned_create", json.loads(planned.stdout)["mode"]); self.assertEqual([0, 0, 0, 0], self.counts())
        applied = self.cli(True); self.assertEqual(0, applied.returncode, applied.stderr)
        self.assertEqual("created_and_verified", json.loads(applied.stdout)["mode"]); self.assertEqual([1, 1, 1, 1], self.counts())
        repeated = self.cli(True); self.assertEqual(0, repeated.returncode, repeated.stderr)
        self.assertEqual("verified_existing_noop", json.loads(repeated.stdout)["mode"]); self.assertEqual([1, 1, 1, 1], self.counts())
        conflict = self.cli(True, dict(verificationReference="SYNTHETIC-DIFFERENT-PROOF"))
        self.assertNotEqual(0, conflict.returncode); self.assertEqual([1, 1, 1, 1], self.counts())
        after = self.db.readonly("SELECT encode(sha256(convert_to(to_jsonb(a)::text,'UTF8')),'hex') FROM public.admin_users a;").strip()
        self.assertEqual(before, after)

    def test_profile_insert_failure_rolls_back_person_and_audits_with_redacted_errors(self):
        self.db.run("""CREATE FUNCTION public.fail_bootstrap_fixture() RETURNS trigger LANGUAGE plpgsql AS $$
          BEGIN RAISE EXCEPTION 'SYNTHETIC-PRIVATE-SQL-DETAIL'; END $$;
          CREATE TRIGGER bootstrap_fixture_failure BEFORE INSERT ON public.unified_admin_profiles FOR EACH ROW EXECUTE FUNCTION public.fail_bootstrap_fixture();""")
        try:
            result = self.cli(True)
            self.assertNotEqual(0, result.returncode)
            self.assertNotIn("SYNTHETIC-PRIVATE-SQL-DETAIL", result.stderr)
            self.assertEqual([0, 0, 0, 0], self.counts())
        finally:
            self.db.run("DROP TRIGGER bootstrap_fixture_failure ON public.unified_admin_profiles; DROP FUNCTION public.fail_bootstrap_fixture();")

    def test_existing_person_foreign_binding_inactive_or_non_hq_is_never_adopted(self):
        self.db.run("INSERT INTO public.unified_persons VALUES('" + PERSON + "','synthetic-preexisting-person','Existing');")
        self.assertNotEqual(0, self.cli(True).returncode); self.assertEqual([1, 0, 0, 0], self.counts())
        self.db.run("DELETE FROM public.unified_persons;")
        for change in ("role='TRAINING_MANAGER'", "status='blocked'", "access_mode='MANAGED'"):
            self.db.run("UPDATE public.admin_users SET " + change + ";")
            self.assertNotEqual(0, self.cli(True).returncode); self.assertEqual([0, 0, 0, 0], self.counts())
            self.db.run("UPDATE public.admin_users SET role='HQ_HEAD',status='active',access_mode='LEGACY';")


if __name__ == "__main__":
    unittest.main()
