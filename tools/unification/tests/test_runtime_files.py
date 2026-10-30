import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "check_runtime_files.py"
SPEC = importlib.util.spec_from_file_location("runtime_files", SCRIPT)
runtime = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runtime)


class RuntimeFilesTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.s3 = self.base / "s3"
        self.merch = self.base / "merch"
        self.s3.mkdir()
        self.merch.mkdir()

    def put(self, root, key, content=b"valid bytes"):
        path = root / key
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)
        return hashlib.sha256(content).hexdigest()

    def ref(self, key="screens/demo.png", **extra):
        return {"source": "receipts.photo_url", "kind": "s3", "key": key, **extra}

    def test_valid_roots_inventory_all_files_and_typed_json_source(self):
        digest = self.put(self.s3, "screens/demo.png")
        self.put(self.s3, "unreferenced.bin", b"other")
        self.put(self.merch, "photos/item.jpg", b"photo")
        refs = [self.ref(expected_sha256=digest, expected_bytes=11),
                {"source": "course_lessons.content/$.images[*].url", "kind": "merch",
                 "key": "photos/item.jpg"}]
        report = runtime.check(refs, self.s3, self.merch)
        self.assertTrue(report["ok"])
        self.assertEqual(report["inventories"]["s3"]["files"], 2)
        self.assertEqual(report["inventories"]["s3"]["total_bytes"], 16)
        self.assertEqual(report["inventories"]["merch"]["total_bytes"], 5)
        encoded = json.dumps(report)
        self.assertNotIn("screens/demo.png", encoded)
        self.assertNotIn("unreferenced.bin", encoded)
        self.assertEqual(report["results"][0]["status"], "restored_present")

    def test_missing_hash_and_size_mismatch_are_failures_without_keys(self):
        self.put(self.s3, "screens/demo.png")
        for ref, expected in [(self.ref("missing-private-name"), "missing"),
                              (self.ref(expected_sha256="0" * 64), "sha256_mismatch"),
                              (self.ref(expected_bytes=1), "size_mismatch")]:
            with self.subTest(expected=expected):
                report = runtime.check([ref], self.s3)
                self.assertFalse(report["ok"])
                self.assertEqual(report["results"][-1]["status"], expected)
                self.assertEqual(report["results"][-1]["key_sha256"], runtime.key_hash(ref["key"]))
                self.assertNotIn(ref["key"], json.dumps(report))

    def test_unsafe_keys_do_not_read_or_escape(self):
        self.put(self.base, "private.bin", b"never read me")
        for key in ["../private.bin", "/absolute", "screens/../private.bin", "x\\y",
                    "screens//x", "./x", "x/", "C:/outside", "line\nsecret", "x\x00y",
                    "x\x7fy", "x\u202ey"]:
            with self.subTest(key=repr(key)):
                report = runtime.check([self.ref(key)], self.s3)
                self.assertFalse(report["ok"])
                self.assertEqual(report["results"][-1]["status"], "unsafe")

    def test_symlink_file_directory_and_unreferenced_link_are_unsafe(self):
        self.put(self.base, "outside/file.png")
        (self.s3 / "link.png").symlink_to(self.base / "outside/file.png")
        (self.s3 / "linked-dir").symlink_to(self.base / "outside", target_is_directory=True)
        report = runtime.check([self.ref("link.png"), self.ref("linked-dir/file.png")], self.s3)
        self.assertFalse(report["ok"])
        self.assertEqual(report["inventories"]["s3"]["files"], 0)
        self.assertTrue(all(r["status"] == "unsafe" for r in report["results"]))
        self.assertFalse(runtime.check([], self.s3)["ok"])

    def test_root_symlink_and_missing_root_fail(self):
        alias = self.base / "alias"
        alias.symlink_to(self.s3, target_is_directory=True)
        self.assertFalse(runtime.check([self.ref()], alias)["ok"])
        self.assertEqual(runtime.check([self.ref()])["results"][0]["status"], "root_required")

    def test_downloads_source_manifest_is_not_a_restored_copy(self):
        manifest = self.base / "downloads.json"
        manifest.write_text(json.dumps({"files": [{"relative_path": "release.zip", "bytes": 123,
                                                    "sha256": "a" * 64}]}))
        ref = {"source": "app_releases.url", "kind": "download", "key": "release.zip",
               "expected_bytes": 123, "expected_sha256": "a" * 64}
        report = runtime.check([ref], downloads_manifest=manifest)
        self.assertTrue(report["ok"])
        self.assertEqual(report["results"][0]["status"], "source_verified_not_restored")
        self.assertEqual(report["inventories"]["download"]["status"], "source_manifest_only_not_restored")
        ref["expected_sha256"] = "b" * 64
        self.assertFalse(runtime.check([ref], downloads_manifest=manifest)["ok"])
        self.assertFalse(runtime.check([ref])["ok"])

    def test_duplicate_or_unsafe_manifest_is_rejected_even_without_references(self):
        manifest = self.base / "downloads.json"
        good = {"relative_path": "release.zip", "bytes": 1, "sha256": "a" * 64}
        for entries in [[good, good], [{**good, "relative_path": "../escape"}],
                        [{**good, "bytes": True}], [{**good, "sha256": "invalid"}]]:
            manifest.write_text(json.dumps({"files": entries}))
            report = runtime.check([], downloads_manifest=manifest)
            self.assertFalse(report["ok"])
            self.assertEqual(report["results"][0]["status"], "manifest_invalid")
        manifest.write_text('{"files": [], "files": []}')
        self.assertFalse(runtime.check([], downloads_manifest=manifest)["ok"])

    def test_external_generated_terminal_never_fetch_or_count_as_missing(self):
        refs = [{"source": "training_certificates.pdf_url", "kind": kind,
                 "key": "https://private.invalid/token-VERY-PRIVATE"}
                for kind in ["generated", "external", "terminal_local"]]
        with patch("socket.socket", side_effect=AssertionError("network forbidden")):
            report = runtime.check(refs)
        self.assertTrue(report["ok"])
        self.assertEqual(set(report["counts"]["by_status"]),
                         {"unchecked_generated", "unchecked_external", "unchecked_terminal_local"})
        self.assertEqual(len(report["counts"]["by_source_kind_status"]), 3)
        self.assertNotIn("VERY-PRIVATE", json.dumps(report))

    def test_invalid_reference_or_expectations_fail_without_value_leaks(self):
        for ref in [{"source": "https://private.invalid/secret", "kind": "s3"},
                    self.ref(expected_bytes=True), self.ref(expected_sha256="wrong"),
                    self.ref(kind="secret-kind"), self.ref(kind=[]),
                    self.ref(expected_sha="misspelled-check"),
                    {**self.ref(), "source": "table." + "a" * 300}, None]:
            report = runtime.check([ref], self.s3)
            self.assertFalse(report["ok"])
            self.assertNotIn("private.invalid", json.dumps(report))
            self.assertNotIn("secret-kind", json.dumps(report))

    def test_cli_output_is_redacted_and_detailed_report_contains_only_hashes(self):
        refs = self.base / "private-references.json"
        refs.write_text(json.dumps([self.ref("private-name-containing-PII")]))
        output = self.base / "summary.json"
        stdout = io.StringIO()
        with contextlib.redirect_stdout(stdout):
            code = runtime.main(["--references", str(refs), "--s3-root", str(self.s3),
                                 "--output", str(output)])
        self.assertEqual(code, 1)
        self.assertNotIn("private-name", stdout.getvalue() + output.read_text())
        self.assertNotIn(str(self.base), stdout.getvalue() + output.read_text())
        self.assertIn("key_sha256", stdout.getvalue())
        self.assertNotIn("entries", stdout.getvalue())

    def test_high_count_invalid_references_stdout_is_bounded_and_full_report_is_kept(self):
        refs = self.base / "refs.json"
        rows = [{"source": "https://private.invalid/person-secret-" + str(i),
                 "kind": "s3", "key": "private-object-" + str(i)} for i in range(13000)]
        refs.write_text(json.dumps(rows))
        output = self.base / "report.json"
        stdout = io.StringIO()
        with contextlib.redirect_stdout(stdout):
            code = runtime.main(["--references", str(refs), "--output", str(output)])
        summary = json.loads(stdout.getvalue())
        self.assertEqual(code, 1)
        self.assertEqual(summary["failure_total"], 13000)
        self.assertEqual(summary["failure_unique_total"], 1)
        self.assertEqual(summary["failures"][0]["count"], 13000)
        self.assertEqual(len(json.loads(output.read_text())["results"]), 13000)
        self.assertLess(len(stdout.getvalue()), 4000)
        self.assertNotIn("person-secret", stdout.getvalue() + output.read_text())
        self.assertNotIn("private-object", stdout.getvalue() + output.read_text())

    def test_many_distinct_failures_and_source_groups_are_also_bounded(self):
        rows = [{"source": "table_" + str(i) + ".column", "kind": "s3",
                 "key": "../private-object-" + str(i)} for i in range(13000)]
        report = runtime.check(rows)
        summary = runtime.stdout_summary(report)
        encoded = json.dumps(summary)
        self.assertEqual(summary["failure_total"], 13000)
        self.assertEqual(summary["failure_unique_total"], 13000)
        self.assertEqual(len(summary["failures"]), 20)
        self.assertEqual(summary["truncated_count"], 12980)
        self.assertEqual(len(summary["counts"]["by_source"]), 20)
        self.assertEqual(summary["counts"]["by_source_truncated_count"], 12980)
        self.assertEqual(len(summary["counts"]["by_source_kind_status"]), 20)
        self.assertEqual(summary["counts"]["by_source_kind_status_truncated_count"], 12980)
        self.assertEqual(len(report["results"]), 13000)
        self.assertLess(len(encoded), 20000)
        self.assertNotIn("private-object", encoded)

    def test_report_cannot_overwrite_input_or_any_checked_file(self):
        refs = self.base / "refs.json"
        original = json.dumps([])
        refs.write_text(original)
        for output in [refs, self.s3 / "report.json"]:
            with contextlib.redirect_stdout(io.StringIO()):
                code = runtime.main(["--references", str(refs), "--s3-root", str(self.s3),
                                     "--output", str(output)])
            self.assertEqual(code, 2)
        self.assertEqual(refs.read_text(), original)
        self.assertFalse((self.s3 / "report.json").exists())


if __name__ == "__main__":
    unittest.main()
