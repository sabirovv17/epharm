#!/usr/bin/env python3
"""Build standalone, pharmacy-specific POSM installation archives.

Secrets are accepted only from a private token JSON file.  They are written to
the matching archive's posm.json and never to reports or stdout.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
import re
import shutil
import stat
import tempfile
import zipfile
from pathlib import Path


REQUIRED_PAYLOAD = {
    "CustomerDisplay.exe",
    "CustomerDisplay.dll",
    "CustomerDisplay.deps.json",
    "CustomerDisplay.runtimeconfig.json",
    "setup-autostart.bat",
    "install-tasks.ps1",
    "watchdog.ps1",
}


def safe_file_stem(value: str, max_bytes: int = 180) -> str:
    value = re.sub(r'[\\/:*?"<>|\x00-\x1f]+', " - ", value)
    value = re.sub(r"\s+", " ", value).strip(" .-") or "Аптека"
    while len(value.encode("utf-8")) > max_bytes:
        value = value[:-1].rstrip()
    return value


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def build_config(pharmacy_id: str, device_id: str, token: str) -> dict[str, object]:
    return {
        "enabled": True,
        "backendBaseUrl": "https://epharm.inkar.kz",
        "backendFallbackBaseUrls": [],
        "fulfillmentBaseUrl": "",
        "fulfillmentFallbackBaseUrls": [],
        "deviceKey": token,
        "deviceId": device_id,
        "pharmacistId": "",
        "pharmacyId": pharmacy_id,
        "screenMode": "prod",
        "videoEnabled": True,
        "recommendTimeoutMs": 5000,
        "debounceMs": 150,
        "recommendRefreshSec": 0,
        "popupAutoCloseSec": 30,
        "playlistPollSec": 20,
        "mediaCacheDir": r"C:\Epharm\media-cache",
        "updateEnabled": True,
        "updatePollSec": 300,
        "heartbeatPath": r"C:\Epharm\heartbeat.txt",
        "heartbeatSec": 15,
        "appLogPath": r"C:\Epharm\customerdisplay.log",
        "outboxDbPath": r"C:\Epharm\outbox.db",
        "outboxFlushSec": 5,
        "fulfillmentEnabled": True,
        "fulfillmentPollSec": 10,
        "fulfillmentCredentialPath": r"C:\Epharm\fulfillment-device.dat",
        "fulfillmentCachePath": r"C:\Epharm\fulfillment-orders.json",
        "receiptCaptureEnabled": True,
        "receiptCaptureDir": r"C:\Epharm\receipts",
        "fiscalReceiptInboxDir": r"C:\Epharm\fiscal-inbox",
        "fiscalReceiptTrustedSources": ["standardn-kkm-sdk", "ofd-api"],
        "fiscalReceiptPollSec": 2,
        "fiscalReceiptMaxClockSkewSec": 900,
        "fiscalReceiptMaxArtifactMb": 10,
        "receiptCaptureActiveRetentionDays": 2,
        "fiscalReceiptCompletedRetentionHours": 24,
        "standardNLogPaths": [],
        "standardNDbEnabled": True,
        "standardNDbHost": "localhost",
        "standardNDbPort": 3050,
        "standardNDbPath": "",
        "standardNDbUser": "SYSDBA",
        "standardNDbPassword": "masterkey",
        "standardNDbTimeoutMs": 1000,
        "standardNReceiptPollMs": 400,
    }


def add_tree(archive: zipfile.ZipFile, root: Path) -> None:
    for path in sorted(root.rglob("*")):
        if path.is_dir():
            continue
        relative = path.relative_to(root).as_posix()
        if relative == "posm.json":
            raise ValueError("common payload must not contain posm.json")
        if path.suffix.lower() in {".pdb", ".cs", ".csproj", ".user", ".suo"}:
            raise ValueError(f"development artifact is forbidden in payload: {relative}")
        archive.write(path, relative)


def load_inputs(inventory_path: Path, matches_path: Path, tokens_path: Path) -> list[dict[str, object]]:
    inventory = {int(row["row"]): row for row in json.loads(inventory_path.read_text(encoding="utf-8"))}
    matches = json.loads(matches_path.read_text(encoding="utf-8"))
    tokens = {
        (item["pharmacyId"], item["deviceId"]): item["token"]
        for item in json.loads(tokens_path.read_text(encoding="utf-8"))
    }

    if any(not match.get("accepted") for match in matches):
        raise ValueError("match report contains unresolved pharmacies")

    rows: list[dict[str, object]] = []
    used_ids: set[str] = set()
    used_tokens: set[str] = set()
    for match in matches:
        source = inventory[int(match["sourceRow"])]
        pharmacy_id = str(match["pharmacyId"])
        device_id = f"POSM-{pharmacy_id.removeprefix('sloc_')}"
        token = tokens.get((pharmacy_id, device_id))
        if not token:
            raise ValueError(f"missing provisioned token for {pharmacy_id}/{device_id}")
        if pharmacy_id in used_ids:
            raise ValueError(f"duplicate pharmacyId in rollout: {pharmacy_id}")
        if token in used_tokens:
            raise ValueError("the provisioning response contains a duplicate raw token")
        used_ids.add(pharmacy_id)
        used_tokens.add(token)
        rows.append({**source, **match, "deviceId": device_id, "token": token})
    return rows


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--inventory", required=True, type=Path)
    parser.add_argument("--matches", required=True, type=Path)
    parser.add_argument("--tokens", required=True, type=Path)
    parser.add_argument("--payload", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--version", required=True)
    args = parser.parse_args()

    if args.output.exists() and any(args.output.iterdir()):
        raise ValueError(f"output directory must be new or empty: {args.output}")
    args.output.mkdir(parents=True, exist_ok=True, mode=0o700)

    present = {path.name for path in args.payload.iterdir() if path.is_file()}
    missing = sorted(REQUIRED_PAYLOAD - present)
    if missing:
        raise ValueError(f"payload is incomplete: {', '.join(missing)}")

    rows = load_inputs(args.inventory, args.matches, args.tokens)
    install_text = (
        "УСТАНОВКА EPHARM POSM\r\n\r\n"
        "1. Полностью распакуйте ZIP в отдельную папку.\r\n"
        "2. Запустите setup-autostart.bat двойным кликом.\r\n"
        "3. Подтвердите запрос прав администратора и дождитесь сообщения [OK].\r\n\r\n"
        "После установки POSM запускается автоматически и получает подписанные обновления "
        "через https://epharm.inkar.kz. Повторная установка для обновлений не требуется.\r\n"
        "При ошибке отправьте C:\\Epharm\\install.log и "
        "C:\\Epharm\\install-status.json в техническую поддержку.\r\n"
    )

    with tempfile.TemporaryDirectory(prefix="epharm-posm-base-") as tmp:
        base_zip = Path(tmp) / "base.zip"
        with zipfile.ZipFile(
            base_zip,
            "w",
            compression=zipfile.ZIP_DEFLATED,
            compresslevel=1,
            allowZip64=True,
        ) as archive:
            add_tree(archive, args.payload)
            archive.writestr("INSTALL.txt", "\ufeff" + install_text)

        manifests: list[dict[str, object]] = []
        used_names: set[str] = set()
        for index, row in enumerate(rows, 1):
            stem = safe_file_stem(str(row["sourcePharmacy"]))
            filename = f"{stem} - POSM {args.version}.zip"
            if filename.casefold() in used_names:
                filename = f"{stem} - строка {row['sourceRow']} - POSM {args.version}.zip"
            used_names.add(filename.casefold())
            output_zip = args.output / filename
            shutil.copyfile(base_zip, output_zip)

            config = build_config(str(row["pharmacyId"]), str(row["deviceId"]), str(row["token"]))
            config_bytes = (json.dumps(config, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
            with zipfile.ZipFile(output_zip, "a", compression=zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("posm.json", config_bytes)
            os.chmod(output_zip, stat.S_IRUSR | stat.S_IWUSR)

            archive_sha = sha256(output_zip)
            manifests.append(
                {
                    "sourceRow": row["sourceRow"],
                    "package": filename,
                    "pharmacy": row["sourcePharmacy"],
                    "city": row["sourceCity"],
                    "address": row["sourceAddress"],
                    "anydesk": row.get("anydesk", ""),
                    "pharmacyId": row["pharmacyId"],
                    "deviceId": row["deviceId"],
                    "version": args.version,
                    "sizeBytes": output_zip.stat().st_size,
                    "sha256": archive_sha,
                }
            )

            with zipfile.ZipFile(output_zip) as archive:
                entries = set(archive.namelist())
                if not REQUIRED_PAYLOAD.issubset(entries) or "posm.json" not in entries:
                    raise ValueError(f"package verification failed: {filename}")
                saved = json.loads(archive.read("posm.json"))
                if saved["pharmacyId"] != row["pharmacyId"] or saved["deviceId"] != row["deviceId"]:
                    raise ValueError(f"package identity verification failed: {filename}")
                if saved["deviceKey"] != row["token"]:
                    raise ValueError(f"package token verification failed: {filename}")

            print(f"[{index:03d}/{len(rows):03d}] {filename}")

    manifest_path = args.output / "МАНИФЕСТ.csv"
    with manifest_path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(manifests[0]))
        writer.writeheader()
        writer.writerows(manifests)

    sums_path = args.output / "SHA256SUMS.txt"
    sums_path.write_text(
        "".join(f"{item['sha256']}  {item['package']}\n" for item in manifests),
        encoding="utf-8",
    )
    summary = {
        "version": args.version,
        "packages": len(manifests),
        "sourceRows": len(rows),
        "uniquePharmacies": len({str(row["pharmacyId"]) for row in rows}),
        "uniqueDevices": len({str(row["deviceId"]) for row in rows}),
        "withAnydesk": sum(bool(str(row.get("anydesk", "")).strip()) for row in rows),
        "withoutAnydesk": sum(not bool(str(row.get("anydesk", "")).strip()) for row in rows),
        "totalBytes": sum(int(item["sizeBytes"]) for item in manifests),
        "autoUpdate": {
            "enabled": True,
            "pollSeconds": 300,
            "signedManifestRequired": True,
            "pharmacyConfigPreserved": True,
        },
    }
    (args.output / "GENERATION_SUMMARY.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(summary, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
