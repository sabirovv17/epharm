import importlib.util
from pathlib import Path
import tempfile
import json
import subprocess
import unittest
import uuid
import threading
import urllib.request
import urllib.error
import http.client
from contextlib import closing
from http.server import ThreadingHTTPServer
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("acceptance_runtime", Path(__file__).resolve().parents[1] / "acceptance_runtime.py")
runtime = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runtime)
OTP_SPEC = importlib.util.spec_from_file_location("acceptance_otp", SPEC.origin.replace("acceptance_runtime.py", "acceptance_otp.py"))
otp = importlib.util.module_from_spec(OTP_SPEC)
OTP_SPEC.loader.exec_module(otp)


class AcceptanceRuntimeGuardTest(unittest.TestCase):
    def test_transport_preserves_binary_status_and_duplicate_headers(self):
        binary = b"\x00\xffPK\r\n\r\n\x00original-webp"
        status, headers, body = runtime.decode_curl_response(
            b"HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 206 Partial Content\r\n"
            b"Content-Type: image/webp\r\nContent-Range: bytes 0-20/99\r\nSet-Cookie: a=1\r\nSet-Cookie: b=2\r\n\r\n" + binary)
        self.assertEqual(206, status)
        self.assertEqual(binary, body)
        self.assertEqual([("Set-Cookie", "a=1"), ("Set-Cookie", "b=2")], [v for v in headers if v[0] == "Set-Cookie"])

    def test_non_loopback_credentials_and_protocol_are_refused(self):
        for value in ("https://epharm.inkar.kz", "http://localhost:18087", "http://user:pass@127.0.0.1:18087", "file:///tmp/fixture"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                runtime.local_url(value)
        runtime.local_url("http://127.0.0.1:18087")

    def test_source_and_candidate_database_are_refused_before_docker(self):
        with patch.object(runtime, "run") as call:
            for value in ({"prefix": "epharm-unify-pg-20261029", "database": "epharm"},
                          {"prefix": runtime.PREFIX + "12345678", "database": "epharm_unified_candidate"}):
                with self.assertRaises(ValueError):
                    runtime.sql(value, "SELECT 1")
            call.assert_not_called()

    def test_non_owned_manifest_is_refused_before_inspection(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(runtime, "run") as call:
            path = Path(folder) / "manifest.json"
            path.write_text(json.dumps({"prefix": "epharm_unified_candidate", "syntheticOnly": True}))
            with self.assertRaises(ValueError):
                runtime.verify_manifest(path)
            call.assert_not_called()

    def test_error_does_not_echo_synthetic_credentials_or_sql_rows(self):
        with patch.object(runtime.subprocess, "run", return_value=subprocess.CompletedProcess([], 1, "", "synthetic-private-password")):
            with self.assertRaises(RuntimeError) as error:
                runtime.run(["docker", "exec", "synthetic"])
        self.assertNotIn("synthetic-private-password", str(error.exception))
        self.assertIn("stderr digest", str(error.exception))

    def test_extra_network_is_rejected_even_with_correct_labels_and_image(self):
        run_id = str(uuid.uuid4())
        prefix = runtime.PREFIX + run_id[:8]
        value = {"prefix": prefix, "runId": run_id, "syntheticOnly": True, "images": runtime.IMAGES,
                 "database": prefix + "-db", "network": prefix + "-network", "apiUrl": "http://127.0.0.1:18087",
                 "apiPort": 18087, "jarSha256": runtime.hashlib.sha256(b"synthetic-jar").hexdigest()}
        inspected = [{"Config": {"Labels": {runtime.LABEL: run_id}}, "Image": runtime.IMAGES["db"],
                      "NetworkSettings": {"Networks": {value["network"]: {}, "external-default": {}}}}]
        with tempfile.TemporaryDirectory() as folder, patch.object(runtime, "run", return_value=json.dumps(inspected)):
            path = Path(folder) / "manifest.json"
            runtime.write_private(path, value)
            (path.parent / "app.jar").write_bytes(b"synthetic-jar")
            with self.assertRaisesRegex(ValueError, "only their internal network"):
                runtime.verify_manifest(path)

    def test_replaced_proxy_pid_is_never_signalled(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "manifest.json"
            (path.parent / "proxy.pid").write_text("12345")
            with patch.object(runtime, "verify_manifest", return_value={"prefix": runtime.PREFIX + "12345678"}), \
                 patch.object(runtime, "run", return_value="unrelated-server --listen 18087"), \
                 patch.object(runtime.os, "kill") as kill:
                with self.assertRaisesRegex(ValueError, "not the pinned"):
                    runtime.stop(path)
                kill.assert_not_called()

    def test_quiesce_keeps_database_redis_and_provider_running(self):
        with tempfile.TemporaryDirectory() as folder, \
             patch.object(runtime, "verify_manifest", return_value={"prefix": runtime.PREFIX + "12345678", "externalProviderFixture": True}), \
             patch.object(runtime, "run") as docker:
            runtime.stop(Path(folder) / "manifest.json", app_only=True)
            docker.assert_called_once_with(["docker", "stop", runtime.PREFIX + "12345678-app"])

    def test_another_loopback_server_cannot_substitute_for_the_pinned_application(self):
        run_id = str(uuid.uuid4()); prefix = runtime.PREFIX + run_id[:8]
        value = {"prefix": prefix, "runId": run_id, "syntheticOnly": True, "images": runtime.IMAGES,
                 "database": prefix + "-db", "network": prefix + "-network", "apiUrl": "http://127.0.0.1:19999", "apiPort": 18087}
        with tempfile.TemporaryDirectory() as folder, patch.object(runtime, "run") as docker:
            path = Path(folder) / "manifest.json"; runtime.write_private(path, value)
            with self.assertRaisesRegex(ValueError, "pinned container port"):
                runtime.verify_manifest(path)
            docker.assert_not_called()


class ProviderProtocolTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # Real HTTP handler; TLS itself is exercised by the real application in the lab.
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), otp.Provider)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown(); cls.server.server_close(); cls.thread.join()

    def post(self, path, body):
        request = urllib.request.Request(f"http://127.0.0.1:{self.server.server_port}" + path,
                                         data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=3) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as error:
            with error:
                return error.code, json.load(error)

    def test_real_daribar_snake_case_protocol(self):
        self.assertEqual(200, self.post("/api/v2/sms", {"phone": "70000000003"})[0])
        status, body = self.post("/api/v2/auth", {"phone": "70000000003", "validation_code": "5445"})
        self.assertEqual(200, status)
        self.assertTrue(body["result"]["access_token"])

    def test_streamed_spring_json_is_supported_without_relaxing_allowlist(self):
        with closing(http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=3)) as connection:
            connection.request("POST", "/api/v2/sms", body=iter([b'{"phone":', b'"70000000003"}']),
                               headers={"Content-Type": "application/json"}, encode_chunked=True)
            response = connection.getresponse()
            self.assertEqual(200, response.status)
            self.assertEqual("success", json.load(response)["status"])

    def test_non_synthetic_phone_wrong_code_and_route_are_denied(self):
        for path, body in (("/api/v2/sms", {"phone": "79999999999"}),
                           ("/api/v2/auth", {"phone": "70000000003", "validation_code": "0000"}),
                           ("/api/v2/auth", {"phone": "70000000003", "validationCode": "5445"}),
                           ("/unexpected", {"phone": "70000000003"})):
            self.assertEqual(400, self.post(path, body)[0])


if __name__ == "__main__":
    unittest.main()
