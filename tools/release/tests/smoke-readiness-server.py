#!/usr/bin/env python3
"""Deterministic HTTP fixture: transient 5xx, then coherent release responses."""

import json
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlsplit


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        path = urlsplit(self.path).path
        if path in ("/api/health", "/release.json"):
            count = self.server.requests.get(path, 0) + 1
            self.server.requests[path] = count
            if count <= 2:
                self.send_response(503 if path == "/api/health" else 502)
                self.end_headers()
                return
            payload = {"releaseId": "v0.1.14"}
            if path == "/api/health":
                payload.update({"status": "ok", "catalogSnapshot": {"ready": True, "products": 1}})
        elif path == "/api/mobile/catalog/products":
            payload = {"items": [{"id": "prod_smoke", "name": "Парацетамол"}], "total": 1}
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
