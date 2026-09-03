#!/usr/bin/env python3
"""Apply a reviewed image manifest to the local PostgreSQL catalog atomically."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import shutil
import subprocess
from datetime import datetime, timezone


UUID_FILE = re.compile(r"^[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}\.webp$")
PRODUCT_ID = re.compile(r"^prod_[A-Z0-9]+$")
BARCODE = re.compile(r"^\d{8,14}$")


def quote(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def run(command: list[str], *, input_text: str | None = None) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, input=input_text, text=True, check=True, capture_output=True)


def load_candidates(path: Path, image_dir: Path) -> list[dict]:
    candidates: list[dict] = []
    seen_products: set[str] = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        row = json.loads(line)
        if row.get("status") != "eligible" or row.get("event") not in {"matched", "verified"}:
            continue
        filename = str(row.get("filename") or "")
        product_id = str(row.get("product_id") or "")
        barcode = str(row.get("raw_barcode") or "")
        if not UUID_FILE.fullmatch(filename):
            raise ValueError(f"invalid filename: {filename}")
        if not PRODUCT_ID.fullmatch(product_id):
            raise ValueError(f"invalid product id: {product_id}")
        if not BARCODE.fullmatch(barcode):
            raise ValueError(f"invalid barcode for {product_id}")
        if product_id in seen_products:
            raise ValueError(f"duplicate product: {product_id}")
        source_url = str(row.get("source_url") or "")
        image_url = str(row.get("image_url") or "")
        if not source_url.startswith("https://") or not image_url.startswith("https://"):
            raise ValueError(f"non-HTTPS provenance for {product_id}")
        source_file = image_dir / filename
        if not source_file.is_file() or source_file.is_symlink() or source_file.stat().st_size <= 0:
            raise ValueError(f"missing image: {filename}")
        candidates.append({
            "product_id": product_id,
            "barcode": barcode,
            "filename": filename,
            "source": str(row.get("source") or "internet"),
            "source_url": source_url,
            "image_url": image_url,
            "license": str(row.get("license") or ""),
        })
        seen_products.add(product_id)
    if not candidates:
        raise ValueError("manifest contains no eligible verified candidates")
    return candidates


def build_sql(candidates: list[dict], url_prefix: str) -> str:
    values = []
    for row in candidates:
        metadata = json.dumps({
            "source": row["source"],
            "source_url": row["source_url"],
            "image_url": row["image_url"],
            "barcode": row["barcode"],
            "license": row["license"],
        }, ensure_ascii=False, separators=(",", ":"))
        values.append("(" + ",".join([
            quote(row["product_id"]), quote(row["barcode"]), quote(row["filename"]), quote(metadata),
        ]) + ")")
    prefix = url_prefix.rstrip("/") + "/"
    return f"""
BEGIN;
CREATE TEMP TABLE image_candidates (
  product_id text PRIMARY KEY,
  barcode text NOT NULL,
  filename text NOT NULL,
  metadata jsonb NOT NULL
) ON COMMIT DROP;
INSERT INTO image_candidates VALUES
{",\n".join(values)};

DO $$
DECLARE expected_count integer;
DECLARE changed_count integer;
BEGIN
  SELECT count(*) INTO expected_count FROM image_candidates;
  IF EXISTS (
    SELECT 1
    FROM image_candidates c
    LEFT JOIN catalog_products p ON p.id = c.product_id AND p.active
    WHERE p.id IS NULL
       OR p.thumbnail_url IS NOT NULL
       OR EXISTS (SELECT 1 FROM catalog_images i WHERE i.product_id = p.id)
       OR NOT EXISTS (
         SELECT 1 FROM catalog_variants v
         WHERE v.product_id = p.id AND v.active
           AND regexp_replace(coalesce(v.barcode, ''), '[^0-9]', '', 'g') = c.barcode
       )
  ) THEN
    RAISE EXCEPTION 'image candidate preflight failed';
  END IF;

  UPDATE catalog_products p
  SET thumbnail_url = {quote(prefix)} || c.filename, updated_at = now()
  FROM image_candidates c
  WHERE p.id = c.product_id AND p.active AND p.thumbnail_url IS NULL
    AND NOT EXISTS (SELECT 1 FROM catalog_images i WHERE i.product_id = p.id);
  GET DIAGNOSTICS changed_count = ROW_COUNT;
  IF changed_count <> expected_count THEN
    RAISE EXCEPTION 'updated % products, expected %', changed_count, expected_count;
  END IF;

  INSERT INTO catalog_images (id, product_id, url, position, metadata, source_seen_at, created_at, updated_at)
  SELECT 'internet_' || replace(c.filename, '.webp', ''), c.product_id,
         {quote(prefix)} || c.filename, 0, c.metadata, now(), now(), now()
  FROM image_candidates c;
  GET DIAGNOSTICS changed_count = ROW_COUNT;
  IF changed_count <> expected_count THEN
    RAISE EXCEPTION 'inserted % images, expected %', changed_count, expected_count;
  END IF;
END $$;
COMMIT;
"""


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--images", required=True, type=Path)
    parser.add_argument("--uploads", required=True, type=Path)
    parser.add_argument("--database", default="inkar_cms")
    parser.add_argument("--url-prefix", default="/api/uploads")
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()

    uploads = args.uploads.resolve(strict=True)
    if not uploads.is_dir() or not str(uploads).startswith("/var/www/inkar-shop-release-"):
        raise SystemExit(f"refusing upload directory: {uploads}")
    candidates = load_candidates(args.manifest, args.images)
    sql = build_sql(candidates, args.url_prefix)
    if not args.apply:
        print(json.dumps({"mode": "dry-run", "selected": len(candidates), "uploads": str(uploads)}))
        return

    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    backup = Path(f"/root/inkar-backups/inkar-cms-pre-images-{timestamp}.dump")
    backup.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    with backup.open("xb") as backup_stream:
        subprocess.run(
            ["sudo", "-u", "postgres", "pg_dump", "-Fc", "-d", args.database],
            check=True,
            stdout=backup_stream,
        )
    backup.chmod(0o600)
    copied: list[Path] = []
    try:
        for row in candidates:
            target = uploads / row["filename"]
            with (args.images / row["filename"]).open("rb") as source, target.open("xb") as destination:
                shutil.copyfileobj(source, destination)
            target.chmod(0o644)
            copied.append(target)
        run(["sudo", "-u", "postgres", "psql", "-v", "ON_ERROR_STOP=1", "-d", args.database], input_text=sql)
    except Exception:
        for target in copied:
            target.unlink(missing_ok=True)
        raise

    print(json.dumps({
        "mode": "apply",
        "applied": len(candidates),
        "backup": str(backup),
        "uploads": str(uploads),
    }))


if __name__ == "__main__":
    main()
