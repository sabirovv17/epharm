import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "check_inventory.py"


class InventoryCliTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.inventory = []
        for prefix, end in (("H", 17), ("L", 20), ("M", 20)):
            for number in range(1, end + 1):
                group = f"{prefix}{number:02}"
                self.inventory.append({
                    "id": group + ".01", "group": group, "name": "Existing action",
                    "source": {"repo": "epharm", "revision": "baseline", "ui": ["src/view.py"]},
                    "target": {"section": "Workspace", "subsection": "Queue", "actions": ["open"], "scope": "assigned pharmacy"},
                    "state": "source_confirmed", "acceptance": ["Existing action remains available"],
                })
        self.contracts = {
            "scope": {"publicOrigin": "https://epharm.inkar.kz", "mandatoryRpsWindowAllowed": False},
            "contracts": [{
                "id": "POSM_RECOMMEND", "method": "POST", "path": "/api/posm/recommend",
                "auth": {"headers": ["X-Posm-Key"]}, "identity": "device and pharmacy",
                "request": {"server": "RecommendRequest"}, "response": {"server": "RecommendResponse"},
                "errors": [], "sideEffects": [], "requiredInvariant": ["Preserve scoped identity"],
                "sourcePaths": [{"path": "src/view.py", "line": 1}],
                "positiveChecks": ["Bound device succeeds"], "negativeChecks": ["Foreign pharmacy denied"],
                "state": "source_confirmed",
            }],
        }

    def run_cli(self, *extra, split=False):
        paths = []
        arrays = [self.inventory[:17], self.inventory[17:]] if split else [self.inventory]
        for index, array in enumerate(arrays):
            path = self.directory / f"inventory-{index}.json"
            path.write_text(json.dumps(array), encoding="utf-8")
            paths.append(str(path))
        contracts = self.directory / "contracts.json"
        contracts.write_text(json.dumps(self.contracts), encoding="utf-8")
        return subprocess.run([sys.executable, "-B", str(SCRIPT), "--inventory", *paths,
                               "--contracts", str(contracts), *map(str, extra)],
                              text=True, capture_output=True, check=False)

    def assert_rejected(self, expected):
        result = self.run_cli()
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn(expected, result.stderr)
        self.assertNotIn("guard passed", result.stdout)
        self.assertNotIn("Traceback", result.stderr)

    def backend_fixture(self, mappings='@GetMapping("/nonfile") fun get() = "ok"'):
        source = self.directory / "src" / "view.py"
        source.parent.mkdir(exist_ok=True)
        source.write_text("# fixture\n", encoding="utf-8")
        controller = self.directory / "admin-panel/backend/src/main/kotlin/Example.kt"
        controller.parent.mkdir(parents=True, exist_ok=True)
        controller.write_text('@RestController\n@RequestMapping("/api")\nclass Example {\n' + mappings + '\n}\n', encoding="utf-8")
        self.inventory[0]["source"]["api"] = ["GET /api/nonfile"]
        return source

    def test_complete_split_inventory_passes_without_runtime_claim(self):
        result = self.run_cli(split=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("57 actions, 57 groups, 1 contracts", result.stdout)
        self.assertIn("Runtime behavior was not checked", result.stdout)

    def test_missing_group(self):
        self.inventory = [item for item in self.inventory if item["group"] != "M20"]
        self.assert_rejected("missing groups: M20")

    def test_duplicate_inventory_id(self):
        self.inventory.append(copy.deepcopy(self.inventory[0]))
        self.assert_rejected("duplicate ID H01.01")

    def test_id_prefix_must_match_group(self):
        self.inventory[0]["id"] = "H02.99"
        self.assert_rejected("ID prefix must match group")

    def test_missing_acceptance(self):
        del self.inventory[0]["acceptance"]
        self.assert_rejected("inventory[H01.01].acceptance")

    def test_wrong_public_origin(self):
        self.contracts["scope"]["publicOrigin"] = "https://other.example"
        self.assert_rejected("contracts.scope.publicOrigin")

    def test_rps_cannot_be_enabled_or_omitted(self):
        for value in (True, None, 0):
            with self.subTest(value=value):
                self.contracts["scope"]["mandatoryRpsWindowAllowed"] = value
                self.assert_rejected("mandatoryRpsWindowAllowed: must be false")

    def test_runtime_states_are_rejected_for_inventory_and_contracts(self):
        for state in ("done", "live_ready", "runtime_passed", [], None):
            with self.subTest(state=state):
                self.inventory[0]["state"] = state
                self.contracts["contracts"][0]["state"] = state
                self.assert_rejected("inventory[H01.01].state")
                self.assertIn("contracts[POSM_RECOMMEND].state", self.run_cli().stderr)

    def test_source_path_traversal_absolute_and_windows_paths(self):
        for path in ("../view.py", "src/../../view.py", "/tmp/view.py", "C:\\src\\view.py"):
            with self.subTest(path=path):
                self.inventory[0]["source"]["ui"] = [path]
                self.assert_rejected("inventory[H01.01].source.ui")

    def test_contract_source_path_traversal(self):
        self.contracts["contracts"][0]["sourcePaths"] = [{"path": "../view.py"}]
        self.assert_rejected("contracts[POSM_RECOMMEND].sourcePaths")

    def test_duplicate_contract_and_missing_check(self):
        self.contracts["contracts"].append(copy.deepcopy(self.contracts["contracts"][0]))
        del self.contracts["contracts"][0]["negativeChecks"]
        self.assert_rejected("duplicate ID POSM_RECOMMEND")
        self.assertIn("negativeChecks: required field is missing", self.run_cli().stderr)

    def test_roots_check_physical_paths_but_not_api_tables_or_functions(self):
        source = self.backend_fixture()
        self.inventory[0]["source"].update({
            "jobs": ["src/view.py:refresh", "SQL api.refresh", "api.refresh"],
            "api": ["GET /api/nonfile"], "data": ["table_name"],
            "files": ["backup database.dump", "GET /api/files/{key}"],
        })
        result = self.run_cli("--epharm-root", self.directory)
        self.assertEqual(result.returncode, 0, result.stderr)
        source.unlink()
        result = self.run_cli("--epharm-root", self.directory)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("source path does not exist", result.stderr)

    def test_new_backend_endpoint_without_inventory_is_rejected(self):
        self.backend_fixture('@GetMapping("/nonfile") fun get() = "ok"\n'
                             '@GetMapping("/new-action") fun newAction() = "new"')
        result = self.run_cli("--epharm-root", self.directory)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("missing inventory action for GET /api/new-action", result.stderr)

    def test_fully_aliased_controller_cannot_hide_beside_normal_controller(self):
        self.backend_fixture()
        hidden = self.directory / "admin-panel/backend/src/main/kotlin/Hidden.kt"
        hidden.write_text('import org.springframework.web.bind.annotation.RestController as Ctrl\n'
                          'import org.springframework.web.bind.annotation.GetMapping as Route\n'
                          '@Ctrl\nclass Hidden {\n'
                          '  @Route("/hidden") fun hidden() = "hidden"\n}\n', encoding="utf-8")
        result = self.run_cli("--epharm-root", self.directory)
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn("unsupported aliased Spring annotation import", result.stderr)
        self.assertNotIn("no backend controller endpoints", result.stderr)
        self.assertNotIn("Traceback", result.stderr)

    def test_control_characters_in_paths_report_errors_without_traceback(self):
        self.backend_fixture()
        for location in ("ui", "contract", "runtimeFiles"):
            for control in ("\x00", "\n", "\x7f"):
                with self.subTest(location=location, control=repr(control)):
                    self.inventory[0]["source"]["ui"] = ["src/view.py"]
                    self.inventory[0]["source"].pop("runtimeFiles", None)
                    self.contracts["contracts"][0]["sourcePaths"] = [{"path": "src/view.py"}]
                    path = f"src/bad{control}.py"
                    if location == "contract":
                        self.contracts["contracts"][0]["sourcePaths"] = [{"path": path}]
                    else:
                        self.inventory[0]["source"][location] = [path]
                    result = self.run_cli("--epharm-root", self.directory)
                    self.assertNotEqual(result.returncode, 0, result.stdout)
                    self.assertIn("control characters", result.stderr)
                    self.assertNotIn("Traceback", result.stderr)

    def test_unresolvable_source_path_reports_error_without_traceback(self):
        self.backend_fixture()
        (self.directory / "src/loop.py").symlink_to("loop.py")
        self.inventory[0]["source"]["ui"] = ["src/loop.py"]
        result = self.run_cli("--epharm-root", self.directory)
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertRegex(result.stderr, r"cannot resolve source path|source path does not exist")
        self.assertNotIn("Traceback", result.stderr)

    def test_unsupported_mapping_syntax_is_an_error(self):
        for mapping in ('@GetMapping(ROUTE) fun get() = "ok"',
                        '@GetMapping(path = "/nonfile") fun get() = "ok"',
                        '@RequestMapping("/nonfile") fun get() = "ok"',
                        '@GetMapping("/nonfile") val wrong = "not a function"',
                        'class Nested { @GetMapping("/nonfile") fun get() = "wrong owner" }'):
            with self.subTest(mapping=mapping):
                self.backend_fixture(mapping)
                result = self.run_cli("--epharm-root", self.directory)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("unsupported", result.stderr)

    def test_parser_ignores_comments_and_preserves_regex_route(self):
        self.backend_fixture('/* @GetMapping("/ignored") fun ignored() {} */\n'
                             'val example = "@GetMapping(\\\"/also-ignored\\\")"\n'
                             '@GetMapping("/nonfile", produces = ["text/plain"]) fun get() = "ok"\n'
                             '@PostMapping("/password/{purpose:reset|activate}") fun password() = "ok"')
        self.inventory[0]["source"]["api"].append("POST /api/password/{purpose:reset|activate}")
        result = self.run_cli("--epharm-root", self.directory)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("2 backend endpoints covered", result.stdout)

    def test_runtime_files_are_not_git_existence_or_copy_proof(self):
        self.backend_fixture()
        self.inventory[0]["source"]["runtimeFiles"] = ["storage/missing-upload", "photo.jpg"]
        result = self.run_cli("--epharm-root", self.directory)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("pending preservation references", result.stdout)
        self.inventory[0]["source"] = {"repo": "epharm", "revision": "baseline",
                                        "runtimeFiles": ["storage/missing-upload"]}
        self.assert_rejected("at least one of ui/api/jobs/files/data")

    def test_runtime_files_reject_traversal(self):
        for paths in ([], ["../photo.jpg"], ["/storage/photo.jpg"], ["C:\\storage\\photo.jpg"]):
            with self.subTest(paths=paths):
                self.inventory[0]["source"]["runtimeFiles"] = paths
                self.assert_rejected("source.runtimeFiles")


if __name__ == "__main__":
    unittest.main()
