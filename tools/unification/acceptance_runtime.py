#!/usr/bin/env python3
"""Create an empty, synthetic acceptance lab. Never connects to a preserved/source database."""
from __future__ import annotations

import argparse
import base64
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import io
import json
import os
from pathlib import Path
import secrets
import signal
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

PREFIX = "epharm-accept-20261030-"
LABEL = "kz.epharm.synthetic-acceptance"
OTP_IMAGE = "sha256:8630ab77c5adf06e1f914483db4dd70e3fa59118160daab9b0ee75e685344221"
IMAGES = {
    "db": "sha256:7e7dbab8d3b431a20793a6d99cb5a6bc84e44914309917f1bf5589a7568cdefd",
    "redis": "sha256:4ab05801a605362b921756ce9dff4893add29c678076fe49a72d8cc3278806c6",
    "app": "sha256:3a409ef6ae940df1f829c92a6683799836f3fc379a32dbbb0ca6a02fa3723ca7",
}


def run(args: list[str], *, data: str | None = None) -> str:
    result = subprocess.run(args, input=data, text=True, capture_output=True, check=False)
    if result.returncode:
        # Docker/SQL errors can echo supplied environment/rows. Retain only a diagnostic hash.
        digest = hashlib.sha256(result.stderr.encode()).hexdigest()[:16]
        raise RuntimeError(f"{args[0]} failed ({result.returncode}); stderr digest {digest}")
    return result.stdout.strip()


def write_private(path: Path, value: object) -> None:
    encoded = json.dumps(value, indent=2) + "\n" if not isinstance(value, str) else value
    with path.open("x", encoding="utf-8") as stream:
        os.chmod(path, 0o600)
        stream.write(encoded)


def local_url(url: str) -> None:
    from urllib.parse import urlsplit
    parsed = urlsplit(url)
    if parsed.scheme != "http" or parsed.hostname != "127.0.0.1" or parsed.username or parsed.password:
        raise ValueError("Only explicit HTTP 127.0.0.1 acceptance URLs are allowed")


def verify_manifest(path: Path) -> dict:
    if path.is_symlink() or path.stat().st_uid != os.getuid() or path.stat().st_mode & 0o077:
        raise ValueError("Acceptance manifest must be owner-only")
    value = json.loads(path.read_text())
    prefix = value.get("prefix", "")
    run_id = str(uuid.UUID(value.get("runId", "")))
    if prefix != PREFIX + run_id[:8] or value.get("syntheticOnly") is not True or value.get("images") != IMAGES:
        raise ValueError("Not an owned synthetic acceptance manifest")
    if value.get("database") != prefix + "-db" or value.get("network") != prefix + "-network":
        raise ValueError("Acceptance resource names changed")
    local_url(value["apiUrl"])
    if not isinstance(value.get("apiPort"), int) or not 1024 <= value["apiPort"] <= 65535 or value["apiUrl"] != f"http://127.0.0.1:{value['apiPort']}":
        raise ValueError("Acceptance URL differs from the pinned container port")
    if hashlib.sha256((path.parent / "app.jar").read_bytes()).hexdigest() != value.get("jarSha256"):
        raise ValueError("Acceptance JAR differs from its pinned fingerprint")
    for key in ("db", "redis", "app"):
        name = f"{prefix}-{key}"
        info = json.loads(run(["docker", "inspect", name]))[0]
        if info["Config"]["Labels"].get(LABEL) != value["runId"] or info["Image"] != IMAGES[key]:
            raise ValueError("Acceptance container identity changed")
        if set(info["NetworkSettings"]["Networks"]) != {value["network"]}:
            raise ValueError("Acceptance containers must have only their internal network")
    network = json.loads(run(["docker", "network", "inspect", value["network"]]))[0]
    if not network["Internal"] or network["Labels"].get(LABEL) != value["runId"]:
        raise ValueError("Acceptance network must remain internal")
    if value.get("externalProviderFixture"):
        info = json.loads(run(["docker", "inspect", prefix + "-otp"]))[0]
        if info["Config"]["Labels"].get(LABEL) != run_id or info["Image"] != OTP_IMAGE or info["HostConfig"].get("PortBindings"):
            raise ValueError("Synthetic provider must remain private and pinned")
        if set(info["NetworkSettings"]["Networks"]) != {value["network"]}:
            raise ValueError("Synthetic provider has an unexpected network")
    app = json.loads(run(["docker", "inspect", f"{prefix}-app"]))[0]
    if not any(m["Destination"] == "/acceptance/app.jar" and not m["RW"] and Path(m["Source"]).resolve() == (path.parent / "app.jar").resolve() for m in app["Mounts"]):
        raise ValueError("Acceptance app must mount its pinned JAR read-only")
    bindings = app["HostConfig"].get("PortBindings") or {}
    if bindings != {"8080/tcp": [{"HostIp": "127.0.0.1", "HostPort": str(value["apiPort"])}]}:
        raise ValueError("Acceptance API must be bound only to its pinned loopback port")
    return value


def stop(path: Path, *, app_only: bool = False) -> None:
    """Stop only the pinned lab; retain its private files/database for evidence."""
    manifest = verify_manifest(path)
    pid_path = path.parent / "proxy.pid"
    if pid_path.exists():
        pid = int(pid_path.read_text())
        if pid <= 1:
            raise ValueError("Invalid acceptance transport PID")
        command = run(["ps", "-p", str(pid), "-o", "command="])
        expected = f"{Path(__file__).resolve()} serve --manifest {path.resolve()}"
        if expected not in command:
            raise ValueError("Transport PID is not the pinned acceptance process")
        os.kill(pid, signal.SIGTERM)
        pid_path.unlink()
    roles = ("app",) if app_only else ("app", "redis", "db", *(["otp"] if manifest.get("externalProviderFixture") else []))
    for role in roles:
        run(["docker", "stop", manifest["prefix"] + "-" + role])


def proof(manifest: dict) -> dict:
    """Read only aggregate persistence evidence; HTTP 200 alone is insufficient."""
    aggregate = sql(manifest, """SELECT json_build_object(
        'adminAccounts',(SELECT count(*) FROM admin_users),
        'managedProfiles',(SELECT count(*) FROM unified_admin_profiles),
        'verifiedPeople',(SELECT count(*) FROM unified_persons),
        'accessAuditRows',(SELECT count(*) FROM unified_admin_access_audit),
        'trainingAssignments',(SELECT count(*) FROM training_assignments),
        'approvedPlacements',(SELECT count(*) FROM merch_media_plans WHERE status='approved'),
        'approvedTaskItems',(SELECT count(*) FROM merch_task_items WHERE status='completed'),
        'evidenceRows',(SELECT count(*) FROM merch_task_evidence),
        'taskShownReceipts',(SELECT count(*) FROM merch_task_delivery_receipts),
        'recommendationEvents',(SELECT count(*) FROM recommendation_events),
        'recommendationsAcked',(SELECT count(*) FROM recommendation_events WHERE displayed_at IS NOT NULL),
        'recommendationsRejected',(SELECT count(*) FROM recommendation_events WHERE outcome='rejected'),
        'posSales',(SELECT count(*) FROM pos_sales),
        'walletEntries',(SELECT count(*) FROM pharmacist_wallet_entries));""")
    counts = json.loads(aggregate)
    if sql(manifest, "SELECT to_regclass('public.learner_password_vault') IS NOT NULL") == "t":
        counts.update(json.loads(sql(manifest, """SELECT json_build_object(
            'capturedPasswordCopies',(SELECT count(*) FROM learner_password_vault WHERE state='captured'),
            'unconsumedCredentialExports',(SELECT count(*) FROM learner_credential_exports),
            'credentialAuditRows',(SELECT count(*) FROM learner_credential_export_audit));""")))
    return {"schemaVersion": 1, "syntheticOnly": True, "jarSha256": manifest["jarSha256"],
            "provider": manifest.get("externalProviderFixture", "local-dev-no-SMS"),
            "isolationVerified": True, "aggregate": counts,
            "renderedOverlayProven": False, "businessUserAcceptanceProven": False}


def sql(manifest: dict, statement: str) -> str:
    name = f"{manifest['prefix']}-db"
    if not name.startswith(PREFIX) or manifest["database"] != name:
        raise ValueError("Refusing any source/candidate database")
    return run(["docker", "exec", "-i", name, "psql", "-X", "-v", "ON_ERROR_STOP=1", "-U", "acceptance", "-d", name, "-At"], data=statement)


def health(url: str, seconds: int = 150) -> None:
    local_url(url)
    until = time.monotonic() + seconds
    while time.monotonic() < until:
        try:
            with urllib.request.urlopen(url + "/actuator/health", timeout=20) as response:
                if response.status == 200:
                    return
        except (OSError, urllib.error.URLError):
            pass
        time.sleep(1)
    raise RuntimeError("Synthetic application did not become healthy; do not substitute a candidate URL")


def decode_curl_response(raw: bytes) -> tuple[int, list[tuple[str, str]], bytes]:
    import http.client
    while True:
        head, boundary, body = raw.partition(b"\r\n\r\n")
        if not boundary:
            raise ValueError("Invalid acceptance transport response")
        status_line, _, headers = head.partition(b"\r\n")
        status = int(status_line.split()[1])
        if 100 <= status < 200:
            raw = body
            continue
        parsed = http.client.parse_headers(io.BytesIO(headers + b"\r\n\r\n"))
        return status, list(parsed.items()), body


def serve(manifest_path: Path) -> None:
    manifest = verify_manifest(manifest_path)
    container = manifest["prefix"] + "-app"
    def quote(value: str) -> str:
        return '"' + value.replace('\\', '\\\\').replace('"', '\\"').replace('\r', '\\r').replace('\n', '\\n') + '"'
    class Proxy(BaseHTTPRequestHandler):
        def log_message(self, *_: object) -> None:
            pass  # Request URLs/headers may contain synthetic credentials; never log them.

        def forward(self) -> None:
            if not (self.path.startswith("/api/") or self.path == "/actuator/health") or self.path.startswith("//") or any(ord(c) < 32 for c in self.path):
                self.send_error(400); return
            size = int(self.headers.get("Content-Length", "0"))
            if not 0 <= size <= 32 * 1024 * 1024:
                self.send_error(413); return
            data = self.rfile.read(size)
            temp = "/tmp/epharm-accept-proxy-" + uuid.uuid4().hex
            hop = {"host", "connection", "transfer-encoding", "content-length", "keep-alive", "te", "trailer", "upgrade", "proxy-authorization", "proxy-authenticate"}
            hop.update(v.strip().lower() for v in self.headers.get("Connection", "").split(","))
            try:
                if data:
                    copied = subprocess.run(["docker", "exec", "-i", container, "sh", "-c", "umask 077; cat > " + temp], input=data, capture_output=True)
                    if copied.returncode:
                        raise RuntimeError("Acceptance request transport failed")
                config = ["silent", "show-error", "noproxy = \"*\"", "max-redirs = 0", "max-time = 30", "dump-header = \"-\"", "output = \"-\"",
                          "url = " + quote("http://127.0.0.1:8080" + self.path), "request = " + quote(self.command)]
                for key, value in self.headers.items():
                    if key.lower() not in hop:
                        config.append("header = " + quote(key + ": " + value))
                if data:
                    config.append("data-binary = " + quote("@" + temp))
                result = subprocess.run(["docker", "exec", "-i", container, "curl", "--config", "-"], input=("\n".join(config) + "\n").encode(), capture_output=True)
                if result.returncode:
                    raise RuntimeError("Acceptance response transport failed")
                status, headers, body = decode_curl_response(result.stdout)
                self.send_response(status)
                connection_fields = {s.strip().lower() for k, v in headers if k.lower() == "connection" for s in v.split(",")}
                for key, value in headers:
                    if key.lower() not in hop | connection_fields:
                        self.send_header(key, value)
                self.send_header("Content-Length", str(len(body))); self.end_headers()
                if self.command != "HEAD":
                    self.wfile.write(body)
            except (OSError, ValueError, RuntimeError):
                self.send_error(502, "Synthetic acceptance transport failed")
            finally:
                if data:
                    subprocess.run(["docker", "exec", container, "rm", "-f", temp], capture_output=True)
        do_GET = do_POST = do_PUT = do_PATCH = do_DELETE = do_HEAD = do_OPTIONS = forward
    ThreadingHTTPServer(("127.0.0.1", manifest["apiPort"]), Proxy).serve_forever()


def bcrypt_hash(password: str) -> str:
    command = shutil.which("htpasswd")
    if not command:
        raise ValueError("Existing htpasswd with bcrypt support is required")
    return run([command, "-niBC", "10", "synthetic"], data=password + "\n").split(":", 1)[1]


def grant(module: str, subsection: str, action: str, merch: bool = False) -> dict:
    return {"module": module, "subsection": subsection, "action": action,
            "allPharmacies": not merch, "pharmacyIds": [], "allMerchBranches": merch,
            "merchBranchIds": [], "allProducers": merch and subsection in ("placements", "producers"), "producerIds": []}


def seed(manifest: dict, root: Path) -> None:
    ids = {name: str(uuid.uuid5(uuid.UUID(manifest["runId"]), name)) for name in
           ("hq", "person", "organization", "branchA", "branchB", "shelfA", "shelfB", "producer", "device")}
    password = "SyntheticOnly-" + secrets.token_urlsafe(18)
    encoded = bcrypt_hash(password)
    device_key = secrets.token_urlsafe(36)
    # Future-dated, mathematically valid synthetic input. It is never claimed to be a real citizen.
    digits = "99010150001"
    check = sum(int(n) * w for n, w in zip(digits, range(1, 12))) % 11
    if check == 10:
        check = sum(int(n) * w for n, w in zip(digits, (3, 4, 5, 6, 7, 8, 9, 10, 11, 1, 2))) % 11
    if check == 10:
        raise ValueError("Synthetic IIN checksum is not usable")
    iin = digits + str(check)
    grants = [grant("management", "accounts", action) for action in ("view", "manage")]
    for section, actions in {
        "inventory": ("view", "edit_structure", "edit_passport", "configure_prices", "manage_availability", "financial_data", "export"),
        "placements": ("view", "edit", "submit", "approve", "reject", "cancel", "assign", "financial_data", "export"),
        "tasks": ("view", "create", "execute", "review", "comment", "cancel", "financial_data", "export"),
        "staff_access": ("view", "create", "revoke"),
    }.items():
        grants += [grant("merchandising", section, action, True) for action in actions]
    for section, actions in {
        "programs": ("view", "create", "edit", "publish", "export"), "assignments": ("view", "assign", "export"),
        "results": ("view", "export"), "events": ("view", "create", "edit"), "courses": ("view", "create", "edit", "publish", "export"),
    }.items():
        grants += [grant("training", section, action) for action in actions]
    for section, actions in {"campaigns": ("view", "create", "edit", "publish"), "recommendations": ("view", "edit", "publish"), "accruals": ("view", "export"), "bonuses": ("configure_bonus",)}.items():
        grants += [grant("motivation", section, action) for action in actions]
    # Explicit fixture authority, not authority derived from the HQ title: delegation of
    # sensitive data and credentials requires the delegator to own the same scope.
    for action in ("view", "personal_data", "export_credentials"):
        value = grant("pharmacists", "registry", action)
        value.update(allPharmacies=False, pharmacyIds=["accept-pharmacy-a"])
        grants.append(value)
    q = lambda v: "'" + str(v).replace("'", "''") + "'"
    statements = ["BEGIN;", "DO $$ BEGIN IF EXISTS(SELECT 1 FROM admin_users) OR EXISTS(SELECT 1 FROM pharmacies) THEN RAISE EXCEPTION 'acceptance database is not empty'; END IF; END $$;",
        f"INSERT INTO admin_users(id,email,password_hash,name,role,company,status,access_mode) VALUES({q(ids['hq'])},'hq@acceptance.invalid',{q(encoded)},'Synthetic acceptance HQ','SYSTEM_ADMIN','Synthetic','active','MANAGED');",
        f"INSERT INTO unified_persons(id,verification_reference) VALUES({q(ids['person'])},'SYNTHETIC_ACCEPTANCE_ONLY');",
        f"INSERT INTO unified_admin_profiles(account_id,person_id,grants_json) VALUES({q(ids['hq'])},{q(ids['person'])},{q(json.dumps(grants))});",
        "INSERT INTO chains(id,name,\"group\") VALUES('accept-chain','Synthetic chain','pilot');"]
    for suffix in ("a", "b"):
        pharmacy = f"accept-pharmacy-{suffix}"
        statements.append(f"INSERT INTO pharmacies(id,name,chain_id,chain_name,city,addr,\"group\") VALUES({q(pharmacy)},{q('Synthetic pharmacy '+suffix)},'accept-chain','Synthetic chain',{q('Synthetic City '+suffix.upper())},'Synthetic address','pilot');")
    statements += [f"INSERT INTO pharmacists(id,name,iin,phone,password_hash,pharmacy_id,pharmacy_name,city,tier,status) VALUES('accept-learner','Synthetic learner',{q(iin)},'+70000000001',{q(encoded)},'accept-pharmacy-a','Synthetic pharmacy a','Synthetic City A','Silver','active');",
        f"INSERT INTO pharmacists(id,name,iin,phone,password_hash,pharmacy_id,pharmacy_name,city,tier,status) VALUES('accept-learner-foreign','Synthetic foreign learner','990101500023','+70000000002',{q(encoded)},'accept-pharmacy-b','Synthetic pharmacy b','Synthetic City B','Silver','active');",
        f"INSERT INTO fulfillment_devices(id,device_id,pharmacy_id,secret_sha256,secret_hint) VALUES({q(ids['device'])},'SYNTHETIC-ACCEPT-POS','accept-pharmacy-a',{q(hashlib.sha256(device_key.encode()).hexdigest())},'SYNTHETIC');"]
    def native(table: str, identifier: str, fields: dict) -> None:
        values = {"id": identifier, "source_system": "EPHARM", "source_origin": "https://epharm.inkar.kz", "source_id": identifier,
                  "source_payload": json.dumps({"syntheticAcceptance": True}), "created_at": "2026-10-30T00:00:00Z", "updated_at": "2026-10-30T00:00:00Z", **fields}
        statements.append(f"INSERT INTO {table}({','.join(values)}) VALUES({','.join(q(v) if not isinstance(v, bool) else str(v).lower() for v in values.values())});")
    native("merch_organizations", ids["organization"], {"name": "Synthetic network", "legal_name": "Synthetic network", "active": True})
    for suffix in ("A", "B"):
        native("merch_branches", ids[f"branch{suffix}"], {"organization_id": ids["organization"], "code": f"ACCEPT-{suffix}", "name": f"Synthetic branch {suffix}", "address": "Synthetic address", "city": f"Synthetic City {suffix}", "active": True,
              "epharm_pharmacy_id": f"accept-pharmacy-{suffix.lower()}", "pharmacy_binding_verified": True})
        native("merch_shelves", ids[f"shelf{suffix}"], {"branch_id": ids[f"branch{suffix}"], "code": f"ACCEPT-SHELF-{suffix}", "category": "AA", "width_m": "1.0", "base_monthly_price": "15000", "half_monthly_price": "8000", "place_type": "shelf", "position_index": "0", "specifications": "{}", "sale_mode": "whole_or_halves", "active": True})
    native("merch_producers", ids["producer"], {"name": "Synthetic producer", "phone": "+70000000003", "phone_normalized": "+70000000003", "phone_verified_at": "2026-01-01T00:00:00Z", "source_account_role": "advertiser", "active": True})
    # Explicit synthetic legacy rule: no catalog request, medical inference, or reward.
    for role in ("trigger", "offer"):
        statements.append(f"INSERT INTO products(id,name,brand,vendor,mnn,price,ipart_id) VALUES('accept-{role}','Synthetic {role}','Synthetic','Synthetic','Synthetic',1000,'ACCEPT-{role.upper()}');")
    statements.append("""INSERT INTO rules(id,type,status,trigger,recommend,bonus,script,created_by)
        VALUES('accept-rule','crosssell','active','{"kind":"product","value":"accept-trigger"}',
        'accept-offer',0,'Synthetic compatibility fixture','synthetic-acceptance');""")
    statements += ["COMMIT;"]
    sql(manifest, "\n".join(statements))
    write_private(root / "fixture.json", {"schemaVersion": 1, "syntheticOnly": True, "runId": manifest["runId"],
        "apiUrl": manifest["apiUrl"], "frontendUrl": "http://127.0.0.1:4177", "ids": ids,
        "hq": {"email": "hq@acceptance.invalid", "password": password}, "learner": {"iin": iin, "password": password},
        "producerPhone": "+70000000003", "syntheticOtp": "5445", "deviceKey": device_key,
        "pharmacyId": "accept-pharmacy-a", "foreignPharmacyId": "accept-pharmacy-b", "deviceId": "SYNTHETIC-ACCEPT-POS"})


def up(args: argparse.Namespace) -> None:
    jar = Path(args.jar).resolve(strict=True)
    if hashlib.sha256(jar.read_bytes()).hexdigest() != args.jar_sha256:
        raise ValueError("JAR fingerprint differs from the approved local artifact")
    with socket.socket() as sock:
        if sock.connect_ex(("127.0.0.1", args.port)) == 0:
            raise ValueError("Acceptance port is already occupied; refusing to reuse any existing server")
    root = Path(args.private_root).absolute()
    root.mkdir(mode=0o700, parents=False, exist_ok=False)
    run_id = str(uuid.uuid4()); prefix = PREFIX + run_id[:8]
    for image in (*IMAGES.values(), OTP_IMAGE):
        if run(["docker", "image", "inspect", image, "--format", "{{.Id}}"] ) != image:
            raise ValueError("Expected cached image is unavailable; pulling is prohibited")
    manifest = {"schemaVersion": 1, "syntheticOnly": True, "runId": run_id, "prefix": prefix,
        "database": prefix + "-db", "network": prefix + "-network", "images": IMAGES,
        "apiPort": args.port, "apiUrl": f"http://127.0.0.1:{args.port}", "jarSha256": args.jar_sha256,
        "externalProviderFixture": "synthetic-HTTPS-Daribar-protocol-only"}
    write_private(root / "manifest.json", manifest)
    password = secrets.token_urlsafe(36)
    write_private(root / "pg.env", f"POSTGRES_DB={manifest['database']}\nPOSTGRES_USER=acceptance\nPOSTGRES_PASSWORD={password}\n")
    owner = str(uuid.uuid5(uuid.UUID(run_id), "hq"))
    write_private(root / "app.env", "\n".join(["SPRING_PROFILES_ACTIVE=test", "SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/" + manifest["database"], "SPRING_DATASOURCE_USERNAME=acceptance", "SPRING_DATASOURCE_PASSWORD=" + password,
        "SPRING_DATA_REDIS_HOST=redis", "JWT_SECRET=" + secrets.token_urlsafe(64), "OTP_DEV_MODE=false", "OTP_PROVIDER=daribar", "OTP_RESEND_COOLDOWN_SECONDS=0",
        "STANDARDN_DIRECTORY_ENABLED=false", "STOREFRONT_CATALOG_ENABLED=false", "MEDUSA_ENABLED=false", "MERCH_AUTOMATION_ENABLED=false", "MERCH_PLACEMENT_EXPIRY_ENABLED=false", "MERCH_TASKS_ENABLED=false", "MERCH_REWARDS_ENABLED=false",
        "MERCH_NATIVE_DELIVERY_ENABLED=true", "MERCH_NATIVE_EXECUTOR_ENABLED=true", "MERCH_STAFF_ACCESS_ENABLED=true", "MERCH_PRODUCER_AUTH_ENABLED=true", "POSM_STAFF_AUTH_ENABLED=false", "POSM_LEGACY_DEVICE_KEY_ENABLED=false", "FULFILLMENT_ENABLED=false",
        "DARIBAR_OTP_BASE_URL=https://otp-fixture:8443", "P1SMS_BASE_URL=http://127.0.0.1:9", "MERCH_TASK_EVIDENCE_ROOT=/acceptance/evidence", "UNIFIED_ADMIN_PRIMARY_OWNER_ID=" + owner]) + "\n")
    with (root / "app.env").open("a") as stream:
        stream.write("LEARNER_CREDENTIALS_ENABLED=true\nLEARNER_CREDENTIALS_KEY_FILE=/acceptance/vault/keys.json\n")
    (root / "vault").mkdir(mode=0o700)
    write_private(root / "vault" / "keys.json", {"version": 1, "activeKeyId": "synthetic-acceptance-only", "keys": {
        "synthetic-acceptance-only": base64.b64encode(secrets.token_bytes(32)).decode()}})
    shutil.copyfile(jar, root / "app.jar"); os.chmod(root / "app.jar", 0o600)
    (root / "evidence").mkdir(mode=0o700)
    run(["docker", "network", "create", "--internal", "--label", f"{LABEL}={run_id}", manifest["network"]])
    common = ["--pull=never", "--detach", "--label", f"{LABEL}={run_id}", "--network", manifest["network"]]
    run(["docker", "run", *common, "--name", prefix + "-db", "--network-alias", "db", "--env-file", str(root / "pg.env"), IMAGES["db"]])
    run(["docker", "run", *common, "--name", prefix + "-redis", "--network-alias", "redis", IMAGES["redis"], "redis-server", "--save", "", "--appendonly", "no"])
    # Private synthetic CA only; never disable TLS validation or change a host trust store.
    run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-noenc", "-days", "2", "-subj", "/CN=otp-fixture", "-addext", "subjectAltName=DNS:otp-fixture",
         "-keyout", str(root / "otp.key"), "-out", str(root / "otp.crt")])
    os.chmod(root / "otp.key", 0o600); os.chmod(root / "otp.crt", 0o600)
    run(["docker", "run", "--rm", "--pull=never", "--network", "none", "--user", "0:0", "--entrypoint", "keytool",
         "--mount", f"type=bind,src={root},dst=/fixture", IMAGES["app"], "-importcert", "-noprompt", "-alias", "synthetic-otp-only",
         "-file", "/fixture/otp.crt", "-keystore", "/fixture/otp-trust.p12", "-storepass", "synthetic-trust-only"])
    os.chmod(root / "otp-trust.p12", 0o600)
    shutil.copyfile(Path(__file__).with_name("acceptance_otp.py"), root / "otp.py"); os.chmod(root / "otp.py", 0o600)
    provider_mounts = [part for file in ("otp.py", "otp.crt", "otp.key") for part in
                       ("--mount", f"type=bind,src={root / file},dst=/fixture/{file},readonly")]
    run(["docker", "run", *common, "--name", prefix + "-otp", "--network-alias", "otp-fixture", "--memory", "96m",
         *provider_mounts, OTP_IMAGE, "python", "/fixture/otp.py"])
    for _ in range(50):
        probe = subprocess.run(["docker", "exec", prefix + "-db", "pg_isready", "-U", "acceptance"], capture_output=True)
        if probe.returncode == 0:
            break
        time.sleep(1)
    run(["docker", "run", *common, "--name", prefix + "-app", "--user", "0:0", "--memory", "1536m", "--publish", f"127.0.0.1:{args.port}:8080", "--env-file", str(root / "app.env"),
         "--mount", f"type=bind,src={root / 'app.jar'},dst=/acceptance/app.jar,readonly", "--mount", f"type=bind,src={root / 'evidence'},dst=/acceptance/evidence",
         "--mount", f"type=bind,src={root / 'otp-trust.p12'},dst=/acceptance/otp-trust.p12,readonly",
         "--mount", f"type=bind,src={root / 'vault'},dst=/acceptance/vault,readonly",
         "--entrypoint", "java", IMAGES["app"], "-Xmx768m", "-Djavax.net.ssl.trustStore=/acceptance/otp-trust.p12", "-Djavax.net.ssl.trustStorePassword=synthetic-trust-only",
         "-jar", "/acceptance/app.jar", "--app.cors.allowed-origins=http://127.0.0.1:4177"])
    verify_manifest(root / "manifest.json")
    proxy_log = (root / "transport.log").open("xb")
    os.chmod(root / "transport.log", 0o600)
    process = subprocess.Popen([sys.executable, str(Path(__file__).resolve()), "serve", "--manifest", str(root / "manifest.json")], stdout=proxy_log, stderr=proxy_log, start_new_session=True)
    write_private(root / "proxy.pid", str(process.pid))
    health(manifest["apiUrl"])
    seed(manifest, root)
    print(json.dumps({"state": "synthetic_runtime_ready", "manifest": str(root / "manifest.json"), "fixture": str(root / "fixture.json"), "apiUrl": manifest["apiUrl"], "jarSha256": args.jar_sha256}))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    create = commands.add_parser("up")
    create.add_argument("--jar", required=True); create.add_argument("--jar-sha256", required=True)
    create.add_argument("--private-root", required=True); create.add_argument("--port", type=int, default=18087)
    status = commands.add_parser("verify"); status.add_argument("--manifest", required=True)
    proxy = commands.add_parser("serve"); proxy.add_argument("--manifest", required=True)
    complete = commands.add_parser("seed"); complete.add_argument("--manifest", required=True)
    halt = commands.add_parser("stop"); halt.add_argument("--manifest", required=True)
    quiet = commands.add_parser("quiesce"); quiet.add_argument("--manifest", required=True)
    evidence = commands.add_parser("proof"); evidence.add_argument("--manifest", required=True)
    args = parser.parse_args()
    if args.command == "up":
        up(args)
    elif args.command == "serve":
        serve(Path(args.manifest))
    elif args.command == "seed":
        path = Path(args.manifest); value = verify_manifest(path); health(value["apiUrl"]); seed(value, path.parent)
        print(json.dumps({"state": "synthetic_fixture_ready", "fixture": str(path.parent / "fixture.json")}))
    elif args.command == "stop":
        stop(Path(args.manifest)); print(json.dumps({"state": "owned_synthetic_runtime_stopped", "filesRetained": True}))
    elif args.command == "quiesce":
        stop(Path(args.manifest), app_only=True); print(json.dumps({"state": "owned_synthetic_app_quiesced", "databaseAndFilesRetained": True}))
    elif args.command == "proof":
        print(json.dumps(proof(verify_manifest(Path(args.manifest))), indent=2))
    else:
        value = verify_manifest(Path(args.manifest)); print(json.dumps({"state": "isolation_verified", "prefix": value["prefix"], "apiUrl": value["apiUrl"]}))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError, OSError) as error:
        raise SystemExit(str(error)) from None
