#!/usr/bin/env python3
"""Build an offline preservation map from restored-source IDs; never import into a database."""

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import sys


FIELDS = {
    "branches": {"id", "organizationId", "sourceKey", "code", "sourceRows", "active", "division"},
    "pharmacies": {"id"}, "organizations": {"id"}, "accounts": {"id"},
    "snapshots": {"id", "sourceSha256", "targetCount"},
    "staging": {"snapshotId", "pharmacyId"},
    "crosswalk": {"branchId", "pharmacyId", "matchMethod", "snapshotId"},
    "containers": {"id", "branchId"}, "shelves": {"id", "branchId", "containerId"},
    "monthlyInventory": {"id", "shelfId"}, "accessCodes": {"id", "organizationId"},
    "accessScopes": {"codeId", "branchId"}, "memberScopes": {"accountId", "branchId"},
    "directBranchRecords": {"entityType", "id", "branchId"},
}
COMPOSITE_KEYS = {"staging": ("snapshotId", "pharmacyId"), "crosswalk": ("branchId",),
                  "accessScopes": ("codeId", "branchId"), "memberScopes": ("accountId", "branchId"),
                  "directBranchRecords": ("entityType", "id")}
DIRECT_TYPES = {"branch_passport_photos", "pharmapay_product_balances", "pharmapay_recommendation_signals",
                "staff_task_dispatches", "staff_task_kiosk_devices", "staff_tasks",
                "telegram_branch_invites", "telegram_chat_bindings", "visit_reports"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def opaque(value):
    return isinstance(value, str) and bool(value.strip()) and not re.search(r"[\x00-\x1f\x7f-\x9f]", value)


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n").encode()


def sha(value):
    return hashlib.sha256(value).hexdigest()


def unique_pairs(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, f"duplicate JSON key {key!r}")
        result[key] = value
    return result


def invalid_constant(token):
    raise ValueError(f"invalid JSON constant {token}")


def load(path):
    data = path.read_bytes()
    value = json.loads(data, object_pairs_hook=unique_pairs, parse_constant=invalid_constant)
    require(isinstance(value, dict), f"{path.name}: expected a JSON object")
    return value, sha(data)


def index_rows(source):
    require(set(source) == set(FIELDS) | {"schemaVersion", "provenance"}, "source: unsupported or missing table/field")
    require(type(source["schemaVersion"]) is int and source["schemaVersion"] == 1, "source: unsupported schemaVersion")
    provenance = source["provenance"]
    require(isinstance(provenance, dict) and set(provenance) == {"mode", "crmRevision", "capturedAt"},
            "source: missing provenance")
    require(provenance["mode"] == "restored_local_read_only" and opaque(provenance["capturedAt"])
            and isinstance(provenance["crmRevision"], str) and re.fullmatch(r"[0-9a-f]{40}", provenance["crmRevision"]),
            "source: invalid restored-source provenance")
    indexes = {}
    for table, fields in FIELDS.items():
        require(isinstance(source[table], list), f"{table}: expected an array")
        index = {}
        for row in source[table]:
            require(isinstance(row, dict) and set(row) == fields, f"{table}: unsupported or missing row fields")
            for field, value in row.items():
                if field == "sourceRows":
                    require(isinstance(value, list) and all(opaque(item) for item in value), "branches: invalid sourceRows")
                elif field == "active":
                    require(type(value) is bool, "branches: active must be boolean")
                elif field == "targetCount":
                    require(type(value) is int and value > 0, "snapshots: invalid targetCount")
                elif not (field in {"containerId", "division"} and value is None):
                    require(opaque(value), f"{table}.{field}: expected a nonblank opaque string")
            key = tuple(row[field] for field in COMPOSITE_KEYS.get(table, ("id",)))
            require(key not in index, f"{table}: duplicate source identity {key!r}")
            index[key] = row
        indexes[table] = index
    return indexes


def build_map(analysis, source, analysis_sha, source_sha):
    indexes = index_rows(source)
    require(analysis["source"]["crmRevision"] == source["provenance"]["crmRevision"],
            "analysis: CRM source revision differs from restored-source provenance")

    def target(table, value, label):
        require((value,) in indexes[table], f"{label}: missing dependent target {table}/{value!r}")
        return indexes[table][(value,)]

    references = {
        "branches": {"organizationId": "organizations"}, "containers": {"branchId": "branches"},
        "shelves": {"branchId": "branches", "containerId": "containers"},
        "monthlyInventory": {"shelfId": "shelves"}, "accessCodes": {"organizationId": "organizations"},
        "accessScopes": {"codeId": "accessCodes", "branchId": "branches"},
        "memberScopes": {"accountId": "accounts", "branchId": "branches"},
        "directBranchRecords": {"branchId": "branches"},
        "crosswalk": {"branchId": "branches", "pharmacyId": "pharmacies", "snapshotId": "snapshots"},
        "staging": {"snapshotId": "snapshots", "pharmacyId": "pharmacies"},
    }
    for table, fields in references.items():
        for row in source[table]:
            for field, destination in fields.items():
                if row[field] is not None:
                    target(destination, row[field], f"{table}.{field}")
    for row in source["shelves"]:
        if row["containerId"] is not None:
            require(target("containers", row["containerId"], "shelf container")["branchId"] == row["branchId"],
                    "shelves: container belongs to a different branch")
    for row in source["directBranchRecords"]:
        require(row["entityType"] in DIRECT_TYPES, "directBranchRecords: unsupported entity type")
    for row in source["crosswalk"]:
        require(row["matchMethod"] in {"created", "high_confidence"}, "crosswalk: unsupported match method")
    pharmacy_ids = {row["id"] for row in source["pharmacies"]}
    linked_ids = [row["pharmacyId"] for row in source["crosswalk"]]
    require(len(set(linked_ids)) == len(linked_ids), "crosswalk: conflicting canonical pharmacy alias")
    require(set(linked_ids) == pharmacy_ids, "crosswalk: pharmacy endpoint coverage differs from restored source")
    for snapshot in source["snapshots"]:
        require(re.fullmatch(r"[0-9a-f]{64}", snapshot["sourceSha256"]), "snapshot: invalid source hash")
        require(sum(row["snapshotId"] == snapshot["id"] for row in source["staging"]) == snapshot["targetCount"],
                "snapshot: staging coverage differs from original target count")
    staged = {(r["snapshotId"], r["pharmacyId"]) for r in source["staging"]}
    supplements = [r for r in source["crosswalk"] if (r["snapshotId"], r["pharmacyId"]) not in staged]
    expected_supplements = analysis["existingCrosswalkProvenance"]["currentCrosswalkWithoutMatchingStaging"]
    require(isinstance(expected_supplements, list), "analysis: missing supplement provenance")
    require(all(isinstance(r, dict) and set(r) == {"branchId", "externalLocationId", "lastSnapshotId"}
                for r in expected_supplements), "analysis: unsupported supplement provenance")
    expected = {(r["branchId"], r["externalLocationId"], r["lastSnapshotId"]) for r in expected_supplements}
    require(len(expected) == len(expected_supplements), "analysis: duplicate supplement provenance")
    require(expected == {(r["branchId"], r["pharmacyId"], r["snapshotId"]) for r in supplements},
            "crosswalk: missing or conflicting 530+supplement provenance")

    analysis_rows = analysis["idMap"]
    require(isinstance(analysis_rows, list), "analysis.idMap: expected an array")
    reviewed = {}
    for row in analysis_rows:
        identity = row["source"]
        require(isinstance(identity, dict) and set(identity) == {"system", "entityType", "originalId"},
                "analysis: unsupported source identity")
        require(identity["system"] == "MERCH_CRM" and identity["entityType"] == "BRANCH", "analysis: wrong source namespace")
        raw_id = identity["originalId"]
        require(opaque(raw_id) and raw_id not in reviewed, "analysis: duplicate or invalid source identity")
        require(row["state"] == "source_confirmed", "analysis: invalid state; runtime acceptance is not proven")
        reviewed[raw_id] = row
    require(set(reviewed) == {r["id"] for r in source["branches"]}, "analysis: branch identity coverage differs from source")
    unmapped = set(reviewed) - {r["branchId"] for r in source["crosswalk"]}
    expected_counts = {"crmBranches": len(reviewed), "crosswalkPairs": len(source["crosswalk"]),
                       "crmOnlyBranches": len(unmapped), "epharmPharmacies": len(pharmacy_ids)}
    for key, count in expected_counts.items():
        require(type(analysis["assertions"][key]) is int and analysis["assertions"][key] == count,
                f"analysis: {key} count differs from complete source identities")
    dependency_counts = {name: Counter() for name in ("shelves", "containers", "monthlyInventory", "accessCodeScopes")}
    for table, name in (("shelves", "shelves"), ("containers", "containers"), ("accessScopes", "accessCodeScopes")):
        dependency_counts[name].update(row["branchId"] for row in source[table])
    dependency_counts["monthlyInventory"].update(target("shelves", row["shelfId"], "monthlyInventory")["branchId"]
                                               for row in source["monthlyInventory"])
    mapped_branches = []
    for branch in sorted(source["branches"], key=lambda r: r["id"]):
        raw_id = branch["id"]
        reviewed_row = reviewed[raw_id]
        require(reviewed_row["preserveMerchBranchId"] == raw_id, "analysis: branch ID replacement is forbidden")
        for field, original in (("legacySourceKey", "sourceKey"), ("legacyCode", "code"),
                                ("organizationSourceId", "organizationId"), ("division", "division"), ("sourceActive", "active")):
            require(type(reviewed_row[field]) is type(branch[original]) and reviewed_row[field] == branch[original],
                    f"analysis: raw branch {original} differs from source")
        link = indexes["crosswalk"].get((raw_id,))
        pharmacy = {"entityType": "PHARMACY", "id": link["pharmacyId"]} if link else None
        require(reviewed_row["canonicalPharmacy"] == pharmacy, "analysis: conflicting optional pharmacy relation")
        if link:
            snapshot = target("snapshots", link["snapshotId"], "crosswalk")
            provenance = {"snapshotId": snapshot["id"], "snapshotSourceSha256": snapshot["sourceSha256"],
                          "kind": "snapshot_staging" if (link["snapshotId"], link["pharmacyId"]) in staged else "current_crosswalk_supplement",
                          "crosswalkRecordSha256": sha(encoded(link)), "sourceArtifactSha256": source_sha}
        else:
            require(branch["active"] is False, "analysis: unresolved branch unexpectedly active")
            require(reviewed_row["preserveCounts"] == {k: c[raw_id] for k, c in dependency_counts.items()},
                    "analysis: missing or changed dependent source records on unresolved branch")
            provenance = None
        mapped_branches.append({"source": {"system": "MERCH_CRM", "entityType": "BRANCH", "originalId": raw_id},
                                "canonical": {"entityType": "MERCH_BRANCH", "id": raw_id},
                                "legacyAttributes": {k: v for k, v in branch.items() if k != "id"},
                                "pharmacyRelation": pharmacy, "pharmacyRelationProvenance": provenance})

    preserved = {table: sorted(source[table], key=lambda row: tuple(row[k] for k in COMPOSITE_KEYS.get(table, ("id",))))
                 for table in FIELDS if table not in {"branches", "crosswalk", "staging", "snapshots", "pharmacies"}}
    complete_map = {"schemaVersion": 1, "sourceSystem": "MERCH_CRM", "state": "source_confirmed",
                    "analysisArtifactSha256": analysis_sha, "sourceArtifactSha256": source_sha,
                    "sourceProvenance": source["provenance"], "branches": mapped_branches,
                    "pharmacyTargets": sorted(pharmacy_ids), "sourceSnapshots": sorted(source["snapshots"], key=lambda r: r["id"]),
                    "sourceStaging": sorted(source["staging"], key=lambda r: (r["snapshotId"], r["pharmacyId"])),
                    "sourceCrosswalk": sorted(source["crosswalk"], key=lambda r: r["branchId"]),
                    "preservedSourceRecords": preserved,
                    "accountBoundary": "Source account IDs and scopes are retained; canonical account/person relations are unresolved.",
                    "authorizationBoundary": "Identity resolution grants no rights and never reactivates a branch or expired access code."}
    summary = {"schemaVersion": 1, "state": "source_confirmed", "runtimeAccepted": False,
               "analysisArtifactSha256": analysis_sha, "sourceArtifactSha256": source_sha,
               "completeMapSha256": sha(encoded(complete_map)), "crmRevision": source["provenance"]["crmRevision"],
               "counts": {"merchBranches": len(mapped_branches), "pharmacyRelations": len(linked_ids),
                          "stagedPharmacyRelations": len(linked_ids) - len(supplements), "supplementPharmacyRelations": len(supplements),
                          "unresolvedPharmacyRelations": len(unmapped), "retainedRecords": {k: len(v) for k, v in preserved.items()}},
               "sourceSnapshotProvenance": [{"sourceSha256": r["sourceSha256"], "targetCount": r["targetCount"]} for r in source["snapshots"]],
               "supplementRecordSha256": sorted(sha(encoded(row)) for row in supplements),
               "validated": ["Complete source branch ID set, exact raw IDs and namespaces", "All optional pharmacy endpoints and one-to-one conflicts",
                             "Organization, branch, shelf, container, monthly inventory, account and access-code dependency targets",
                             "Unresolved-branch dependent record counts against prior analysis", "Historical staging and current crosswalk supplement provenance"],
               "boundary": "Offline preservation evidence only. No database import, activation, authorization acceptance or human identity merge."}
    return complete_map, summary


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ("analysis", "source-records", "private-map", "report"):
        parser.add_argument("--" + option, required=True, type=Path)
    args = parser.parse_args(argv)
    try:
        paths = [p.resolve() for p in (args.analysis, args.source_records, args.private_map, args.report)]
        require(len(set(paths)) == 4, "input and output paths must be distinct")
        analysis, analysis_sha = load(args.analysis)
        source, source_sha = load(args.source_records)
        complete_map, summary = build_map(analysis, source, analysis_sha, source_sha)
        for path, value in ((args.private_map, complete_map), (args.report, summary)):
            data = encoded(value) if path == args.private_map else (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode()
            path.write_bytes(data)
            path.chmod(0o600)
        print(json.dumps({"counts": summary["counts"], "completeMapSha256": summary["completeMapSha256"]}, sort_keys=True))
        return 0
    except (OSError, ValueError, KeyError, TypeError, RuntimeError) as error:
        print(f"identity map: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
