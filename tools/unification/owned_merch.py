#!/usr/bin/env python3
"""Pinned, transactional staging -> owned M0 tables in an isolated candidate only."""

import argparse
import json
from pathlib import Path
import re
import sys

import crm_stage as stage


FIELDS = {
    "organizations": "name legal_name bin active created_at updated_at",
    "branches": "organization_id code name address city division latitude longitude price_adjustment_percent active created_at updated_at",
    "inventory_containers": "branch_id container_type code name category position_index active metadata created_at updated_at",
    "shelves": "branch_id container_id code category width_m base_monthly_price active place_type title position_index specifications sale_mode half_monthly_price created_at updated_at",
    "monthly_inventory": "shelf_id placement_month status monthly_price held_until block_reason block_note blocked_at created_at updated_at",
    "media_plans": "advertiser_name placement_month status held_until origin total_amount submitted_at created_at updated_at",
    "media_plan_items": "media_plan_id shelf_id placement_month monthly_price status sale_portion created_at",
}
TEXT_FIELDS = {"container_type", "status", "block_reason", "place_type", "sale_mode", "origin", "sale_portion"}
ORIGIN = "https://crm.inkar.kz"


def identity_bindings(mapping):
    stage.require(mapping.get("schemaVersion") == 1 and mapping.get("state") == "source_confirmed", "unverified identity artifact")
    source_links = {r["branchId"]: r["pharmacyId"] for r in mapping["sourceCrosswalk"]}
    stage.require(len(source_links) == len(mapping["sourceCrosswalk"]), "duplicate branch crosswalk")
    result = []
    used_pharmacies = set()
    for row in mapping["branches"]:
        source, canonical, relation = row["source"], row["canonical"], row["pharmacyRelation"]
        identifier = source["originalId"]
        stage.require(source["system"] == "MERCH_CRM" and source["entityType"] == "BRANCH", "wrong source namespace")
        stage.require(canonical == {"entityType": "MERCH_BRANCH", "id": identifier}, "branch identity was changed")
        pharmacy = None
        if relation is not None:
            stage.require(relation["entityType"] == "PHARMACY" and source_links.get(identifier) == relation["id"], "unproven pharmacy relation")
            stage.require(bool(row["pharmacyRelationProvenance"]), "missing crosswalk provenance")
            pharmacy = relation["id"]
            stage.require(pharmacy not in used_pharmacies, "crosswalk is not one-to-one")
            used_pharmacies.add(pharmacy)
        else:
            stage.require(identifier not in source_links, "crosswalk relation was lost")
        result.append({"id": identifier, "pharmacy_id": pharmacy})
    stage.require(len({r["id"] for r in result}) == len(result), "duplicate source branch")
    stage.require(len(used_pharmacies) == len(source_links), "crosswalk endpoint missing")
    return result


def projection(table):
    columns = ["id", "source_system", "source_origin", "source_id", "source_payload"] + FIELDS[table].split()
    values = ["s.id", "'MERCH_CRM'", stage.literal(ORIGIN), "s.id", "to_jsonb(s)"]
    values += ["s." + stage.qi(c) + ("::text" if c in TEXT_FIELDS else "") for c in FIELDS[table].split()]
    if table == "branches":
        columns += ["epharm_pharmacy_id", "pharmacy_binding_verified"]
        values += ["b.pharmacy_id", "b.pharmacy_id IS NOT NULL"]
    if table == "monthly_inventory":
        columns += ["blocked_by_source", "blocked_by_admin"]
        values += ["s.blocked_by", "NULL::uuid"]
    return columns, values


def comparison_sql(schema, table):
    columns, values = projection(table)
    joined = stage.qi(schema) + "." + stage.qi(table) + " s"
    if table == "branches":
        joined += " JOIN _owned_merch_bindings b ON b.id=s.id"
    comparisons = ["t." + stage.qi(c) + " IS NOT DISTINCT FROM (" + value + ")" for c, value in zip(columns, values)]
    return "SELECT count(*) FROM " + joined + " LEFT JOIN public." + stage.qi("merch_" + table) + \
        " t ON t.id=s.id WHERE t.id IS NULL OR NOT(" + " AND ".join(comparisons) + ")"


def assertion(condition, message):
    return "DO $owned$ BEGIN IF NOT(" + condition + ") THEN RAISE EXCEPTION " + stage.literal(message) + \
        " USING ERRCODE='23514'; END IF; END; $owned$;"


def import_sql(schema, bindings, source_hash, identity_hash, expected_rows, verification=""):
    stage.require(re.fullmatch(r"crm_stage_[0-9a-f]{16}", schema), "invalid pinned stage schema")
    key = "owned_merch_m0:" + source_hash[:24] + ":" + identity_hash[:24]
    qschema = stage.qi(schema)
    sql = ["BEGIN ISOLATION LEVEL REPEATABLE READ;", stage.SETTINGS, "SELECT pg_advisory_xact_lock(hashtextextended('owned_merch_m0',0));", verification,
           "CREATE TEMP TABLE _owned_merch_bindings(id uuid PRIMARY KEY, pharmacy_id varchar(64)) ON COMMIT DROP;",
           "INSERT INTO _owned_merch_bindings SELECT * FROM jsonb_to_recordset(" + stage.literal(json.dumps(bindings)) +
           "::jsonb) AS b(id uuid, pharmacy_id varchar(64));",
           assertion("(SELECT count(*) FROM _owned_merch_bindings)=(SELECT count(*) FROM " + qschema + ".branches)", "branch set differs"),
           assertion("NOT EXISTS(SELECT 1 FROM " + qschema + ".branches s FULL JOIN _owned_merch_bindings b ON b.id=s.id WHERE b.id IS NULL OR s.id IS NULL)", "branch IDs differ"),
           assertion("NOT EXISTS(SELECT 1 FROM _owned_merch_bindings b LEFT JOIN public.pharmacies p ON p.id=b.pharmacy_id WHERE b.pharmacy_id IS NOT NULL AND p.id IS NULL)", "pharmacy crosswalk endpoint missing"),
           assertion("NOT EXISTS(SELECT 1 FROM _owned_merch_bindings b LEFT JOIN " + qschema + ".epharm_branch_crosswalk c ON c.branch_id=b.id AND c.external_location_id=b.pharmacy_id WHERE b.pharmacy_id IS NOT NULL AND c.branch_id IS NULL)", "verified mapping differs from pinned source crosswalk"),
           "CREATE TEMP TABLE _owned_merch_replay AS SELECT EXISTS(SELECT 1 FROM public.unified_import_runs WHERE operation_key=" + stage.literal(key) + ") AS done;",
           assertion("NOT EXISTS(SELECT 1 FROM public.unified_import_runs WHERE operation_key=" + stage.literal(key) +
                     " AND (source_sha256<>" + stage.literal(source_hash) + " OR identity_sha256<>" + stage.literal(identity_hash) + "))", "import provenance changed")]
    for table in FIELDS:
        columns, values = projection(table)
        target = "public." + stage.qi("merch_" + table)
        sql += [assertion("(SELECT done FROM _owned_merch_replay) OR NOT EXISTS(SELECT 1 FROM " + target + ")", "owned target is not empty"),
                assertion("(SELECT count(*) FROM " + qschema + "." + stage.qi(table) + ")=" + str(expected_rows[table]), "stage row count drift"),
                "INSERT INTO " + target + "(" + ",".join(map(stage.qi, columns)) + ") SELECT " + ",".join(values) +
                " FROM " + qschema + "." + stage.qi(table) + " s" + (" JOIN _owned_merch_bindings b ON b.id=s.id" if table == "branches" else "") +
                " WHERE NOT(SELECT done FROM _owned_merch_replay);",
                assertion("(" + comparison_sql(schema, table) + ")=0", "owned projection or source payload differs"),
                assertion("(SELECT count(*) FROM " + target + ")=" + str(expected_rows[table]), "owned target row count drift")]
    # Supplier identity is the advertiser account, never the pharmacy organization or a free-text name.
    normalized_phone = "CASE WHEN a.phone IS NULL THEN NULL WHEN length(regexp_replace(a.phone,'[^0-9]','','g'))=10 THEN '+7'||regexp_replace(a.phone,'[^0-9]','','g') WHEN length(regexp_replace(a.phone,'[^0-9]','','g'))=11 AND left(regexp_replace(a.phone,'[^0-9]','','g'),1) IN ('7','8') THEN '+7'||substring(regexp_replace(a.phone,'[^0-9]','','g') FROM 2) ELSE '+'||regexp_replace(a.phone,'[^0-9]','','g') END"
    sql += ["INSERT INTO public.merch_producers(id,source_system,source_origin,source_id,source_payload,name,phone,phone_normalized,phone_verified_at,source_account_role,active,created_at,updated_at) "
            "SELECT p.user_id,'MERCH_CRM'," + stage.literal(ORIGIN) + ",p.user_id,to_jsonb(p),p.company_name,a.phone," + normalized_phone + ",a.phone_verified_at,a.role::text,a.active,p.created_at,p.updated_at "
            "FROM " + qschema + ".advertiser_profiles p JOIN " + qschema + ".app_users a ON a.id=p.user_id "
            "WHERE NOT(SELECT done FROM _owned_merch_replay);",
            assertion("NOT EXISTS(SELECT 1 FROM " + qschema + ".advertiser_profiles p JOIN " + qschema + ".app_users a ON a.id=p.user_id "
                      "LEFT JOIN public.merch_producers t ON t.id=p.user_id WHERE t.id IS NULL OR t.source_payload IS DISTINCT FROM to_jsonb(p) OR t.name IS DISTINCT FROM p.company_name OR t.active IS DISTINCT FROM a.active OR t.phone IS DISTINCT FROM a.phone OR t.phone_normalized IS DISTINCT FROM (" + normalized_phone + ") OR t.phone_verified_at IS DISTINCT FROM a.phone_verified_at OR t.source_account_role IS DISTINCT FROM a.role::text)", "producer source differs"),
            "INSERT INTO public.unified_external_accounts(id,source_system,source_origin,source_payload,email,full_name,phone,source_role,active,password_hash,created_at,updated_at) "
            "SELECT a.id,'MERCH_CRM'," + stage.literal(ORIGIN) + ",to_jsonb(a),a.email,a.full_name,a.phone,a.role::text,a.active,a.password_hash,a.created_at,a.updated_at "
            "FROM " + qschema + ".app_users a WHERE NOT(SELECT done FROM _owned_merch_replay);",
            assertion("NOT EXISTS(SELECT 1 FROM " + qschema + ".app_users a LEFT JOIN public.unified_external_accounts t ON t.id=a.id WHERE t.id IS NULL OR t.source_payload IS DISTINCT FROM to_jsonb(a) OR t.password_hash IS DISTINCT FROM a.password_hash)", "external account preservation differs")]
    refs = [("MERCH_CRM", ORIGIN, "BRANCH", "MERCH_BRANCH", qschema + ".branches", "id"),
            ("MERCH_CRM", ORIGIN, "ACCOUNT", "ACCOUNT", qschema + ".app_users", "id"),
            ("MERCH_CRM", ORIGIN, "CUSTOMER", "CUSTOMER", qschema + ".advertiser_profiles", "user_id"),
            ("EPHARM", "https://epharm.inkar.kz", "PHARMACY", "PHARMACY", "public.pharmacies", "id"),
            ("EPHARM", "https://epharm.inkar.kz", "ADMIN_ACCOUNT", "ADMIN_ACCOUNT", "public.admin_users", "id"),
            ("EPHARM", "https://epharm.inkar.kz", "PHARMACIST", "PHARMACIST", "public.pharmacists", "id")]
    for system, origin, entity, canonical, table, identifier in refs:
        constants = list(map(stage.literal, [system, origin, entity]))
        reference = "verified-snapshot:" + source_hash + ";identity:" + identity_hash
        sql += ["INSERT INTO public.unified_source_links(source_system,source_origin,source_entity_type,source_id,canonical_entity_type,canonical_id,verification_reference) "
                "SELECT " + ",".join(constants) + ",s." + stage.qi(identifier) + "::text," + stage.literal(canonical) + ",s." + stage.qi(identifier) + "::text," + stage.literal(reference) +
                " FROM " + table + " s WHERE NOT(SELECT done FROM _owned_merch_replay);",
                assertion("NOT EXISTS(SELECT 1 FROM " + table + " s LEFT JOIN public.unified_source_links l ON " +
                          " AND ".join("l." + k + "=" + v for k, v in zip(["source_system", "source_origin", "source_entity_type"], constants)) +
                          " AND l.source_id=s." + stage.qi(identifier) + "::text WHERE l.canonical_entity_type IS DISTINCT FROM " + stage.literal(canonical) +
                          " OR l.canonical_id IS DISTINCT FROM s." + stage.qi(identifier) + "::text)", "canonical source identity differs")]
    result = {"sourceRows": sum(expected_rows.values()), "tables": len(FIELDS), "pharmacyRelations": sum(b["pharmacy_id"] is not None for b in bindings),
              "scope": "owned M0 materialization; no account/person merge, activation, rewards, jobs or source writes"}
    sql += ["INSERT INTO public.unified_import_runs(operation_key,source_sha256,identity_sha256,result_json) SELECT " +
            ",".join(map(stage.literal, [key, source_hash, identity_hash, json.dumps(result)])) +
            "::jsonb WHERE NOT(SELECT done FROM _owned_merch_replay);",
            "SELECT json_build_object('mode',CASE WHEN (SELECT done FROM _owned_merch_replay) THEN 'verified_existing_noop' ELSE 'materialized_and_verified' END,'operationKey'," + stage.literal(key) + ", 'result'," + stage.literal(json.dumps(result)) + "::jsonb);", "COMMIT;"]
    return "\n".join(sql)


def run(args):
    stage.require(not args.report.exists() and not args.report.is_symlink(), "report must be a new private path")
    stage.require(args.report.resolve() not in {args.artifact.resolve(), args.identities.resolve()}, "report cannot replace source input")
    stage.require(all(p.stat().st_mode & 0o077 == 0 for p in [args.artifact, args.identities]), "source inputs must be owner-only")
    artifact, source_hash = stage.parse_json(args.artifact.read_bytes()), stage.file_hash(args.artifact)
    mapping, identity_hash = stage.parse_json(args.identities.read_bytes()), stage.file_hash(args.identities)
    stage.require(source_hash == stage.hash_arg(args.artifact_sha256), "source artifact SHA mismatch")
    stage.require(identity_hash == stage.hash_arg(args.identity_sha256), "identity artifact SHA mismatch")
    stage.validate_artifact(artifact, 57)
    bindings = identity_bindings(mapping)
    db = stage.Database(args.container, args.user, args.database)
    stage.require(args.database == "epharm_unified_candidate", "only the designated candidate database is allowed")
    target = db.inspect()
    stage.require(target["identitySha256"] == stage.hash_arg(args.target_sha256), "candidate identity mismatch")
    stage.require(target["versionNum"] // 10000 == 16, "owned target must be PostgreSQL16")
    stage.require(target["freeBytes"] >= stage.MIN_HEADROOM + args.artifact.stat().st_size * 8, "insufficient target headroom")
    schema = "crm_stage_" + source_hash[:16]
    expected = {table: len(artifact["tables"][table]) for table in FIELDS}
    stage.require(len(bindings) == expected["branches"], "identity and source branch counts differ")
    output = db.run(import_sql(schema, bindings, source_hash, identity_hash, expected,
                              stage.verification_sql(artifact, schema, source_hash, True)))
    summary = stage.parse_json([line for line in output.splitlines() if line.startswith('{')][-1])
    summary.update(sourceArtifactSha256=source_hash, identityArtifactSha256=identity_hash,
                   targetIdentitySha256=target["identitySha256"], productionChanged=False)
    stage.private_write(args.report, stage.encoded(summary))
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ["container", "user", "database", "artifact-sha256", "identity-sha256", "target-sha256"]:
        parser.add_argument("--" + name, required=True)
    for name in ["artifact", "identities", "report"]:
        parser.add_argument("--" + name, type=Path, required=True)
    try:
        result = run(parser.parse_args())
        print(json.dumps(result, sort_keys=True))
    except (stage.StageError, ValueError, KeyError, TypeError, OSError):
        print("Owned materialization refused; no source values disclosed. Inspect verified inputs and candidate state.", file=sys.stderr)
        raise SystemExit(1)


if __name__ == "__main__":
    main()
