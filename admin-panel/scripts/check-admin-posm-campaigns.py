#!/usr/bin/env python3
import json
import urllib.request
from pathlib import Path


def load_env(path: Path) -> dict[str, str]:
    env: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        env[key] = value.strip().strip('"').strip("'")
    return env


def request_json(url: str, *, token: str | None = None, data: dict | None = None) -> dict | list:
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    body = json.dumps(data).encode("utf-8") if data is not None else None
    req = urllib.request.Request(url, data=body, headers=headers, method="POST" if data is not None else "GET")
    with urllib.request.urlopen(req, timeout=15) as response:
        return json.load(response)


def main() -> None:
    env = load_env(Path(".env.prod"))
    base = "https://" + env.get("ADMIN_DOMAIN", "epharm.inkar.kz")
    login = request_json(
        base + "/api/admin/auth/login",
        data={"email": env["ADMIN_BOOTSTRAP_EMAIL"], "password": env["ADMIN_BOOTSTRAP_PASSWORD"]},
    )
    token = login["tokens"]["accessToken"]

    promos = request_json(base + "/api/admin/promo?status=active", token=token)
    print(f"ACTIVE_PROMOS {len(promos)}")
    for promo in promos:
        print(
            "PROMO|{id}|{title}|{status}|product={product}|barcode={barcode}|ipart={ipart}|bonus={bonus}".format(
                id=promo["id"],
                title=promo["title"],
                status=promo["status"],
                product=promo["productName"],
                barcode=promo.get("barcode") or "",
                ipart=promo.get("ipartId") or "",
                bonus=promo.get("pharmacistBonus"),
            )
        )
        rules = request_json(base + f"/api/admin/promo/{promo['id']}/rules", token=token)
        config = rules["config"]
        print(
            f"RULES|{promo['id']}|ruleCount={rules['ruleCount']}|activeCount={rules['activeCount']}|"
            f"replacements={len(config['replacements'])}|crossSells={len(config['crossSells'])}"
        )
        for item in config["replacements"]:
            print(
                "  REPLACE|trigger={name}|barcode={barcode}|ipart={ipart}|active={active}".format(
                    name=item["name"],
                    barcode=item.get("barcode") or "",
                    ipart=item.get("ipartId") or "",
                    active=item.get("active"),
                )
            )
        for item in config["crossSells"]:
            print(
                "  CROSS|trigger={name}|barcode={barcode}|ipart={ipart}|active={active}".format(
                    name=item["name"],
                    barcode=item.get("barcode") or "",
                    ipart=item.get("ipartId") or "",
                    active=item.get("active"),
                )
            )


if __name__ == "__main__":
    main()
