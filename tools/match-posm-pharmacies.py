#!/usr/bin/env python3
"""Match a private POSM rollout inventory to the production pharmacy catalog.

The inventory JSON is intentionally kept outside Git.  This utility emits no
AnyDesk identifiers and refuses to auto-select a candidate unless the match is
both strong and clearly separated from the runner-up.
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import unicodedata
from difflib import SequenceMatcher
from pathlib import Path


STOP_WORDS = {
    "аптека",
    "аптечный",
    "г",
    "город",
    "д",
    "дом",
    "здание",
    "зд",
    "корпус",
    "корп",
    "помещение",
    "пом",
    "нп",
    "ул",
    "улица",
}


def normalize(value: object) -> str:
    text = unicodedata.normalize("NFKC", str(value or "")).lower().replace("ё", "е")
    text = text.replace("қ", "к").replace("ғ", "г").replace("ү", "у")
    text = text.replace("ұ", "у").replace("і", "и").replace("ң", "н")
    text = re.sub(r"(?<=\d)[\\/-](?=\d)", " ", text)
    text = re.sub(r"[^0-9a-zа-я]+", " ", text)
    return " ".join(part for part in text.split() if part not in STOP_WORDS)


def tokens(value: object) -> set[str]:
    return set(normalize(value).split())


def token_score(left: object, right: object) -> float:
    a, b = tokens(left), tokens(right)
    if not a or not b:
        return 0.0
    intersection = len(a & b)
    return 2.0 * intersection / (len(a) + len(b))


def sequence_score(left: object, right: object) -> float:
    a, b = normalize(left), normalize(right)
    if not a or not b:
        return 0.0
    return SequenceMatcher(None, a, b).ratio()


def candidate_score(source: dict[str, object], pharmacy: dict[str, str]) -> float:
    src_name = source.get("pharmacy", "")
    src_address = source.get("address", "")
    src_city = source.get("city", "")
    prod_name = pharmacy["name"]
    prod_address = pharmacy["address"]
    prod_city = pharmacy["city"]

    name_score = max(sequence_score(src_name, prod_name), token_score(src_name, prod_name))
    address_score = max(
        sequence_score(src_address, prod_address),
        token_score(src_address, prod_address),
    )
    city_score = max(sequence_score(src_city, prod_city), token_score(src_city, prod_city))
    combined_source = f"{src_name} {src_city} {src_address}"
    combined_prod = f"{prod_name} {prod_city} {prod_address}"
    combined_score = max(
        sequence_score(combined_source, combined_prod),
        token_score(combined_source, combined_prod),
    )

    # Address and the full production name are the most reliable identifiers.
    # City is only a guardrail because some imported production rows use "—".
    return max(
        combined_score,
        0.58 * name_score + 0.34 * address_score + 0.08 * city_score,
        0.70 * address_score + 0.30 * city_score,
    )


def read_pharmacies(path: Path) -> list[dict[str, str]]:
    rows: list[dict[str, str]] = []
    with path.open(encoding="utf-8", newline="") as handle:
        reader = csv.reader(handle, delimiter="\t")
        for row_number, row in enumerate(reader, 1):
            if len(row) != 5:
                raise ValueError(f"{path}:{row_number}: expected 5 TSV columns, got {len(row)}")
            pharmacy_id, name, city, address, active = row
            if active.lower() not in {"t", "true", "1"}:
                continue
            rows.append(
                {
                    "id": pharmacy_id.strip(),
                    "name": name.strip(),
                    "city": city.strip(),
                    "address": address.strip(),
                }
            )
    return rows


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--inventory", required=True, type=Path)
    parser.add_argument("--pharmacies", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--min-score", type=float, default=0.82)
    parser.add_argument("--min-margin", type=float, default=0.035)
    args = parser.parse_args()

    inventory = json.loads(args.inventory.read_text(encoding="utf-8"))
    pharmacies = read_pharmacies(args.pharmacies)
    matches: list[dict[str, object]] = []

    for source in inventory:
        exact_name_matches = [
            pharmacy
            for pharmacy in pharmacies
            if normalize(source.get("pharmacy", "")) == normalize(pharmacy["name"])
        ]
        ranked = sorted(
            (
                {"score": candidate_score(source, pharmacy), **pharmacy}
                for pharmacy in pharmacies
            ),
            key=lambda item: (-float(item["score"]), str(item["id"])),
        )
        best = ranked[0]
        if len(exact_name_matches) == 1:
            exact = exact_name_matches[0]
            best = next(candidate for candidate in ranked if candidate["id"] == exact["id"])
            accepted = True
            match_method = "unique_normalized_name"
        else:
            runner_up = ranked[1]
            accepted = (
                float(best["score"]) >= args.min_score
                and float(best["score"]) - float(runner_up["score"]) >= args.min_margin
            )
            match_method = "fuzzy" if accepted else "review"
        runner_up = next(candidate for candidate in ranked if candidate["id"] != best["id"])
        margin = float(best["score"]) - float(runner_up["score"])
        matches.append(
            {
                "sourceRow": source["row"],
                "sourcePharmacy": source["pharmacy"],
                "sourceCity": source["city"],
                "sourceAddress": source["address"],
                "accepted": accepted,
                "matchMethod": match_method,
                "score": round(float(best["score"]), 6),
                "margin": round(margin, 6),
                "pharmacyId": best["id"] if accepted else None,
                "pharmacyName": best["name"] if accepted else None,
                "pharmacyCity": best["city"] if accepted else None,
                "pharmacyAddress": best["address"] if accepted else None,
                "candidates": [
                    {
                        "score": round(float(candidate["score"]), 6),
                        "id": candidate["id"],
                        "name": candidate["name"],
                        "city": candidate["city"],
                        "address": candidate["address"],
                    }
                    for candidate in ranked[:3]
                ],
            }
        )

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(matches, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    accepted_count = sum(bool(item["accepted"]) for item in matches)
    print(json.dumps({"total": len(matches), "accepted": accepted_count, "review": len(matches) - accepted_count}))
    return 0 if accepted_count == len(matches) else 2


if __name__ == "__main__":
    raise SystemExit(main())
