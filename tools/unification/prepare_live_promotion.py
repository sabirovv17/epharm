#!/usr/bin/env python3
"""Prepare a private SQL package; never apply it.

SQL pins database/system/OID/schema. Only the manifest's exact docker-exec container
ID binds Docker identity: operators must verify the external package SHA and command.
"""
import argparse
import copy
import json
import os
from pathlib import Path
import re
import stat
import sys

import crm_stage as stage


SCHEMA_VERSION = "088"
# Deliberately versioned, not inferred from whatever tables a source happens to contain.
BASE_NATIVE_TABLES = tuple("""
learner_credential_export_attempts learner_credential_export_audit learner_credential_exports learner_password_vault
merch_automation_branch_owners merch_automation_owner_audit merch_branches merch_commercial_audit
merch_floor_plan_versions merch_instruction_events merch_instruction_materials merch_instruction_plans
merch_inventory_audit merch_inventory_containers merch_inventory_editor_audit merch_inventory_media
merch_media_plan_items merch_media_plans merch_monthly_inventory merch_monthly_visits merch_organizations
merch_placement_audit merch_placement_campaigns merch_placement_item_product_details merch_placement_reference_photos
merch_producer_auth_limits merch_producer_challenges merch_producer_profile_audit merch_producer_refresh_tokens
merch_producer_sessions merch_producers merch_shelves merch_supplier_budget_plan_items merch_supplier_budget_plans
merch_supplier_company_members merch_supplier_pricing_tiers merch_supplier_settings merch_tariff_audit
merch_task_attempts merch_task_bulk_receipts merch_task_comments merch_task_delivery_receipts merch_task_dispatches
merch_task_events merch_task_evidence merch_task_items merch_task_links merch_task_reviews merch_task_rewards
merch_task_source_stock merch_task_stock_observations merch_task_template_receipts
mobile_password_reset_challenges mobile_password_reset_rate_limits pharmacist_person_links
promo_producer_binding_retractions promo_producer_binding_versions promo_producer_bindings
promo_publication_changes promo_publication_history unified_admin_access_audit unified_admin_profiles
unified_external_account_bindings unified_external_accounts unified_identity_audit unified_import_runs
unified_persons unified_source_links
""".split())
EXPLICIT_ADDITIVE_TABLES = ("merch_legacy_capability_aliases",)
NATIVE_TABLES = tuple(sorted((*BASE_NATIVE_TABLES, *EXPLICIT_ADDITIVE_TABLES)))
NEVER_POPULATED = frozenset("""
learner_credential_export_attempts learner_credential_export_audit learner_credential_exports learner_password_vault
merch_producer_auth_limits merch_producer_challenges merch_producer_refresh_tokens merch_producer_sessions
mobile_password_reset_challenges mobile_password_reset_rate_limits
""".split())
PIN_FIELDS = {"database", "oid", "systemIdentifier", "versionNum", "containerId", "identitySha256", "user"}


def schema_profile():
    return stage.parse_json(Path(__file__).with_name("promotion_schema_088.json").read_bytes())


def expected_target_metadata(metadata):
    """Only two reviewed complete schema088 fingerprints; no SQL equivalence engine."""
    profile = schema_profile()
    stage.require(set(profile) == {"schemaVersion", "sourceSchemaSha256", "plainSchemaSha256", "substitutions"}
                  and profile["schemaVersion"] == SCHEMA_VERSION, "invalid pinned schema variant profile")
    actual = stage.schema_hash(metadata)
    source_hash = stage.hash_arg(profile["sourceSchemaSha256"])
    plain_hash = stage.hash_arg(profile["plainSchemaSha256"])
    stage.require(actual in {source_hash, plain_hash}, "unreviewed source schema088 fingerprint")
    if actual == plain_hash:
        return metadata
    expected = copy.deepcopy(metadata)
    seen = set()
    for patch in profile["substitutions"]:
        stage.require(set(patch) == {"table", "constraint", "sourceDefinition", "targetDefinition"}, "invalid CHECK substitution")
        key = (patch["table"], patch["constraint"])
        stage.require(key not in seen, "duplicate CHECK substitution")
        seen.add(key)
        matches = [c for t in expected["tables"] if t["name"] == patch["table"]
                   for c in t["constraints"] if c["name"] == patch["constraint"]]
        stage.require(len(matches) == 1 and matches[0]["kind"] == "c"
                      and matches[0]["definition"] == patch["sourceDefinition"], "pinned CHECK source differs")
        matches[0]["definition"] = patch["targetDefinition"]
    stage.require(len(seen) == 29 and stage.schema_hash(expected) == plain_hash, "pinned CHECK transformation differs")
    return expected


def private_input(path, expected_sha, limit=256 * 1024 * 1024):
    with os.fdopen(os.open(path, os.O_RDONLY | os.O_NOFOLLOW), "rb") as stream:
        info = os.fstat(stream.fileno())
        stage.require(stat.S_ISREG(info.st_mode) and info.st_uid == os.getuid()
                      and stat.S_IMODE(info.st_mode) in (0o400, 0o600) and info.st_size <= limit,
                      "input must be a bounded caller-owned private regular file")
        raw = stream.read(limit + 1)
    stage.require(len(raw) <= limit and stage.digest(raw) == stage.hash_arg(expected_sha), "input SHA256 mismatch")
    return stage.parse_json(raw)


def validate_pin(pin):
    stage.require(isinstance(pin, dict) and set(pin) == PIN_FIELDS, "target pin fields differ")
    for key in ("database", "user"):
        stage.require(isinstance(pin[key], str) and re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", pin[key]), "invalid target identifier")
    oid = pin["oid"]
    stage.require((type(oid) is int and 0 < oid < 2 ** 32)
                  or (isinstance(oid, str) and re.fullmatch(r"[1-9][0-9]*", oid) and int(oid) < 2 ** 32),
                  "invalid target database OID")
    stage.require(type(pin["versionNum"]) is int and pin["versionNum"] // 10000 == 16, "target must be PG16")
    stage.require(isinstance(pin["systemIdentifier"], str) and re.fullmatch(r"[0-9]+", pin["systemIdentifier"]), "invalid target system identifier")
    stage.require(isinstance(pin["containerId"], str) and re.fullmatch(r"[0-9a-f]{64}", pin["containerId"]), "exact target container ID required")
    identity = {k: pin[k] for k in ("database", "oid", "systemIdentifier", "versionNum", "containerId")}
    stage.require(stage.digest(stage.encoded(identity)) == stage.hash_arg(pin["identitySha256"]), "target pin identity digest differs")
    return pin


def assertion(condition, message):
    body = "BEGIN IF NOT(" + condition + ") THEN RAISE EXCEPTION " + stage.literal(message) + " USING ERRCODE='23514'; END IF; END"
    tag = "$promotion_" + stage.digest(body.encode())[:16] + "$"
    while tag in body:
        tag = tag[:-1] + "x$"
    return "DO " + tag + body + tag + ";"


def table_order(tables):
    names = {t["name"] for t in tables}
    pending = {t["name"]: {c["foreignTable"] for c in t["constraints"] if c["kind"] == "f"
                          and c["foreignSchema"] == "public" and c["foreignTable"] in names
                          and c["foreignTable"] != t["name"]} for t in tables}
    result = []
    while pending:
        ready = sorted(name for name, parents in pending.items() if not parents)
        stage.require(ready, "cross-table FK cycle requires a separately reviewed import; constraints stay enabled")
        result.extend(ready)
        pending = {name: parents.difference(ready) for name, parents in pending.items() if name not in ready}
    return result


def migration_query():
    return "SELECT json_build_object('kind','migrations','rows',coalesce(jsonb_agg(jsonb_build_array(version,type,script,checksum,success) ORDER BY installed_rank),'[]'::jsonb)) FROM public.flyway_schema_history;"


def validate_snapshot(snapshot):
    metadata = snapshot["metadata"]
    names = [t["name"] for t in metadata["tables"]]
    stage.require(len(BASE_NATIVE_TABLES) == 68 and len(NATIVE_TABLES) == 69 and len(set(NATIVE_TABLES)) == 69, "invalid compiled allowlist")
    stage.require(len(names) == 142 and set(NATIVE_TABLES).issubset(names) and "flyway_schema_history" in names,
                  "schema088 must have exactly original72+Flyway+native69 tables")
    originals = [t for t in metadata["tables"] if t["name"] not in NATIVE_TABLES and t["name"] != "flyway_schema_history"]
    stage.require(len(originals) == 72, "original72 inventory differs")
    tables = [t for t in metadata["tables"] if t["name"] in NATIVE_TABLES]
    for table in tables:
        stage.primary_columns(table)
        stage.require(table["kind"] == "r" and not table["rowSecurity"] and not table["forceRowSecurity"], "native table/RLS kind requires separate review")
        stage.require(all(not c["identity"] and not c["generated"] for c in table["columns"]), "native generated columns require separate review")
        stage.require(all("nextval(" not in (c.get("default") or "") for c in table["columns"]), "native sequences require separate review")
    stage.require(set(snapshot["rows"]) == set(NATIVE_TABLES), "native row coverage differs")
    stage.require(all(not snapshot["rows"][name] for name in NEVER_POPULATED), "ephemeral session/OTP/vault/export rows must never be promoted")
    rows = snapshot["migrations"]
    versions = [r[0] for r in rows if r[0] is not None]
    stage.require(rows and all(len(r) == 5 and r[4] is True for r in rows)
                  and max(int(v) for v in versions) == int(SCHEMA_VERSION), "source migration version/success differs")
    checksums = [stage.table_checksum(t, snapshot["rows"][t["name"]]) for t in tables]
    return originals, tables, checksums, table_order(tables)


def capture(db, expected_identity, artifact, artifact_sha):
    info = db.inspect()
    stage.require(info["identitySha256"] == stage.hash_arg(expected_identity) and info["versionNum"] // 10000 == 16,
                  "source candidate identity/version differs")
    meta = stage.parse_json(db.readonly(stage.metadata_sql()))
    native = {**meta, "tables": [t for t in meta["tables"] if t["name"] in NATIVE_TABLES]}
    schema = "crm_stage_" + artifact_sha[:16]
    # The metadata, migrations, verified inert stage and rows share one consistent source snapshot.
    frames = db.readonly(stage.metadata_sql() + migration_query()
                         + stage.verification_sql(artifact, schema, artifact_sha, True)
                         + stage.data_query(native, "public")).splitlines()
    actual = stage.parse_json(frames[0])
    stage.require(stage.schema_hash(actual) == stage.schema_hash(meta), "source DDL changed during capture")
    migration_frame = stage.parse_json(frames[1])
    stage.require(migration_frame["kind"] == "migrations", "migration frame missing")
    data = {name: [] for name in NATIVE_TABLES}
    for line in frames[2:]:
        row = stage.parse_json(line)
        stage.require(row["kind"] == "row" and row["table"] in data, "unexpected native row frame")
        data[row["table"]].append(row["values"])
    snapshot = {"metadata": actual, "migrations": migration_frame["rows"], "rows": data,
                "sourceIdentitySha256": expected_identity}
    validate_snapshot(snapshot)
    stage.require(db.inspect()["identitySha256"] == expected_identity, "source candidate was replaced")
    return snapshot


def facts_query(table, schema="public"):
    rows = stage.hash_expression([c["name"] for c in table["columns"]])
    keys = stage.hash_expression(stage.primary_columns(table))
    aggregate = "encode(pg_catalog.sha256(convert_to(coalesce(string_agg(v.VALUE||E'\\n','' ORDER BY v.VALUE COLLATE pg_catalog.\"C\"),''),'UTF8')),'hex')"
    return "SELECT " + stage.literal(table["name"]) + "::text AS name,count(*)::bigint AS n," + aggregate.replace("VALUE", "rh") + " AS rh," + aggregate.replace("VALUE", "kh") + " AS kh FROM (SELECT " + rows + " AS rh," + keys + " AS kh FROM " + stage.qi(schema) + "." + stage.qi(table["name"]) + ") v"


def verify_native(tables, checksums):
    by_name = {c["table"]: c for c in checksums}
    return "\n".join(assertion("(SELECT jsonb_build_array(n,rh,kh) FROM (" + facts_query(t) + ") f)="
                               + stage.literal(json.dumps([by_name[t["name"]][key] for key in ("rowCount", "rowSha256", "primaryKeySha256")]))
                               + "::jsonb", "native count/PK/full-values mismatch") for t in tables)


def copy_table(table, rows):
    return "COPY public." + stage.qi(table["name"]) + " (" + ",".join(stage.qi(c["name"]) for c in table["columns"]) + ") FROM STDIN WITH (FORMAT text);\n" + "".join("\t".join(stage.copy_value(v) for v in row) + "\n" for row in rows) + "\\.\n"


def build_sql(snapshot, artifact, artifact_sha, pin):
    originals, tables, checksums, order = validate_snapshot(snapshot)
    validate_pin(pin)
    schema = "crm_stage_" + stage.hash_arg(artifact_sha)[:16]
    # Reuse the validated inert generator, stripping only its own fixed outer transaction.
    inert = stage.load_sql(artifact, schema, artifact_sha)
    stage.require(inert.startswith("BEGIN;\n") and inert.endswith(" COMMIT;"), "inert generator transaction contract changed")
    inert = inert[len("BEGIN;\n"):-len(" COMMIT;")]
    target_metadata = expected_target_metadata(snapshot["metadata"])
    stable_meta = {k: v for k, v in target_metadata.items() if k != "database"}
    locale = {k: snapshot["metadata"]["database"][k] for k in ("encoding", "collate", "ctype", "localeProvider")}
    locale_sql = "(SELECT jsonb_build_object('encoding',current_setting('server_encoding'),'collate',datcollate,'ctype',datctype,'localeProvider',datlocprovider) FROM pg_database WHERE datname=current_database())"
    current_meta = "(" + stage.metadata_sql().strip().rstrip(";") + ")::jsonb-'database'"
    native_locks = ",".join("public." + stage.qi(n) for n in sorted(NATIVE_TABLES))
    guard = ("current_database()=" + stage.literal(pin["database"]) + " AND current_user=" + stage.literal(pin["user"])
             + " AND (SELECT oid FROM pg_database WHERE datname=current_database())=" + str(pin["oid"])
             + " AND (SELECT system_identifier::text FROM pg_control_system())=" + stage.literal(pin["systemIdentifier"])
             + " AND current_setting('server_version_num')::integer=" + str(pin["versionNum"]))
    sql = ["\\set ON_ERROR_STOP on", "\\set VERBOSITY sqlstate", "\\set SHOW_CONTEXT never",
           "BEGIN ISOLATION LEVEL REPEATABLE READ;", "SET LOCAL lock_timeout='5s'; SET LOCAL statement_timeout='120s';",
           stage.SETTINGS,
           # Acquire all native write fences before taking the data snapshot. Originals remain writable.
           "LOCK TABLE " + native_locks + " IN SHARE ROW EXCLUSIVE MODE;",
           "SELECT pg_advisory_xact_lock(hashtextextended('unified_native_promotion_088',0));",
           assertion(guard, "target database identity differs"),
           assertion(locale_sql + "=" + stage.literal(stage.encoded(locale).decode()) + "::jsonb", "target database locale differs"),
           assertion("(" + current_meta + ")=" + stage.literal(stage.encoded(stable_meta).decode()) + "::jsonb", "target088 full schema differs")]
    # Compare every installed migration's code checksum; install timestamps may legitimately differ.
    sql.append(assertion("(SELECT coalesce(jsonb_agg(jsonb_build_array(version,type,script,checksum,success) ORDER BY installed_rank),'[]'::jsonb) FROM public.flyway_schema_history)="
                         + stage.literal(stage.encoded(snapshot["migrations"]).decode()) + "::jsonb", "target088 Flyway checksums differ"))
    sql += [assertion("NOT EXISTS(SELECT 1 FROM pg_namespace WHERE nspname LIKE 'crm_stage_%')", "CRM stage exists; refusing replay without changes"),
            assertion(" AND ".join("NOT EXISTS(SELECT 1 FROM public." + stage.qi(n) + ")" for n in sorted(NATIVE_TABLES)),
                      "native targets are not pristine; refusing replay without changes"),
            "CREATE TEMP TABLE _promotion_original_before ON COMMIT DROP AS " + " UNION ALL ".join(facts_query(t) for t in originals) + ";",
            inert]
    by_name = {t["name"]: t for t in tables}
    sql.extend(copy_table(by_name[n], snapshot["rows"][n]) for n in order)
    sql += [stage.verification_sql(artifact, schema, artifact_sha, True), verify_native(tables, checksums),
            "CREATE TEMP TABLE _promotion_original_after ON COMMIT DROP AS " + " UNION ALL ".join(facts_query(t) for t in originals) + ";",
            assertion("NOT EXISTS((TABLE _promotion_original_before EXCEPT TABLE _promotion_original_after) UNION ALL (TABLE _promotion_original_after EXCEPT TABLE _promotion_original_before))", "original72 changed inside promotion; rollback required"),
            "SELECT json_build_object('mode','imported_and_verified','nativeTables',69,'crmTables',57,'originalTablesUnchanged',72,'sourceIdentitySha256'," + stage.literal(snapshot["sourceIdentitySha256"]) + ",'targetIdentitySha256'," + stage.literal(pin["identitySha256"]) + ");", "COMMIT;"]
    return "\n".join(sql) + "\n", checksums


def prepare(args, database_factory=stage.Database):
    proposal = private_input(args.proposal, args.proposal_sha256)
    stage.require(proposal.get("newPublicTableAllowlist") == list(BASE_NATIVE_TABLES), "approved base68 allowlist differs from compiled table list")
    artifact = private_input(args.crm_artifact, args.crm_sha256)
    stage.validate_artifact(artifact, 57)
    pin = validate_pin(private_input(args.target_pin, args.target_pin_sha256))
    stage.require(re.fullmatch(r"epharm-unify-candidate-[a-zA-Z0-9_.-]+", args.source_container)
                  and args.source_database == "epharm_unified_candidate", "source must be the dedicated offline candidate")
    parent = args.output.parent.resolve(strict=True)
    info = parent.stat()
    stage.require(parent == args.output.parent and info.st_uid == os.getuid() and stat.S_IMODE(info.st_mode) == 0o700,
                  "output parent must be physical caller-owned0700")
    stage.require(not args.output.exists() and not args.output.is_symlink(), "output already exists; nothing will be replaced")
    db = database_factory(args.source_container, args.source_user, args.source_database)
    snapshot = capture(db, args.source_identity_sha256, artifact, args.crm_sha256)
    sql, checksums = build_sql(snapshot, artifact, args.crm_sha256, pin)
    # New exclusive private paths only. No database run/apply method is called here.
    args.output.mkdir(mode=0o700)
    stage.private_write(args.output / "promotion.sql", sql.encode())
    stage.private_write(args.output / "native-snapshot.json", stage.encoded(snapshot))
    stage.private_write(args.output / "crm-artifact.json", stage.encoded(artifact))
    manifest = {"formatVersion": 1, "schemaVersion": SCHEMA_VERSION, "target": pin,
                "sourceIdentitySha256": args.source_identity_sha256, "crmArtifactSha256": args.crm_sha256,
                "approvedProposalSha256": args.proposal_sha256, "explicitReviewedAdditiveTables": list(EXPLICIT_ADDITIVE_TABLES), "nativeTables": list(NATIVE_TABLES),
                "schemaVariantProfileSha256": stage.file_hash(Path(__file__).with_name("promotion_schema_088.json")),
                "nativeChecksums": checksums, "nativeRows": sum(c["rowCount"] for c in checksums),
                "replayBehavior": "refuse_without_changes; no merging or seed reconciliation",
                "writeTargets": {"public": list(NATIVE_TABLES), "crmStage": "crm_stage_" + args.crm_sha256[:16],
                                 "crmTables": [t["name"] for t in artifact["metadata"]["tables"]] + [stage.LEDGER]},
                "originalTablesWritten": [], "execution": "operator review required; generator never executes generated SQL",
                "targetBinding": "SQL validates DB/system/OID/version/schema; Docker container identity is enforced only by the exact manifest operatorCommand, not by SQL alone",
                "operatorCommand": ["docker", "exec", "-i", pin["containerId"], "psql", "-X", "--username=" + pin["user"],
                                    "--dbname=" + pin["database"], "--set=ON_ERROR_STOP=1"],
                "files": {n: stage.file_hash(args.output / n) for n in ("promotion.sql", "native-snapshot.json", "crm-artifact.json")}}
    stage.private_write(args.output / "manifest.json", stage.encoded(manifest))
    stage.private_write(args.output / "SHA256SUMS", "".join(stage.file_hash(args.output / n) + "  " + n + "\n" for n in (*manifest["files"], "manifest.json")).encode())
    return {"state": "prepared_not_applied", "schemaVersion": SCHEMA_VERSION, "nativeTables": len(NATIVE_TABLES),
            "nativeRows": manifest["nativeRows"], "crmTables": 57, "package": str(args.output),
            "manifestSha256": stage.file_hash(args.output / "manifest.json"), "sqlSha256": manifest["files"]["promotion.sql"],
            "packageSha256": stage.file_hash(args.output / "SHA256SUMS"),
            "targetIdentitySha256": pin["identitySha256"], "sourceWrites": False, "targetConnections": 0}


def parser():
    result = argparse.ArgumentParser(description=__doc__)
    for name in ("proposal", "crm-artifact", "target-pin", "output"):
        result.add_argument("--" + name, type=Path, required=True)
    for name in ("proposal-sha256", "crm-sha256", "target-pin-sha256", "source-container", "source-user", "source-database", "source-identity-sha256"):
        result.add_argument("--" + name, required=True)
    return result


def main(argv=None):
    try:
        print(json.dumps(prepare(parser().parse_args(argv)), sort_keys=True))
        return 0
    except (stage.StageError, OSError, ValueError, TypeError, KeyError, IndexError):
        print("Promotion preparation refused; private values suppressed. No package was applied.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
