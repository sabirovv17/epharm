#!/usr/bin/env python3
"""Deterministic HTTP fixture: transient 5xx, then coherent release responses."""

import json
import os
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import parse_qs, urlsplit


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        request_url = urlsplit(self.path)
        path = request_url.path
        if path in ("/api/health", "/release.json"):
            count = self.server.requests.get(path, 0) + 1
            self.server.requests[path] = count
            if count <= int(os.environ.get("CATALOG_FIXTURE_TRANSIENT_REQUESTS", "2")):
                self.send_response(503 if path == "/api/health" else 502)
                self.end_headers()
                return
            payload = {"releaseId": (os.environ.get("FRONTEND_FIXTURE_RELEASE_ID", "v0.1.14")
                                    if path == "/release.json" else "v0.1.14"),
                       "commit": "frontend-fixture" if path == "/release.json" else "backend-fixture"}
            if path == "/api/health":
                payload.update({"status": "ok", "catalogSnapshot": {"ready": True, "products": 1}})
                source = os.environ.get("CATALOG_FIXTURE_SOURCE")
                if source:
                    payload["catalogSource"] = source
                readiness = os.environ.get("CATALOG_FIXTURE_SNAPSHOT_READY", "true")
                if readiness == "absent":
                    del payload["catalogSnapshot"]
                elif readiness == "false":
                    payload["catalogSnapshot"] = {"ready": False, "products": 0}
        elif path == "/api/mobile/catalog/products":
            payload = {"items": [{"id": "prod_smoke", "name": "Парацетамол"}], "total": 1}
            mode = os.environ.get("CATALOG_FIXTURE_RESPONSE", "normal")
            if mode == "unavailable":
                self.send_response(503)
                self.end_headers()
                return
            if mode == "empty" or (mode == "search-missing" and "q" in parse_qs(request_url.query)):
                payload = {"items": [], "total": 0}
        else:
            self.send_response(404)
            self.end_headers()
            return
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, _format, *_args):
        pass


server = HTTPServer(("127.0.0.1", 0), Handler)
server.requests = {}
print(server.server_port, flush=True)
server.serve_forever()
