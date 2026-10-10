import importlib.util
import pathlib
import unittest


SCRIPT = pathlib.Path(__file__).resolve().parents[1] / "prepare-acc-recommendation-catalog.py"
SPEC = importlib.util.spec_from_file_location("prepare_acc_catalog", SCRIPT)
assert SPEC and SPEC.loader
catalog = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(catalog)


def row(ware: str, barcode: str, group: str = "", subgroup: str = "", mnn: str = ""):
    values = [None] * 21
    values[0], values[3], values[15], values[19], values[20] = (
        ware, barcode, mnn, group, subgroup,
    )
    return values


class AccCatalogPreparationTests(unittest.TestCase):
    def test_scope_keys_normalize_case_space_and_unicode(self):
        self.assertEqual(
            catalog.scope_key("group", "АНТИСЕПТИКИ"),
            catalog.scope_key("group", "  антисептики  "),
        )
        self.assertEqual(catalog.clean_label("Этанол_x000D_"), "Этанол")
        self.assertNotEqual(
            catalog.scope_key("subgroup", "Витамины", "Дети"),
            catalog.scope_key("subgroup", "Витамины", "Взрослые"),
        )

    def test_multibarcode_and_missing_scopes(self):
        records, stats = catalog.prepare_rows(
            [
                row("WARE-1", "3856013216955_3856013235871_x000D_", "АНТИСЕПТИКИ", "Спирт", "Этанол_x000D_"),
                row("WARE-2", "12345678", "НЕ ПРИВЯЗАНО", "Без группы", "0"),
                row("WARE-3", "7777777", "АНТИСЕПТИКИ", "Спирт", "Этанол"),
            ],
            "00000000-0000-0000-0000-000000000001",
        )
        self.assertEqual(len(records), 3)
        self.assertEqual(stats["no_valid_barcode"], 1)
        self.assertEqual(stats["subgroup_without_parent"], 1)
        self.assertIsNone(records[0][5])
        self.assertEqual(records[0][7:], (None, None))
        self.assertEqual(next(record for record in records if record[2] == "3856013216955")[8], "Этанол")

    def test_shared_barcode_is_kept_but_reported_ambiguous(self):
        records, stats = catalog.prepare_rows(
            [row("WARE-1", "4601234567890", "Группа А"), row("WARE-2", "4601234567890", "Группа Б")],
            "00000000-0000-0000-0000-000000000001",
        )
        self.assertEqual(len(records), 2)
        self.assertEqual(stats["ambiguous_barcodes"], 1)
        self.assertEqual(stats["unambiguous_barcodes"], 0)

    def test_conflicting_same_ware_barcode_aborts_import(self):
        with self.assertRaisesRegex(ValueError, "Conflicting classification"):
            catalog.prepare_rows(
                [row("WARE-1", "4601234567890", "Группа А"), row("WARE-1", "4601234567890", "Группа Б")],
                "00000000-0000-0000-0000-000000000001",
            )

    def test_invalid_or_blank_ware_id_and_barcode_are_not_imported(self):
        records, stats = catalog.prepare_rows(
            [row("", "4601234567890", "Группа"), row("WARE-2", "12345", "Группа")],
            "00000000-0000-0000-0000-000000000001",
        )
        self.assertEqual(records, [])
        self.assertEqual(stats["missing_or_invalid_ware_id"], 1)
        self.assertEqual(stats["no_valid_barcode"], 1)


if __name__ == "__main__":
    unittest.main()
