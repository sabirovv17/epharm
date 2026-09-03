#!/usr/bin/env python3
"""Discover Europharma images for catalog products by exact barcode only."""

from __future__ import annotations

import argparse
import json
import re
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from html.parser import HTMLParser
from pathlib import Path
from threading import Lock
from urllib.parse import urljoin, urlparse


USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 Chrome/138.0 Safari/537.36"
)
BARCODE_LENGTHS = {8, 13, 14}


def normalize_barcode(value: object) -> str | None:
    digits = re.sub(r"\D", "", str(value or ""))
    if len(digits) in BARCODE_LENGTHS:
        return digits
    if 9 <= len(digits) <= 12:
        return digits.zfill(13)
    if 1 <= len(digits) <= 7:
        return digits.zfill(8)
    return None


def fetch_text(url: str, retries: int = 3) -> str:
    last_error: Exception | None = None
    for attempt in range(retries):
        try:
            request = urllib.request.Request(
                url,
                headers={"User-Agent": USER_AGENT, "Accept": "text/html"},
            )
            with urllib.request.urlopen(request, timeout=30) as response:
                return response.read().decode("utf-8", "replace")
        except Exception as error:  # network retry boundary
            last_error = error
            time.sleep(0.4 * (attempt + 1))
    assert last_error is not None
    raise last_error


class CatalogLinkParser(HTMLParser):
    def __init__(self, base_url: str) -> None:
        super().__init__(convert_charrefs=True)
        self.base_url = base_url
        self.links: set[str] = set()

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag.lower() != "a":
            return
        values = dict(attrs)
        classes = set((values.get("class") or "").split())
        href = values.get("href")
        if "card-product__link" not in classes or not href:
            return
        url = urljoin(self.base_url, href)
        parsed = urlparse(url)
        if parsed.scheme == "https" and parsed.hostname and parsed.hostname.endswith("europharma.kz"):
            self.links.add(url)


class ProductMetaParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.meta: dict[str, str] = {}

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag.lower() != "meta":
            return
        values = dict(attrs)
        itemprop = (values.get("itemprop") or "").lower()
        content = values.get("content")
        if itemprop and content is not None and itemprop not in self.meta:
            self.meta[itemprop] = content


def parse_catalog_links(html: str, base_url: str) -> set[str]:
    parser = CatalogLinkParser(base_url)
    parser.feed(html)
    return parser.links


def parse_product(html: str) -> dict[str, str | None] | None:
    parser = ProductMetaParser()
    parser.feed(html)
    barcode = normalize_barcode(parser.meta.get("mpn"))
    image = parser.meta.get("image")
    name = parser.meta.get("name")
    if not barcode or not image or not name:
        return None
    image_url = urlparse(image)
    if image_url.scheme != "https" or image_url.hostname != "st.europharma.kz":
        return None
    return {
        "barcode": barcode,
        "raw_barcode": re.sub(r"\D", "", parser.meta.get("mpn", "")),
        "image": image,
        "name": name,
        "brand": parser.meta.get("brand"),
        "sku": parser.meta.get("sku"),
    }


def read_inventory(path: Path) -> list[dict[str, object]]:
    products: list[dict[str, object]] = []
    with path.open("r", encoding="utf-8") as stream:
        for line in stream:
            if line.strip():
                product = json.loads(line)
                if product.get("barcode"):
                    products.append(product)
    return products


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--inventory", required=True, type=Path)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--catalog-base", default="https://oral.europharma.kz/ru")
    parser.add_argument("--max-pages", type=int, default=250)
    parser.add_argument("--concurrency", type=int, default=12)
    args = parser.parse_args()

    base = args.catalog_base.rstrip("/")
    parsed_base = urlparse(base)
    if parsed_base.scheme != "https" or not parsed_base.hostname or not parsed_base.hostname.endswith("europharma.kz"):
        raise SystemExit("catalog-base must be an HTTPS europharma.kz URL")
    max_pages = min(1000, max(1, args.max_pages))
    concurrency = min(24, max(1, args.concurrency))
    products = read_inventory(args.inventory)
    by_barcode: dict[str, list[dict[str, object]]] = {}
    for product in products:
        by_barcode.setdefault(str(product["barcode"]), []).append(product)

    product_urls: set[str] = set()
    lock = Lock()

    def load_catalog(page: int) -> int:
        links = parse_catalog_links(fetch_text(f"{base}/catalog?page={page}"), base)
        with lock:
            product_urls.update(links)
        return len(links)

    catalog_pages_fetched = 0
    with ThreadPoolExecutor(max_workers=concurrency) as executor:
        futures = [executor.submit(load_catalog, page) for page in range(1, max_pages + 1)]
        for future in as_completed(futures):
            try:
                future.result()
                catalog_pages_fetched += 1
            except Exception:
                pass

    urls = sorted(product_urls)
    matches: dict[str, dict[str, object]] = {}
    details_fetched = 0
    details_parsed = 0

    def load_product(url: str) -> tuple[str, dict[str, str | None] | None]:
        return url, parse_product(fetch_text(url))

    with ThreadPoolExecutor(max_workers=concurrency) as executor:
        futures = [executor.submit(load_product, url) for url in urls]
        for future in as_completed(futures):
            try:
                source_url, data = future.result()
                details_fetched += 1
            except Exception:
                continue
            if not data:
                continue
            details_parsed += 1
            for product in by_barcode.get(str(data["barcode"]), []):
                product_id = str(product["product_id"])
                if product_id in matches:
                    continue
                matches[product_id] = {
                    "event": "matched",
                    "source": "europharma",
                    "product_id": product_id,
                    "handle": product.get("handle"),
                    "title": product.get("title"),
                    "sku": product.get("sku"),
                    "variant_id": product.get("variant_id"),
                    "barcode": product.get("barcode"),
                    "raw_barcode": product.get("raw_barcode"),
                    "source_title": data["name"],
                    "source_brand": data["brand"],
                    "source_sku": data["sku"],
                    "source_barcode": data["raw_barcode"],
                    "source_url": source_url,
                    "image_url": data["image"],
                    "image_score": 100,
                    "status": "eligible",
                    "license": "Source: Europharma product card; commercial image reuse rights must be confirmed by the catalog owner",
                }
            if details_fetched and details_fetched % 250 == 0:
                print(json.dumps({"progress": details_fetched, "product_pages": len(urls), "exact_barcode_matches": len(matches)}), flush=True)

    args.manifest.parent.mkdir(parents=True, exist_ok=True)
    with args.manifest.open("w", encoding="utf-8", newline="\n") as stream:
        for match in matches.values():
            stream.write(json.dumps(match, ensure_ascii=False, separators=(",", ":")) + "\n")
    print(json.dumps({
        "missing_with_barcode": len(products),
        "catalog_pages_requested": max_pages,
        "catalog_pages_fetched": catalog_pages_fetched,
        "product_pages": len(urls),
        "details_fetched": details_fetched,
        "details_parsed": details_parsed,
        "exact_barcode_matches": len(matches),
        "manifest": str(args.manifest.resolve()),
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
