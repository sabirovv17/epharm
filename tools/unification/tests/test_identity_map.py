import copy
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "build_identity_map.py"


def fixture():
    branches = [{"id": raw_id, "organizationId": "shared-id", "sourceKey": code, "code": code,
                 "sourceRows": [f"Д1!{number}"], "active": number < 5, "division": "Д1"}
                for number, (raw_id, code) in enumerate((("Branch", "001"), ("branch", "002"), (" 0007 ", "003")), 3)]
    source = {
        "schemaVersion": 1,
        "provenance": {"mode": "restored_local_read_only", "crmRevision": "a" * 40, "capturedAt": "2026-10-29T00:00:00Z"},
        "branches": branches, "pharmacies": [{"id": "Branch"}, {"id": "pharmacy-B"}],
        "organizations": [{"id": "shared-id"}], "accounts": [{"id": "Branch"}],
        "snapshots": [{"id": "snapshot", "sourceSha256": "b" * 64, "targetCount": 1}],
        "staging": [{"snapshotId": "snapshot", "pharmacyId": "Branch"}],
        "crosswalk": [{"branchId": "Branch", "pharmacyId": "Branch", "matchMethod": "high_confidence", "snapshotId": "snapshot"},
                      {"branchId": "branch", "pharmacyId": "pharmacy-B", "matchMethod": "created", "snapshotId": "snapshot"}],
        "containers": [{"id": "shared-id", "branchId": " 0007 "}],
        "shelves": [{"id": "shared-id", "branchId": " 0007 ", "containerId": "shared-id"}],
        "monthlyInventory": [{"id": "month-row", "shelfId": "shared-id"}],
        "accessCodes": [{"id": "code-id", "organizationId": "shared-id"}],
        "accessScopes": [{"codeId": "code-id", "branchId": " 0007 "}],
        "memberScopes": [{"accountId": "Branch", "branchId": "Branch"}],
        "directBranchRecords": [{"entityType": "staff_tasks", "id": "task-id", "branchId": "Branch"}],
    }
    analysis = {"source": {"crmRevision": "a" * 40},
                "assertions": {"crmBranches": 3, "crosswalkPairs": 2, "crmOnlyBranches": 1, "epharmPharmacies": 2},
                "existingCrosswalkProvenance": {"currentCrosswalkWithoutMatchingStaging": [
                    {"branchId": "branch", "externalLocationId": "pharmacy-B", "lastSnapshotId": "snapshot"}]}, "idMap": []}
    for branch in branches:
        link = next((r for r in source["crosswalk"] if r["branchId"] == branch["id"]), None)
        row = {"source": {"system": "MERCH_CRM", "entityType": "BRANCH", "originalId": branch["id"]},
               "preserveMerchBranchId": branch["id"], "legacySourceKey": branch["sourceKey"], "legacyCode": branch["code"],
               "organizationSourceId": branch["organizationId"], "division": branch["division"], "sourceActive": branch["active"],
               "canonicalPharmacy": {"entityType": "PHARMACY", "id": link["pharmacyId"]} if link else None,
               "state": "source_confirmed"}
        if not link:
            row["preserveCounts"] = {"shelves": 1, "containers": 1, "monthlyInventory": 1, "accessCodeScopes": 1}
        analysis["idMap"].append(row)
    return analysis, source


class IdentityMapCliTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.analysis, self.source = fixture()

    def run_cli(self, expected_error=None):
        for name, value in (("analysis", self.analysis), ("source", self.source)):
            (self.root / f"{name}.json").write_text(json.dumps(value), encoding="utf-8")
        result = subprocess.run([sys.executable, str(SCRIPT), "--analysis", str(self.root / "analysis.json"),
                                 "--source-records", str(self.root / "source.json"), "--private-map", str(self.root / "map.json"),
                                 "--report", str(self.root / "report.json")], text=True, capture_output=True)
        self.assertNotIn("Traceback", result.stderr)
        if expected_error:
            self.assertNotEqual(0, result.returncode)
            self.assertIn(expected_error, result.stderr)
            self.assertFalse((self.root / "map.json").exists())
            self.assertFalse((self.root / "report.json").exists())
            return
        self.assertEqual(0, result.returncode, result.stderr)
        return json.loads((self.root / "map.json").read_text()), json.loads((self.root / "report.json").read_text())

    def test_complete_map_retains_namespaces_raw_ids_dependencies_and_supplements(self):
        complete, report = self.run_cli()
        rows = {r["source"]["originalId"]: r for r in complete["branches"]}
        self.assertEqual({"Branch", "branch", " 0007 "}, set(rows))
        for raw_id, row in rows.items():
            self.assertEqual({"entityType": "MERCH_BRANCH", "id": raw_id}, row["canonical"])
        self.assertIsNone(rows[" 0007 "]["pharmacyRelation"])
        self.assertEqual({"entityType": "PHARMACY", "id": "Branch"}, rows["Branch"]["pharmacyRelation"])
        self.assertEqual("current_crosswalk_supplement", rows["branch"]["pharmacyRelationProvenance"]["kind"])
        self.assertEqual("snapshot_staging", rows["Branch"]["pharmacyRelationProvenance"]["kind"])
        self.assertEqual(self.source["shelves"], complete["preservedSourceRecords"]["shelves"])
        self.assertEqual(self.source["accounts"], complete["preservedSourceRecords"]["accounts"])
        self.assertFalse(report["runtimeAccepted"])
        self.assertEqual(1, len(report["supplementRecordSha256"]))
        self.assertEqual(1, report["counts"]["stagedPharmacyRelations"])
        self.assertEqual(1, report["counts"]["supplementPharmacyRelations"])
        self.assertEqual(hashlib.sha256((self.root / "map.json").read_bytes()).hexdigest(), report["completeMapSha256"])
        first = (self.root / "map.json").read_bytes(), (self.root / "report.json").read_bytes()
        self.run_cli()
        self.assertEqual(first, ((self.root / "map.json").read_bytes(), (self.root / "report.json").read_bytes()))
        self.assertEqual(0o600, (self.root / "map.json").stat().st_mode & 0o777)

    def test_removed_branch_is_not_hidden_by_matching_aggregate_counts(self):
        self.source["branches"][2]["id"] = "replacement-id"
        self.run_cli("missing dependent target")

    def test_missing_branch_without_dependents_still_fails_set_comparison(self):
        self.source["branches"][1]["id"] = "replacement-id"
        self.source["crosswalk"][1]["branchId"] = "replacement-id"
        self.analysis["existingCrosswalkProvenance"]["currentCrosswalkWithoutMatchingStaging"][0]["branchId"] = "replacement-id"
        self.run_cli("branch identity coverage differs")

    def test_duplicate_identity_fails(self):
        self.source["branches"].append(copy.deepcopy(self.source["branches"][0]))
        self.run_cli("duplicate source identity")

    def test_same_branch_cannot_have_two_pharmacy_relations(self):
        self.source["crosswalk"][1]["branchId"] = "Branch"
        self.run_cli("duplicate source identity")

    def test_two_branches_cannot_collapse_to_one_pharmacy(self):
        self.source["crosswalk"][1]["pharmacyId"] = "Branch"
        self.run_cli("conflicting canonical pharmacy alias")

    def test_missing_pharmacy_target_fails(self):
        self.source["crosswalk"][0]["pharmacyId"] = "not-present"
        self.run_cli("missing dependent target pharmacies")

    def test_missing_container_target_fails(self):
        self.source["containers"].clear()
        self.run_cli("missing dependent target containers")

    def test_missing_account_target_fails_without_inventing_account(self):
        self.source["accounts"].clear()
        self.run_cli("missing dependent target accounts")

    def test_container_cannot_move_shelf_between_branches(self):
        self.source["containers"][0]["branchId"] = "Branch"
        self.run_cli("container belongs to a different branch")

    def test_removed_inventory_record_fails_prior_evidence(self):
        self.source["monthlyInventory"].clear()
        self.run_cli("missing or changed dependent source records")

    def test_wrong_namespace_does_not_match_same_opaque_id(self):
        self.analysis["idMap"][0]["source"]["system"] = "EPHARM"
        self.run_cli("wrong source namespace")

    def test_forged_optional_pharmacy_relation_fails(self):
        self.analysis["idMap"][2]["canonicalPharmacy"] = {"entityType": "PHARMACY", "id": "Branch"}
        self.run_cli("conflicting optional pharmacy relation")

    def test_lost_supplement_provenance_fails(self):
        self.analysis["existingCrosswalkProvenance"]["currentCrosswalkWithoutMatchingStaging"].clear()
        self.run_cli("missing or conflicting 530+supplement provenance")

    def test_historical_snapshot_cannot_claim_more_staged_rows(self):
        self.source["snapshots"][0]["targetCount"] = 2
        self.run_cli("staging coverage differs")

    def test_unsupported_dependent_type_fails(self):
        self.source["directBranchRecords"][0]["entityType"] = "new_unreviewed_table"
        self.run_cli("unsupported entity type")

    def test_invalid_analysis_state_is_not_runtime_acceptance(self):
        self.analysis["idMap"][0]["state"] = "live_ready"
        self.run_cli("invalid state")

    def test_unknown_source_fields_cannot_copy_credential_material(self):
        self.source["accounts"][0]["password_hash"] = "synthetic-forbidden"
        self.run_cli("unsupported or missing row fields")

    def test_control_character_id_is_clear_error(self):
        self.source["branches"][0]["id"] += "\0"
        self.run_cli("expected a nonblank opaque string")

    def test_nullable_division_of_epharm_created_branch_is_preserved(self):
        self.source["branches"][1]["division"] = None
        self.analysis["idMap"][1]["division"] = None
        complete, _ = self.run_cli()
        row = next(r for r in complete["branches"] if r["source"]["originalId"] == "branch")
        self.assertIsNone(row["legacyAttributes"]["division"])

    def test_mismatched_source_revision_fails(self):
        self.source["provenance"]["crmRevision"] = "c" * 40
        self.run_cli("source revision differs")

    def test_boolean_is_not_a_schema_version(self):
        self.source["schemaVersion"] = True
        self.run_cli("unsupported schemaVersion")

    def test_aggregate_report_contains_no_raw_source_ids(self):
        _, report = self.run_cli()
        def strings(value):
            if isinstance(value, dict):
                return [s for item in value.values() for s in strings(item)]
            if isinstance(value, list):
                return [s for item in value for s in strings(item)]
            return [value] if isinstance(value, str) else []
        self.assertFalse({"Branch", "branch", " 0007 ", "pharmacy-B", "shared-id", "code-id"} & set(strings(report)))


if __name__ == "__main__":
    unittest.main()
