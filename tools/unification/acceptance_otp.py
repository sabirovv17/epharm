#!/usr/bin/env python3
"""Synthetic Daribar protocol fixture, only on the acceptance lab's internal network.

This replaces the external SMS provider, not the application's challenges, expiry,
phone eligibility, attempt limits, sessions, or business authorization.
"""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import ssl


class Provider(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_POST(self):
        try:
            # Spring's actual RestClient streams JSON with chunked transfer encoding.
            if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
                raw = b""
                while True:
                    line = self.rfile.readline(32)
                    size = int(line.strip(), 16)
                    if size == 0:
                        if self.rfile.readline(32) != b"\r\n": raise ValueError()
                        break
                    if size < 0 or len(raw) + size > 2048: raise ValueError()
                    raw += self.rfile.read(size)
                    if self.rfile.read(2) != b"\r\n": raise ValueError()
            else:
                size = int(self.headers.get("Content-Length", "0"))
                if not 0 < size <= 2048: raise ValueError()
                raw = self.rfile.read(size)
            value = json.loads(raw)
        except (ValueError, UnicodeError):
            self.send_error(400)
            return
        known = value.get("phone") in ("70000000001", "70000000002", "70000000003")
        valid = known and (self.path == "/api/v2/sms" or
                          self.path == "/api/v2/auth" and value.get("validation_code") == "5445")
        payload = {"status": "success", "result": {"access_token": "synthetic-provider-proof-only"}} if valid else {
            "status": "error", "error": "invalid code"}
        raw = json.dumps(payload).encode()
        self.send_response(200 if valid else 400)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)


if __name__ == "__main__":
    server = ThreadingHTTPServer(("0.0.0.0", 8443), Provider)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain("/fixture/otp.crt", "/fixture/otp.key")
    server.socket = context.wrap_socket(server.socket, server_side=True)
    server.serve_forever()
