import copy
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import owned_merch as owned
import crm_stage as stage

B1 = "11111111-1111-4111-8111-111111111111"
B2 = "22222222-2222-4222-8222-222222222222"


def mapping():
    def branch(identifier, pharmacy):
        return {"source": {"system": "MERCH_CRM", "entityType": "BRANCH", "originalId": identifier},
                "canonical": {"entityType": "MERCH_BRANCH", "id": identifier},
                "pharmacyRelation": {"entityType": "PHARMACY", "id": pharmacy} if pharmacy else None,
                "pharmacyRelationProvenance": {"kind": "verified_crosswalk"} if pharmacy else None}
    return {"schemaVersion": 1, "state": "source_confirmed", "branches": [branch(B1, "opaque-pharmacy-1"), branch(B2, None)],
            "sourceCrosswalk": [{"branchId": B1, "pharmacyId": "opaque-pharmacy-1"}]}


class OwnedMerchTest(unittest.TestCase):
    def test_registered_branch_and_unresolved_branch_are_both_preserved(self):
        result = owned.identity_bindings(mapping())
        self.assertEqual(result, [{"id": B1, "pharmacy_id": "opaque-pharmacy-1"}, {"id": B2, "pharmacy_id": None}])

    def test_type_confusion_and_guessed_relation_are_rejected(self):
        for change in ["canonical", "source", "relation", "provenance"]:
            value = mapping()
            if change == "canonical": value["branches"][0]["canonical"]["entityType"] = "PHARMACY"
            if change == "source": value["branches"][0]["source"]["system"] = "EPHARM"
            if change == "relation": value["branches"][0]["pharmacyRelation"]["id"] = "guessed-by-address"
            if change == "provenance": value["branches"][0]["pharmacyRelationProvenance"] = None
            with self.subTest(change=change), self.assertRaises(stage.StageError): owned.identity_bindings(value)

    def test_duplicate_branch_and_pharmacy_alias_fail_closed(self):
        value = mapping(); value["branches"].append(copy.deepcopy(value["branches"][0]))
        with self.assertRaises(stage.StageError): owned.identity_bindings(value)
        value = mapping(); value["branches"][1]["pharmacyRelation"] = copy.deepcopy(value["branches"][0]["pharmacyRelation"])
        value["branches"][1]["pharmacyRelationProvenance"] = {"kind": "verified_crosswalk"}
        value["sourceCrosswalk"].append({"branchId": B2, "pharmacyId": "opaque-pharmacy-1"})
        with self.assertRaises(stage.StageError): owned.identity_bindings(value)

    def test_sql_is_pinned_transactional_and_never_replays_source_workers_or_money(self):
        sql = owned.import_sql("crm_stage_" + "a" * 16, owned.identity_bindings(mapping()), "a" * 64, "b" * 64,
                               {key: 0 for key in owned.FIELDS}, "DO $v$ BEGIN NULL; END $v$;")
        self.assertTrue(sql.startswith("BEGIN ISOLATION LEVEL REPEATABLE READ;"))
        self.assertTrue(sql.endswith("COMMIT;"))
        self.assertIn("pg_advisory_xact_lock", sql)
        self.assertIn("to_jsonb(s)", sql)
        self.assertIn("s.blocked_by,NULL::uuid", sql)
        self.assertIn("verified_existing_noop", sql)
        self.assertIn("source crosswalk", sql)
        self.assertNotIn("UPDATE ", sql)
        self.assertNotIn("DELETE ", sql)
        self.assertNotIn("TRUNCATE ", sql)
        self.assertNotIn("INSERT INTO public.pharmacist_wallet", sql)
        self.assertNotIn("api.expire", sql)
        self.assertLess(sql.index("DO $v$"), sql.index("INSERT INTO public.\"merch_organizations\""))

    def test_external_or_injected_schema_is_refused_before_building_sql(self):
        with self.assertRaises(stage.StageError):
            owned.import_sql('public; DROP TABLE users', [], "a" * 64, "b" * 64, {})


if __name__ == "__main__": unittest.main()
