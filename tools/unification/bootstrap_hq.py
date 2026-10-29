#!/usr/bin/env python3
"""Plan or explicitly bootstrap one verified HQ profile in a pinned, offline PG16 candidate.

No role/status/password/access_mode or primary-owner configuration is changed.
The private input supplies every identity fact and grant; no account or person is guessed.
"""

import argparse
import json
import os
from pathlib import Path
import re
import stat
import sys
import uuid

import crm_stage as stage


DATABASE = "epharm_unified_candidate"
FIELDS = {"schemaVersion", "accountId", "personId", "displayName", "verificationReference", "jobTitle", "grants"}
GRANT_FIELDS = {"module", "subsection", "action", "allPharmacies", "pharmacyIds", "allMerchBranches", "merchBranchIds", "allProducers", "producerIds"}
MODULES = {"analytics", "motivation", "training", "merchandising", "pharmacies_products", "pharmacists", "management"}
KINDS = {"ORDINARY", "SENSITIVE_DATA", "FINANCIAL", "EXPORT", "CREDENTIAL_EXPORT", "POSM_TECHNICAL", "ACCESS_ADMINISTRATION"}
CREDENTIAL_EXPORT = ("pharmacists", "registry", "export_credentials")
# Explicit, bounded source definitions approved alongside the pinned route catalog. Never auto-issued.
MANUAL = {
    ("management", "accounts", "view"): "SENSITIVE_DATA",
    ("management", "accounts", "manage"): "ACCESS_ADMINISTRATION",
    ("pharmacists", "registry", "personal_data"): "SENSITIVE_DATA",
    ("pharmacists", "registry", "financial_data"): "FINANCIAL",
    ("pharmacies_products", "pharmacies", "financial_data"): "FINANCIAL",
    ("motivation", "bonuses", "configure_bonus"): "FINANCIAL",
    ("motivation", "recommendations", "publish"): "ORDINARY",
    ("merchandising", "inventory", "financial_data"): "FINANCIAL",
    ("merchandising", "placements", "financial_data"): "FINANCIAL",
    ("merchandising", "tasks", "financial_data"): "FINANCIAL",
    ("training", "assignments", "view"): "ORDINARY",
    ("training", "results", "view"): "ORDINARY",
}
PREREQUISITES = {
    ("management", "accounts", "manage"): ("management", "accounts", "view"),
    ("motivation", "campaigns", "manage_producer"): ("motivation", "campaigns", "view"),
    ("merchandising", "placements", "bind_producer"): ("merchandising", "placements", "view"),
}


def read_file(path, private=False, limit=1024 * 1024):
    """Inspect the opened inode, not a symlink or a path checked before opening."""
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
    with os.fdopen(os.open(path, flags), "rb") as stream:
        info = os.fstat(stream.fileno())
        stage.require(stat.S_ISREG(info.st_mode), "input must be a regular non-symlink file")
        if private:
            stage.require(info.st_uid == os.getuid() and stat.S_IMODE(info.st_mode) in (0o400, 0o600),
                          "identity input must be caller-owned mode 0400 or 0600")
        data = stream.read(limit + 1)
    stage.require(len(data) <= limit, "input exceeds the bounded JSON size")
    return data


def permission_key(value):
    stage.require(isinstance(value, dict), "permission must be an object")
    key = tuple(value.get(field) for field in ("module", "subsection", "action"))
    stage.require(key[0] in MODULES and all(isinstance(v, str) and re.fullmatch(r"[a-z][a-z0-9_]{0,63}", v) for v in key),
                  "invalid permission identifiers")
    return key


def permission_catalog(routes, include_credential_export=False):
    stage.require(isinstance(routes, list) and 0 < len(routes) <= 10000, "routes must be a bounded nonempty array")
    allowed = dict(MANUAL)
    denied, apis = set(), set()
    for route in routes:
        stage.require(isinstance(route, dict) and set(route) <= {"api", "permission", "mode", "explicitAccess"}, "unsupported route shape")
        stage.require(isinstance(route.get("api"), str) and route["api"] not in apis, "duplicate or missing route API")
        apis.add(route["api"])
        stage.require(route.get("mode") in {"SCOPED", "ALL_ONLY", "NOT_READY"}, "unknown route integration mode")
        definition = route.get("permission")
        stage.require(isinstance(definition, dict) and set(definition) == {"module", "subsection", "action", "kind"}, "unsupported permission shape")
        key = permission_key(definition)
        kind = definition["kind"]
        stage.require(kind in KINDS, "unknown permission kind")
        if kind == "CREDENTIAL_EXPORT":
            denied.add(key)
        if route["mode"] != "NOT_READY":
            stage.require(key not in allowed or allowed[key] == kind, "active permission kind conflicts with approved catalog")
            allowed[key] = kind
    for key in denied:
        allowed.pop(key, None)
    if include_credential_export:
        allowed[CREDENTIAL_EXPORT] = "CREDENTIAL_EXPORT"
    return allowed


def text(value, label, maximum, blank=False):
    stage.require(isinstance(value, str), label + " must be text")
    stage.require(not any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in value), label + " contains control characters")
    value = value.strip()
    stage.require((blank or bool(value)) and len(value) <= maximum, label + " is empty or too long")
    return value


def canonical_uuid(value):
    stage.require(isinstance(value, str), "identity IDs must be canonical UUID strings")
    try:
        parsed = uuid.UUID(value)
    except ValueError:
        raise stage.StageError("identity IDs must be canonical UUID strings") from None
    stage.require(str(parsed) == value, "identity IDs must be canonical lowercase UUID strings")
    return value


def validate_payload(payload, allowed, include_credential_export=False):
    stage.require(isinstance(payload, dict) and set(payload) == FIELDS, "identity input has missing or unsupported fields")
    stage.require(type(payload["schemaVersion"]) is int and payload["schemaVersion"] == 1, "unsupported identity schema version")
    result = {"schemaVersion": 1, "accountId": canonical_uuid(payload["accountId"]), "personId": canonical_uuid(payload["personId"]),
              "displayName": text(payload["displayName"], "displayName", 255),
              "verificationReference": text(payload["verificationReference"], "verificationReference", 1000),
              "jobTitle": text(payload["jobTitle"], "jobTitle", 255, blank=True)}
    grants = payload["grants"]
    stage.require(isinstance(grants, list) and 1 <= len(grants) <= 200, "provide between 1 and 200 explicit grants")
    normalized, keys = [], set()
    for grant in grants:
        stage.require(isinstance(grant, dict) and set(grant) <= GRANT_FIELDS, "grant has unsupported fields")
        key = permission_key(grant)
        stage.require(key in allowed and (allowed[key] != "CREDENTIAL_EXPORT" or
                      include_credential_export and key == CREDENTIAL_EXPORT),
                      "unknown, not-ready or unapproved credential-export grant is forbidden")
        stage.require(key not in keys, "repeated permission grant")
        keys.add(key)
        row = dict(zip(("module", "subsection", "action"), key))
        for flag in ("allPharmacies", "allMerchBranches", "allProducers"):
            stage.require(type(grant.get(flag, False)) is bool, "ALL flags must be booleans")
            row[flag] = grant.get(flag, False)
        for field in ("pharmacyIds", "merchBranchIds", "producerIds"):
            stage.require(isinstance(grant.get(field, []), list) and not grant.get(field, []), "bootstrap accepts typed ALL scopes only, never identity lists")
            row[field] = []
        if key[0] == "merchandising":
            stage.require(not row["allPharmacies"] and row["allMerchBranches"], "merch grant requires branch ALL, never pharmacy ALL")
            stage.require(row["allProducers"] == (key[1] in {"placements", "producers"}), "customer ALL must match the exact merch resource domain")
        else:
            stage.require(row["allPharmacies"] and not row["allMerchBranches"] and not row["allProducers"], "non-merch grant requires pharmacy ALL only")
        normalized.append(row)
    for key in keys:
        prerequisite = PREREQUISITES.get(key)
        if allowed[key] in {"EXPORT", "CREDENTIAL_EXPORT"}:
            prerequisite = (key[0], key[1], "view")
        stage.require(prerequisite is None or prerequisite in keys, "explicit same-domain view prerequisite is missing")
    result["grants"] = sorted(normalized, key=lambda g: (g["module"], g["subsection"], g["action"]))
    return result


def facts_sql(input_select):
    return """WITH input AS (%s), desired AS (SELECT payload,(payload->>'accountId')::uuid AS account_id,
        (payload->>'personId')::uuid AS person_id FROM input),
        account_row AS (SELECT a.* FROM public.admin_users a,desired d WHERE a.id=d.account_id),
        profile_row AS (SELECT p.* FROM public.unified_admin_profiles p,desired d WHERE p.account_id=d.account_id),
        person_row AS (SELECT p.* FROM public.unified_persons p,desired d WHERE p.id=d.person_id)
        SELECT jsonb_build_object(
          'accountCount',(SELECT count(*) FROM account_row),
          'accountEligible',EXISTS(SELECT 1 FROM account_row WHERE status='active' AND role IN ('SYSTEM_ADMIN','HQ_HEAD') AND access_mode='LEGACY'),
          'adminRowSha256',(SELECT encode(pg_catalog.sha256(convert_to(to_jsonb(a)::text,'UTF8')),'hex') FROM account_row a),
          'profileCount',(SELECT count(*) FROM profile_row),'personCount',(SELECT count(*) FROM person_row),
          'exactProfile',EXISTS(SELECT 1 FROM profile_row p,desired d WHERE p.person_id=d.person_id AND p.version=0
              AND p.job_title=d.payload->>'jobTitle' AND p.grants_json::jsonb=d.payload->'grants'),
          'exactPerson',EXISTS(SELECT 1 FROM person_row p,desired d WHERE p.verification_reference=d.payload->>'verificationReference' AND p.display_name=d.payload->>'displayName'),
          'otherPersonBindings',(SELECT count(*) FROM public.unified_admin_profiles p,desired d WHERE p.person_id=d.person_id AND p.account_id<>d.account_id)
              +(SELECT count(*) FROM public.pharmacist_person_links p,desired d WHERE p.person_id=d.person_id)
              +(SELECT count(*) FROM public.unified_external_account_bindings p,desired d WHERE p.person_id=d.person_id),
          'identityAuditCount',(SELECT count(*) FROM public.unified_identity_audit a,desired d WHERE a.actor_id=d.account_id
              AND a.entity_type='ADMIN_ACCOUNT' AND a.entity_id=d.account_id::text AND a.operation='BOOTSTRAP_HQ'),
          'accessAuditCount',(SELECT count(*) FROM public.unified_admin_access_audit a,desired d WHERE a.actor_id=d.account_id
              AND a.target_id=d.account_id AND a.target_version=0 AND a.operation='BOOTSTRAP_HQ')) AS facts""" % input_select


def disposition(facts):
    stage.require(facts.get("accountCount") == 1 and facts.get("accountEligible") is True, "target must be an existing active HQ account still in LEGACY mode")
    stage.require(facts.get("otherPersonBindings") == 0, "person already belongs to another identity binding")
    if facts.get("profileCount") == facts.get("personCount") == 0 and facts.get("identityAuditCount") == facts.get("accessAuditCount") == 0:
        return "create"
    if facts.get("profileCount") == facts.get("personCount") == facts.get("identityAuditCount") == facts.get("accessAuditCount") == 1 and facts.get("exactProfile") is True and facts.get("exactPerson") is True:
        return "noop"
    raise stage.StageError("existing profile, person or bootstrap audit conflicts; no merge or overwrite is allowed")


def apply_sql(payload, target):
    private_input = stage.literal(stage.encoded(payload).decode()) + "::jsonb"
    expected = {key: target[key] for key in ("database", "oid", "systemIdentifier", "versionNum")}
    input_select = "SELECT payload FROM pg_temp._bootstrap_input"
    # Untrusted JSON is inserted outside dollar quoting; it cannot close or inject a DO body.
    return "\n".join([
        "BEGIN;", stage.SETTINGS, "SELECT pg_advisory_xact_lock(817109,1);",
        "CREATE TEMP TABLE _bootstrap_input(payload jsonb,target jsonb) ON COMMIT DROP;",
        "INSERT INTO pg_temp._bootstrap_input VALUES(" + private_input + "," + stage.literal(json.dumps(expected)) + "::jsonb);",
        """DO $bootstrap$ BEGIN
          PERFORM a.id FROM public.admin_users a,pg_temp._bootstrap_input i WHERE a.id=(i.payload->>'accountId')::uuid FOR NO KEY UPDATE OF a;
          PERFORM p.account_id FROM public.unified_admin_profiles p,pg_temp._bootstrap_input i WHERE p.account_id=(i.payload->>'accountId')::uuid FOR UPDATE OF p;
        END $bootstrap$;""",
        "CREATE TEMP TABLE _bootstrap_before ON COMMIT DROP AS " + facts_sql(input_select) + ";",
        """DO $bootstrap$ DECLARE j jsonb; t jsonb; f jsonb; ready boolean; repeated boolean;
        BEGIN
          SELECT payload,target INTO j,t FROM pg_temp._bootstrap_input;
          IF current_database()<>t->>'database' OR current_database()<>'epharm_unified_candidate'
              OR current_setting('server_version_num')::integer/10000<>16
              OR current_setting('server_version_num')::integer<>(t->>'versionNum')::integer
              OR (SELECT oid FROM pg_database WHERE datname=current_database())<>(t->>'oid')::oid
              OR (SELECT system_identifier::text FROM pg_control_system())<>t->>'systemIdentifier' THEN
            RAISE EXCEPTION 'candidate identity changed' USING ERRCODE='P0001';
          END IF;
          SELECT facts INTO f FROM pg_temp._bootstrap_before;
          IF (f->>'accountCount')::integer<>1 OR NOT (f->>'accountEligible')::boolean OR (f->>'otherPersonBindings')::integer<>0 THEN
            RAISE EXCEPTION 'bootstrap target or person is not eligible' USING ERRCODE='P0001';
          END IF;
          ready := (f->>'profileCount')::integer=0 AND (f->>'personCount')::integer=0
              AND (f->>'identityAuditCount')::integer=0 AND (f->>'accessAuditCount')::integer=0;
          repeated := (f->>'profileCount')::integer=1 AND (f->>'personCount')::integer=1
              AND (f->>'identityAuditCount')::integer=1 AND (f->>'accessAuditCount')::integer=1
              AND (f->>'exactProfile')::boolean AND (f->>'exactPerson')::boolean;
          IF NOT ready AND NOT repeated THEN RAISE EXCEPTION 'bootstrap state conflicts' USING ERRCODE='P0001'; END IF;
          IF ready THEN
            INSERT INTO public.unified_persons(id,verification_reference,display_name)
              VALUES((j->>'personId')::uuid,j->>'verificationReference',j->>'displayName');
            INSERT INTO public.unified_admin_profiles(account_id,person_id,job_title,version,grants_json)
              VALUES((j->>'accountId')::uuid,(j->>'personId')::uuid,j->>'jobTitle',0,(j->'grants')::text);
            INSERT INTO public.unified_identity_audit(id,actor_id,entity_type,entity_id,operation)
              VALUES(gen_random_uuid(),(j->>'accountId')::uuid,'ADMIN_ACCOUNT',j->>'accountId','BOOTSTRAP_HQ');
            INSERT INTO public.unified_admin_access_audit(id,actor_id,target_id,operation,target_version)
              VALUES(gen_random_uuid(),(j->>'accountId')::uuid,(j->>'accountId')::uuid,'BOOTSTRAP_HQ',0);
          END IF;
        END $bootstrap$;""",
        "CREATE TEMP TABLE _bootstrap_after ON COMMIT DROP AS " + facts_sql(input_select) + ";",
        """DO $bootstrap$ DECLARE b jsonb; a jsonb;
        BEGIN
          SELECT facts INTO b FROM pg_temp._bootstrap_before; SELECT facts INTO a FROM pg_temp._bootstrap_after;
          IF a->>'adminRowSha256' IS DISTINCT FROM b->>'adminRowSha256' OR NOT (a->>'accountEligible')::boolean
              OR (a->>'profileCount')::integer<>1 OR (a->>'personCount')::integer<>1
              OR NOT (a->>'exactProfile')::boolean OR NOT (a->>'exactPerson')::boolean
              OR (a->>'otherPersonBindings')::integer<>0 OR (a->>'identityAuditCount')::integer<>1 OR (a->>'accessAuditCount')::integer<>1 THEN
            RAISE EXCEPTION 'bootstrap verification failed' USING ERRCODE='P0001';
          END IF;
        END $bootstrap$;""",
        "SELECT jsonb_build_object('created',(SELECT (facts->>'profileCount')::integer=0 FROM pg_temp._bootstrap_before),'facts',(SELECT facts FROM pg_temp._bootstrap_after));",
        "COMMIT;",
    ])


def report_file(path):
    parent = path.parent.resolve(strict=True)
    info = parent.stat()
    stage.require(parent.is_dir() and info.st_uid == os.getuid() and stat.S_IMODE(info.st_mode) == 0o700,
                  "report parent must be a caller-owned private 0700 directory")
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0)
    return os.fdopen(os.open(path, flags, 0o600), "wb")


def run(args, database_factory=stage.Database):
    stage.require(re.fullmatch(r"epharm-(?:unify-candidate|stage-test-bootstrap)-[a-zA-Z0-9_.-]+", args.container), "only dedicated candidate or disposable bootstrap containers are allowed")
    stage.require(args.database == DATABASE, "only the designated candidate database is allowed")
    raw = read_file(args.input, private=True)
    routes_raw = read_file(args.routes)
    plan_sha, routes_sha = stage.digest(raw), stage.digest(routes_raw)
    stage.require(plan_sha == stage.hash_arg(args.plan_sha256), "identity plan checksum differs from the approved pin")
    stage.require(routes_sha == stage.hash_arg(args.routes_sha256), "route catalog checksum differs from the approved pin")
    credential_export = getattr(args, "include_credential_export", False)
    allowed = permission_catalog(stage.parse_json(routes_raw), credential_export)
    payload = validate_payload(stage.parse_json(raw), allowed, credential_export)
    target_sha = stage.hash_arg(args.target_sha256)
    stage.require(not args.apply or args.report is not None, "--apply requires a new private report path")
    output = report_file(args.report) if args.report else None
    summary = {"schemaVersion": 1, "planSha256": plan_sha, "routesSha256": routes_sha, "targetIdentitySha256": target_sha,
               "grantCount": len(payload["grants"]), "allowedPermissionCount": len(allowed), "productionChanged": False,
               "primaryOwnerConfigurationChanged": False, "applicationRuntimeVerified": False,
               "credentialExportExplicitlyApproved": credential_export and any(permission_key(g) == CREDENTIAL_EXPORT for g in payload["grants"])}
    try:
        db = database_factory(args.container, args.user, args.database)
        target = db.inspect()
        stage.require(target["identitySha256"] == target_sha, "candidate identity pin differs")
        stage.require(target["database"] == DATABASE and target["versionNum"] // 10000 == 16 and target["sha256Available"] is True, "an isolated PG16 candidate with core sha256 is required")
        if args.apply:
            result = stage.parse_json([line for line in db.run(apply_sql(payload, target)).splitlines() if line.startswith('{')][-1])
            facts = result["facts"]
            stage.require(disposition(facts) == "noop", "post-bootstrap state is not verified")
            summary.update(mode="created_and_verified" if result["created"] else "verified_existing_noop", personsAdded=int(result["created"]),
                           profilesAdded=int(result["created"]), auditsAdded=2 * int(result["created"]))
        else:
            facts = stage.parse_json(db.readonly(facts_sql("SELECT " + stage.literal(stage.encoded(payload).decode()) + "::jsonb AS payload") + ";"))
            mode = disposition(facts)
            summary.update(mode="planned_create" if mode == "create" else "planned_noop", personsAdded=0, profilesAdded=0, auditsAdded=0)
        summary.update(adminRowSha256=facts["adminRowSha256"], activeHqChecked=True, legacyAccessModePreserved=True)
        if output:
            output.write(stage.encoded(summary)); output.flush(); os.fsync(output.fileno())
        return summary
    except Exception:
        if output:
            output.write(stage.encoded(dict(summary, mode="refused_or_outcome_unknown_verify_before_retry"))); output.flush()
        raise
    finally:
        if output:
            output.close()


def parser():
    p = argparse.ArgumentParser(description=__doc__, epilog="Default is --plan (database READ ONLY). --apply requires the exact approved input/routes/target pins and a new private report. This never sets app.unified-admin.primary-owner-id; that needs a separate operator decision.")
    for name in ("container", "user", "target-sha256", "plan-sha256", "routes-sha256"):
        p.add_argument("--" + name, required=True)
    p.add_argument("--database", default=DATABASE)
    p.add_argument("--input", type=Path, required=True)
    p.add_argument("--routes", type=Path, required=True)
    p.add_argument("--report", type=Path)
    p.add_argument("--include-credential-export", action="store_true",
                   help="Allow only the explicit D9 grant in the pinned HQ input; never adds a grant implicitly")
    mode = p.add_mutually_exclusive_group(); mode.add_argument("--plan", action="store_true"); mode.add_argument("--apply", action="store_true")
    return p


def main(argv=None):
    try:
        print(json.dumps(run(parser().parse_args(argv)), sort_keys=True))
        return 0
    except stage.StageError as error:
        print("Bootstrap refused: " + str(error), file=sys.stderr)
    except (OSError, ValueError, KeyError, TypeError, IndexError):
        print("Bootstrap refused: invalid input or inaccessible private artifact; details suppressed", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
