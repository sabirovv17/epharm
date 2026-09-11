"""Strict, loss-accounted Standard N daily-file validation; no database writes."""
import argparse
import csv
import hashlib
import json
import math
import os
import re
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from functools import lru_cache

HEADER = ["PART_ID", "WARE_ID", "SNAME", "G$PROFILE_ID", "CAPTION", "RETURN",
          "GOODS_RECEIVED", "GOOD_TRANSFERRED", "TRANSFERRED_OUT", "ADJUSTMENTS",
          "SALES", "RETAIL_PRICE", "COST_PRICE", "STOCK_AT_THE_BEGINNING",
          "STOCK_AT_THE_END", "SUPPLIER", "SERIES", "EXPIRATION_DATE", "QUANT_OPT", "REVENUE"]
TEXT_FIELDS = {2, 4, 15, 16}
NUMERIC_FIELDS = {5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 18, 19}
OPTIONAL_NUMERIC = {11, 12, 19}
UUID_RE = re.compile(r"[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}\Z")
NUMBER_RE = re.compile(r"[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?\Z")
SURROGATE_RE = re.compile("[\udc80-\udcff]")


class ValidationError(RuntimeError):
    pass


def durable_json(path, document):
    path = Path(path)
    temporary = path.with_name(path.name + f'.tmp.{os.getpid()}')
    with temporary.open('w', encoding='utf-8') as handle:
        json.dump(document, handle, ensure_ascii=False, indent=2)
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(temporary, path)
    if os.name != 'nt':
        directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try: os.fsync(directory)
        finally: os.close(directory)


def sha256(path):
    with Path(path).open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def escape_tsv(value):
    return value.replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n")


@lru_cache(maxsize=100000)
def normalize_expiry(value):
    if not value:
        return ""
    if "/" in value:
        for fmt in ("%m/%d/%Y", "%m/%d/%Y %I:%M:%S %p"):
            try:
                return datetime.strptime(value, fmt).strftime("%Y-%m-%d %H:%M:%S")
            except ValueError:
                continue
        raise ValidationError("invalid US-format expiration date")
    try:
        return datetime.fromisoformat(value).strftime("%Y-%m-%d %H:%M:%S")
    except ValueError as exc:
        raise ValidationError("invalid expiration date") from exc


def normalize_row(row, record, quality, audit):
    if len(row) != len(HEADER):
        # Do not stitch arbitrary rows together or discard financial records.
        raise ValidationError(f"record {record}: expected 20 fields, got {len(row)}")
    values = list(row)
    for index, value in enumerate(values):
        if "\udc98" in value:
            if index not in TEXT_FIELDS:
                raise ValidationError(f"record {record}: undefined cp1251 byte in {HEADER[index]}")
            count = value.count("\udc98")
            audit({"record": record, "field": HEADER[index], "issue": "undefined_cp1251_98",
                   "original_bytes_hex": value.encode("cp1251", errors="surrogateescape").hex(),
                   "replacement": "U+FFFD", "count": count})
            values[index] = value.replace("\udc98", "\ufffd")
            quality["undefined_cp1251_98_replaced"] += count
        if SURROGATE_RE.search(values[index]):
            raise ValidationError(f"record {record}: unsupported undecodable byte in {HEADER[index]}")
        if "\x00" in values[index]:
            raise ValidationError(f"record {record}: NUL byte in {HEADER[index]}")
    for index, maximum in ((0, 2**64-1), (3, 2**32-1)):
        raw = values[index].strip()
        if not raw.isascii() or not raw.isdecimal() or int(raw) > maximum:
            raise ValidationError(f"record {record}: invalid integer {HEADER[index]}")
        if index == 3 and int(raw) == 0:
            raise ValidationError(f"record {record}: zero pharmacy identifier")
        values[index] = str(int(raw))
    for index in NUMERIC_FIELDS:
        raw = values[index].strip()
        if raw == "" and index in OPTIONAL_NUMERIC:
            quality["missing_" + HEADER[index].lower()] += 1
            values[index] = ""
            continue
        if not NUMBER_RE.fullmatch(raw) or not math.isfinite(float(raw)):
            raise ValidationError(f"record {record}: invalid number {HEADER[index]}")
        values[index] = raw
    ware = values[1].strip()
    if UUID_RE.fullmatch(ware):
        values[1] = ware.lower()
    else:
        # Preserve the complete source row in PIM and its original identity.
        # Invalid/absent IDs are NEVER invented and must not enter product exports.
        quality["invalid_ware_rows" if ware else "blank_ware_rows"] += 1
        audit({"record": record, "issue": "invalid_ware_id" if ware else "blank_ware_id",
               "ware_id": ware, "pharmacy_id": values[3], "part_id": values[0]})
        values[1] = ware
    if not values[2].strip() and UUID_RE.fullmatch(ware):
        raise ValidationError(f"record {record}: missing product name")
    try:
        values[17] = normalize_expiry(values[17].strip())
    except ValidationError as exc:
        raise ValidationError(f"record {record}: invalid expiration date") from exc
    if not values[17]:
        quality["missing_expiration_date"] += 1
    return values


def normalize_records(rows, output, quality, audit, interrupt_after=None):
    try:
        header = next(rows)
    except StopIteration as exc:
        raise ValidationError("empty source") from exc
    if header != HEADER:
        raise ValidationError("source header does not match the 20-column contract")
    output.write("\t".join(HEADER) + "\n")
    count = 0
    products, pharmacies, keys = set(), set(), set()
    for record, row in enumerate(rows, 1):
        if interrupt_after is not None and record > interrupt_after:
            raise InterruptedError("simulated interrupted normalization")
        values = normalize_row(row, record, quality, audit)
        key = (int(values[3]), int(values[0]))
        if key in keys:
            raise ValidationError(f"record {record}: duplicate pharmacy/PART_ID snapshot row")
        keys.add(key)
        pharmacies.add(values[3])
        if UUID_RE.fullmatch(values[1]):
            products.add(values[1])
        output.write("\t".join(escape_tsv(value) for value in values) + "\n")
        count += 1
    if count == 0:
        raise ValidationError("source contains no product/batch rows")
    return {"rows": count, "valid_unique_ware_ids": len(products),
            "pharmacies": len(pharmacies), "duplicate_pharmacy_part_keys": 0,
            "discarded_rows": 0}


def validate_file(source, artifact_root, expected_bytes=None, interrupt_after=None):
    source = Path(source)
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}\.csv", source.name):
        raise ValidationError("invalid daily source file name")
    datetime.strptime(source.stem, "%Y-%m-%d")
    before = source.stat()
    if expected_bytes is not None and before.st_size != expected_bytes:
        raise ValidationError("download size differs from SMB source size")
    source_sha = sha256(source)
    artifact = Path(artifact_root) / f"{source.stem}-{source_sha[:16]}"
    manifest_path = artifact / "manifest.json"
    if manifest_path.exists():
        cached = json.loads(manifest_path.read_text())
        if (cached.get("validation_status") == "complete" and cached.get("source_sha256") == source_sha
                and sha256(artifact / "normalized.tsv") == cached.get("normalized_sha256")):
            return cached, artifact
        raise ValidationError("existing artifact is incomplete or changed; use a new artifact directory")
    # Snapshot contents include commercially sensitive stock and price data.
    # Protect the directory even when the CLI is run outside the systemd UMask.
    artifact.mkdir(parents=True, exist_ok=False, mode=0o700)
    quality = Counter()
    base = {"schema_version": 1, "snapshot_date": source.stem, "source_file": source.name,
            "source_bytes": before.st_size, "source_sha256": source_sha,
            "observed_at": datetime.now(timezone.utc).isoformat(), "encoding": "cp1251",
            "normalized_format": "UTF-8 ClickHouse TabSeparatedWithNames with backslash escapes",
            "nullable_source_numeric_fields": [HEADER[index] for index in sorted(OPTIONAL_NUMERIC)],
            "invalid_identity_policy": "preserve every PIM row; exclude invalid/blank IDs from Medusa exports"}
    try:
        csv.field_size_limit(32 * 1024 * 1024)
        with source.open(encoding="cp1251", errors="surrogateescape", newline="") as handle, \
                (artifact / "normalized.tsv.partial").open("w", encoding="utf-8", newline="") as output, \
                (artifact / "quality-audit.ndjson").open("w", encoding="utf-8") as audit_file:
            def audit(event):
                audit_file.write(json.dumps(event, ensure_ascii=True) + "\n")
            counts = normalize_records(iter(csv.reader(handle, delimiter="\t")), output, quality, audit,
                                       interrupt_after=interrupt_after)
            output.flush()
            os.fsync(output.fileno())
        after = source.stat()
        if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns) or sha256(source) != source_sha:
            raise ValidationError("source changed during complete-file validation")
        partial = artifact / "normalized.tsv.partial"
        normalized_sha = sha256(partial)
        partial.replace(artifact / "normalized.tsv")
        manifest = {**base, **counts, "quality": dict(quality), "normalized_sha256": normalized_sha,
                    "normalized_bytes": (artifact / "normalized.tsv").stat().st_size,
                    "validation_status": "complete"}
        durable_json(manifest_path, manifest)
        return manifest, artifact
    except BaseException as exc:
        # No complete manifest/normalized file is produced on interruption or malformed input.
        durable_json(artifact / "failure.json", {**base, "validation_status": "failed",
             "error_type": type(exc).__name__, "error": str(exc), "quality": dict(quality)})
        raise


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("--artifact-root", type=Path, required=True)
    parser.add_argument("--expected-bytes", type=int)
    args = parser.parse_args()
    result, folder = validate_file(args.source, args.artifact_root, args.expected_bytes)
    print(json.dumps({**result, "artifact_path": str(folder)}, ensure_ascii=False))
