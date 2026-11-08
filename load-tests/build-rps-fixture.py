#!/usr/bin/env python3
"""Generate synthetic fixtures/SQL only. Never connects to a database or HTTP origin."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import uuid


def synthetic_iins(count):
    result = []
    for serial in range(10000):
        prefix = f"0001015{serial:04d}"
        digits = list(map(int, prefix))
        checksum = sum(a * b for a, b in zip(digits, range(1, 12))) % 11
        if checksum == 10:
            checksum = sum(a * b for a, b in zip(digits, [3, 4, 5, 6, 7, 8, 9, 10, 11, 1, 2])) % 11
        if checksum != 10:
            result.append(prefix + str(checksum))
            if len(result) == count:
                return result
    raise ValueError("Synthetic IIN space exhausted")


def generate(run_id, count=500, unique_device_ids=False):
    if not re.fullmatch(r"[a-z0-9_]{1,32}", run_id) or not 1 <= count <= 500:
        raise ValueError("run-id must be 1..32 lowercase ASCII letters/digits/underscores; desks 1..500")
    devices = []
    for index, iin in enumerate(synthetic_iins(count), 1):
        devices.append({
            "deviceId": f"load-kassa-{index:03d}" if unique_device_ids else "KASSA",
            "pharmacyId": f"rps-load-{run_id}-{index:03d}",
            "key": secrets.token_urlsafe(32),
            "iin": iin,
            "phone": f"+7700{index:07d}",
            "name": f"RPS Synthetic {index:03d}",
            "password": secrets.token_urlsafe(12),
        })
    prefix = "rps-load-" + run_id
    barcode_seed = int(hashlib.sha256(run_id.encode()).hexdigest()[:8], 16) % 1000000000
    def barcode(number):
        first = f"487{number:09d}"
        checksum = -sum(int(digit) * (1 if index % 2 == 0 else 3) for index, digit in enumerate(first)) % 10
        return first + str(checksum)
    background = {"triggerSku": prefix + "-trigger", "triggerBarcode": barcode(barcode_seed),
                  "recommendSku": prefix + "-recommend", "recommendBarcode": barcode((barcode_seed + 1) % 1000000000),
                  "recommendPrice": 2000, "bonus": 125, "ruleId": prefix + "-rule",
                  "playlistId": prefix + "-playlist", "slideId": prefix + "-slide",
                  "slideUrl": "http://127.0.0.1:8080/synthetic/" + prefix + "-slide.mp4"}
    return {"kind": "epharm-isolated-rps-load-v1", "runId": run_id,
            "expectedDatabase": f"epharm_rps_load_{run_id}", "background": background, "devices": devices}


def sql_literal(value):
    return "'" + value.replace("'", "''") + "'"


def guard(database):
    return ("DO $$ BEGIN IF current_database() <> " + sql_literal(database) +
            " THEN RAISE EXCEPTION 'Refusing non-fixture database'; END IF; END $$;\n")


def seed_sql(fixture):
    chain = "rps-load-" + fixture["runId"]
    background = fixture["background"]
    statements = ["\\set ON_ERROR_STOP on", "BEGIN;", guard(fixture["expectedDatabase"]),
                  "CREATE TABLE rps_load_expected(iin text PRIMARY KEY, phone text UNIQUE NOT NULL, name text NOT NULL, pharmacy_id text NOT NULL);",
                  "INSERT INTO chains(id,name,\"group\") VALUES (" + sql_literal(chain) + ",'RPS isolated load','pilot');"]
    for sku, barcode, name, price in [(background["triggerSku"], background["triggerBarcode"], "RPS Synthetic trigger", 1500),
                                     (background["recommendSku"], background["recommendBarcode"], "RPS Synthetic recommendation", background["recommendPrice"])]:
        statements.append("INSERT INTO products(id,name,brand,vendor,mnn,price,barcode) VALUES (" + ",".join(map(sql_literal, [sku, name, "Synthetic", "Synthetic", ""])) + f",{price}," + sql_literal(barcode) + ");")
    trigger = json.dumps({"kind": "product", "value": background["triggerSku"]})
    statements.append("INSERT INTO rules(id,type,status,trigger,recommend,bonus,script,created_by) VALUES (" + sql_literal(background["ruleId"]) + ",'substitution','active'," + sql_literal(trigger) + "::jsonb," + sql_literal(background["recommendSku"]) + f",{background['bonus']},'Synthetic load scenario','isolated-load');")
    statements.append("INSERT INTO playlists(id,name,status,slides_count,duration_sec) VALUES (" + sql_literal(background["playlistId"]) + ",'RPS Synthetic playlist','active',1,15);")
    statements.append("INSERT INTO slides(id,title,kind,duration_sec,media_url,playlist_id,position) VALUES (" + sql_literal(background["slideId"]) + ",'RPS Synthetic video','video',15," + sql_literal(background["slideUrl"]) + "," + sql_literal(background["playlistId"]) + ",0);")
    for desk in fixture["devices"]:
        pharmacy = sql_literal(desk["pharmacyId"])
        statements.append("INSERT INTO pharmacies(id,name,chain_id,chain_name,city,addr,\"group\") VALUES (" + pharmacy + "," + pharmacy + "," + sql_literal(chain) + ",'RPS isolated load','Synthetic','Synthetic','pilot');")
        digest = hashlib.sha256(desk["key"].encode()).hexdigest()
        statements.append("INSERT INTO fulfillment_devices(id,device_id,pharmacy_id,secret_sha256,secret_hint) VALUES (" + ",".join(map(sql_literal, [str(uuid.uuid4()), desk["deviceId"], desk["pharmacyId"], digest, "load-only"])) + ");")
        statements.append("INSERT INTO rps_load_expected(iin,phone,name,pharmacy_id) VALUES (" + ",".join(map(sql_literal, [desk["iin"], desk["phone"], desk["name"], desk["pharmacyId"]])) + ");")
        statements.append("INSERT INTO playlist_pharmacy_assignments(pharmacy_id,playlist_id) VALUES (" + pharmacy + "," + sql_literal(background["playlistId"]) + ");")
    statements.append("CREATE TABLE rps_load_financial_baseline AS SELECT (SELECT count(*) FROM pharmacist_wallet_entries) AS wallet_rows,(SELECT count(*) FROM posm_pharmacist_mappings) AS seller_rows;")
    statements.append("COMMIT;")
    return "\n".join(statements) + "\n"


def verification_sql(fixture):
    count = len(fixture["devices"])
    background = fixture["background"]
    return "\\set ON_ERROR_STOP on\nBEGIN READ ONLY;\n" + guard(fixture["expectedDatabase"]) + f"""
DO $$
BEGIN
  IF (SELECT count(*) FROM rps_load_expected) <> {count} THEN RAISE EXCEPTION 'Fixture cardinality changed'; END IF;
  IF (SELECT count(*) FROM pharmacists p JOIN rps_load_expected e ON p.iin=e.iin
      WHERE p.phone=e.phone AND p.name=e.name AND p.pharmacy_id=e.pharmacy_id
      AND p.status='active' AND p.phone_verification_status='self_declared'
      AND p.password_hash LIKE '$2%') <> {count}
    THEN RAISE EXCEPTION 'Missing, duplicate, inactive, misassigned or invalid credential profile'; END IF;
  IF (SELECT count(*) FROM pharmacists WHERE name LIKE 'RPS Synthetic %') <> {count}
    THEN RAISE EXCEPTION 'Unexpected extra synthetic profile'; END IF;
  IF EXISTS (SELECT 1 FROM posm_device_staff_sessions s JOIN pharmacists p ON p.id=s.pharmacist_id
      JOIN rps_load_expected e ON e.iin=p.iin WHERE s.pharmacy_id<>e.pharmacy_id)
    THEN RAISE EXCEPTION 'Grant assigned to wrong pharmacy'; END IF;
  IF EXISTS (SELECT 1 FROM posm_device_staff_sessions s JOIN pharmacists p ON p.id=s.pharmacist_id
      JOIN rps_load_expected e ON e.iin=p.iin WHERE s.revoked_at IS NULL AND s.expires_at>now())
    THEN RAISE EXCEPTION 'Synthetic session was not logged out'; END IF;
  IF EXISTS (SELECT 1 FROM rps_load_financial_baseline b
      WHERE b.wallet_rows<>(SELECT count(*) FROM pharmacist_wallet_entries)
      OR b.seller_rows<>(SELECT count(*) FROM posm_pharmacist_mappings))
    THEN RAISE EXCEPTION 'Registration changed financial/seller data'; END IF;
  IF NOT EXISTS (SELECT 1 FROM rules WHERE id={sql_literal(background['ruleId'])}
      AND status='active' AND recommend={sql_literal(background['recommendSku'])})
    THEN RAISE EXCEPTION 'Positive recommendation rule missing'; END IF;
  IF (SELECT count(*) FROM playlist_pharmacy_assignments a JOIN rps_load_expected e ON e.pharmacy_id=a.pharmacy_id
      WHERE a.playlist_id={sql_literal(background['playlistId'])}) <> {count}
    OR NOT EXISTS (SELECT 1 FROM slides WHERE id={sql_literal(background['slideId'])}
      AND playlist_id={sql_literal(background['playlistId'])} AND position=0)
    THEN RAISE EXCEPTION 'Positive playlist assignment/slide missing'; END IF;
  IF NOT EXISTS (SELECT 1 FROM recommendation_events WHERE rule_id={sql_literal(background['ruleId'])}
      AND trigger_sku={sql_literal(background['triggerSku'])} AND recommend_sku={sql_literal(background['recommendSku'])}
      AND expected_amount={background['recommendPrice']} AND bonus={background['bonus']})
    THEN RAISE EXCEPTION 'No positive recommendation event was persisted'; END IF;
END $$;
SELECT {count} AS verified_active_profiles, {count} AS correctly_assigned_pharmacies,
       true AS no_extra_profiles, true AS no_active_load_grants, true AS financial_rows_unchanged,
       (SELECT count(*) FROM recommendation_events WHERE rule_id={sql_literal(background['ruleId'])}) AS positive_recommendation_events;
ROLLBACK;
"""


def exclusive(path, text):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, "w") as output:
        output.write(text)


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--desks", type=int, default=500)
    parser.add_argument("--unique-device-ids", action="store_true")
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    fixture = generate(args.run_id, args.desks, args.unique_device_ids)
    args.output_dir.mkdir(mode=0o700, parents=True, exist_ok=False)
    exclusive(args.output_dir / "fixture.private.json", json.dumps(fixture, indent=2) + "\n")
    exclusive(args.output_dir / "seed.private.sql", seed_sql(fixture))
    exclusive(args.output_dir / "verify.sql", verification_sql(fixture))
    exclusive(args.output_dir / "device-keys.private.txt", "\n".join(x["key"] for x in fixture["devices"]) + "\n")
    exclusive(args.output_dir / "pharmacy-ids.txt", ",".join(x["pharmacyId"] for x in fixture["devices"]) + "\n")
    exclusive(args.output_dir / "background.expected.json", json.dumps({"runId": fixture["runId"], "background": fixture["background"]}, indent=2) + "\n")
    print(json.dumps({"state": "generated-only-no-external-actions", "desks": args.desks,
                      "expectedDatabase": fixture["expectedDatabase"],
                      "deviceIds": "distinct" if args.unique_device_ids else "KASSA shared across pharmacies"}))


if __name__ == "__main__":
    main()
