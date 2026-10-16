from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import os
import uuid
from datetime import datetime, timedelta, timezone
from decimal import Decimal
from typing import Any
from urllib.parse import urlparse

from clickhouse_http import ClickHouseClient, DWH_ROOT, load_env_file


ZERO_HASH = "0" * 64
JOB_NAME = "storefront_incremental"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Incrementally load the 'Аптека со склада' PostgreSQL read model into DWH"
    )
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--full", action="store_true", help="Read all available source rows")
    mode.add_argument("--since", type=parse_timestamp, help="Override the lower watermark (ISO-8601)")
    parser.add_argument("--batch-size", type=int, default=2_000)
    return parser.parse_args()


def parse_timestamp(value: str) -> datetime:
    normalized = value.strip().replace(" ", "T").replace("Z", "+00:00")
    parsed = datetime.fromisoformat(normalized)
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def iso(value: datetime | None) -> str | None:
    if value is None:
        return None
    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc).isoformat(timespec="milliseconds")


def text(value: Any, maximum: int = 1_024) -> str:
    return str(value or "").strip()[:maximum]


def decimal_text(value: Any) -> str | None:
    if value is None or value == "":
        return None
    try:
        return format(Decimal(str(value)), "f")
    except Exception:
        return None


def json_object(value: Any) -> dict[str, Any]:
    if isinstance(value, dict):
        return value
    if isinstance(value, str):
        try:
            parsed = json.loads(value)
            return parsed if isinstance(parsed, dict) else {}
        except json.JSONDecodeError:
            return {}
    return {}


def hash_identifier(value: Any, salt: str) -> str:
    normalized = text(value, 512)
    if not normalized:
        return ZERO_HASH
    return hmac.new(salt.encode("utf-8"), normalized.encode("utf-8"), hashlib.sha256).hexdigest()


def property_value(properties: dict[str, Any], *names: str) -> Any:
    for name in names:
        if name in properties and properties[name] is not None:
            return properties[name]
    return None


def analytics_consent(snapshot: Any) -> bool:
    data = json_object(snapshot)
    value = data.get("analytics")
    if isinstance(value, dict):
        value = value.get("state") or value.get("status")
    return value is True or str(value or "").lower() in {"granted", "true", "1"}


def safe_page_path(value: Any) -> str:
    raw = text(value, 4_096)
    if not raw:
        return ""
    parsed = urlparse(raw)
    path = parsed.path or "/"
    return path[:2_048]


def source_url() -> str:
    value = os.getenv("STOREFRONT_DATABASE_URL", "").strip()
    parsed = urlparse(value)
    if parsed.scheme not in {"postgres", "postgresql"} or not parsed.hostname or not parsed.path:
        raise RuntimeError("STOREFRONT_DATABASE_URL must be a PostgreSQL connection URL")
    return value


def privacy_salt() -> str:
    value = os.getenv("DWH_CUSTOMER_HASH_SALT", "").strip()
    if len(value) < 16 or value.startswith("replace-with"):
        raise RuntimeError("DWH_CUSTOMER_HASH_SALT must contain at least 16 non-placeholder characters")
    return value


def checkpoint(client: ClickHouseClient) -> datetime:
    raw = client.execute(
        "SELECT toString(watermark) FROM dwh_meta.ingestion_checkpoint FINAL "
        f"WHERE source_system = 'storefront' AND job_name = '{JOB_NAME}' LIMIT 1"
    )
    return parse_timestamp(raw) if raw else datetime(1970, 1, 1, tzinfo=timezone.utc)


def job_run(
    client: ClickHouseClient,
    run_id: str,
    lower: datetime,
    upper: datetime,
    status: str,
    *,
    rows_read: int = 0,
    rows_written: int = 0,
    error: str = "",
) -> None:
    finished = "NULL" if status == "running" else "now64(3)"
    client.execute(
        "INSERT INTO dwh_meta.etl_job_run "
        "(job_run_id, job_name, source_system, window_start, window_end, started_at, "
        "finished_at, status, rows_read, rows_written, error_message) VALUES ("
        f"toUUID({client.quote(run_id)}), '{JOB_NAME}', 'storefront', "
        f"parseDateTime64BestEffort({client.quote(iso(lower) or '')}), "
        f"parseDateTime64BestEffort({client.quote(iso(upper) or '')}), now64(3), "
        f"{finished}, {client.quote(status)}, {rows_read}, {rows_written}, "
        f"{client.quote(error[:4000])})"
    )


def fetch_rows(cursor: Any, sql: str, lower: datetime, upper: datetime) -> list[dict[str, Any]]:
    cursor.execute(sql, (lower, upper))
    return list(cursor.fetchall())


def catalog_products(rows: list[dict[str, Any]], batch_id: str) -> list[dict[str, Any]]:
    return [
        {
            "batch_id": batch_id,
            "source_product_id": text(row["id"], 256),
            "ware_id": text(row.get("ware_id"), 256).lower(),
            "mapping_source": text(row.get("mapping_source"), 64).lower(),
            "handle": text(row.get("handle"), 512),
            "title": text(row.get("title"), 1_024),
            "brand": text(row.get("brand"), 512),
            "manufacturer": text(row.get("manufacturer"), 512),
            "country": text(row.get("country"), 256),
            "mnn": text(row.get("mnn"), 512),
            "atc": text(row.get("atc"), 128),
            "rx_otc": text(row.get("rx_otc"), 128),
            "active": bool(row.get("active")),
            "source_created_at": iso(row.get("source_created_at")),
            "source_updated_at": iso(row.get("source_updated_at")),
            "source_seen_at": iso(row["source_seen_at"]),
        }
        for row in rows
    ]


def catalog_variants(rows: list[dict[str, Any]], batch_id: str) -> list[dict[str, Any]]:
    return [
        {
            "batch_id": batch_id,
            "source_variant_id": text(row["id"], 256),
            "source_product_id": text(row["product_id"], 256),
            "title": text(row.get("title"), 1_024),
            "sku": text(row.get("sku"), 256),
            "barcode": text(row.get("barcode"), 256),
            "price_amount": decimal_text(row.get("price_amount")),
            "original_price_amount": decimal_text(row.get("original_price_amount")),
            "currency_code": text(row.get("currency_code") or "KZT", 3).upper(),
            "active": bool(row.get("active")),
            "source_updated_at": iso(row["updated_at"]),
        }
        for row in rows
    ]


def catalog_categories(rows: list[dict[str, Any]], batch_id: str) -> list[dict[str, Any]]:
    return [
        {
            "batch_id": batch_id,
            "source_category_id": text(row["id"], 256),
            "parent_source_category_id": text(row.get("parent_id"), 256),
            "handle": text(row.get("handle"), 512),
            "category_name": text(row.get("name"), 1_024),
            "rank": int(row.get("rank") or 0),
            "active": bool(row.get("active")),
            "source_updated_at": iso(row["updated_at"]),
        }
        for row in rows
    ]


def product_categories(
    rows: list[dict[str, Any]], batch_id: str, captured_at: datetime
) -> list[dict[str, Any]]:
    return [
        {
            "batch_id": batch_id,
            "source_product_id": text(row["product_id"], 256),
            "source_category_id": text(row["category_id"], 256),
            "is_primary": bool(row.get("is_primary")),
            "captured_at": iso(captured_at),
        }
        for row in rows
    ]


def pharmacies(rows: list[dict[str, Any]], batch_id: str) -> list[dict[str, Any]]:
    return [
        {
            "batch_id": batch_id,
            "source_pharmacy_id": text(row["id"], 256),
            "external_pharmacy_id": text(row.get("external_id"), 256),
            "pharmacy_name": text(row.get("name"), 1_024),
            "city": text(row.get("city"), 256),
            "address": text(row.get("address"), 1_024),
            "latitude": decimal_text(row.get("latitude")),
            "longitude": decimal_text(row.get("longitude")),
            "active": bool(row.get("active")),
            "source_updated_at": iso(row["updated_at"]),
        }
        for row in rows
    ]


def offers(rows: list[dict[str, Any]], batch_id: str, captured_at: datetime) -> list[dict[str, Any]]:
    return [
        {
            "batch_id": batch_id,
            "source_product_id": text(row["product_id"], 256),
            "source_variant_id": text(row.get("variant_id"), 256),
            "source_pharmacy_id": text(row["pharmacy_id"], 256),
            "price_amount": decimal_text(row["price_amount"]),
            "currency_code": text(row.get("currency_code") or "KZT", 3).upper(),
            "stock_quantity": decimal_text(row.get("stock_quantity")),
            "in_stock": bool(row.get("in_stock")),
            "source_updated_at": iso(row.get("source_updated_at")),
            "captured_at": iso(captured_at),
        }
        for row in rows
    ]


def warehouse_stocks(
    rows: list[dict[str, Any]], batch_id: str, captured_at: datetime
) -> list[dict[str, Any]]:
    return [
        {
            "batch_id": batch_id,
            "source_product_id": text(row["product_id"], 256),
            "source_variant_id": text(row.get("variant_id"), 256),
            "warehouse_code": text(row["warehouse_code"], 256),
            "city": text(row.get("city"), 256),
            "stock_quantity": decimal_text(row.get("stock_quantity")) or "0",
            "in_transit_quantity": decimal_text(row.get("in_transit_quantity")) or "0",
            "source_updated_at": iso(row["source_updated_at"]),
            "captured_at": iso(captured_at),
        }
        for row in rows
    ]


def orders_and_items(
    rows: list[dict[str, Any]], batch_id: str, salt: str
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    headers: list[dict[str, Any]] = []
    items: list[dict[str, Any]] = []
    for row in rows:
        metadata = json_object(row.get("metadata"))
        order_id = text(row["id"], 256)
        placed_at = row["placed_at"]
        headers.append(
            {
                "batch_id": batch_id,
                "source_order_id": order_id,
                "commerce_source": text(row.get("source_system"), 32),
                "source_commerce_order_id": text(row.get("source_order_id"), 256),
                "display_number": int(row.get("display_number") or 0),
                "placed_at": iso(placed_at),
                "source_updated_at": iso(row["updated_at"]),
                "total_amount": decimal_text(row.get("total_amount")) or "0",
                "currency_code": text(row.get("currency_code") or "KZT", 3).upper(),
                "order_status": text(row.get("status"), 128),
                "status_version": int(row.get("status_version") or 1),
                "item_count": int(row.get("item_count") or 0),
                "delivery_method": text(row.get("delivery_method"), 64),
                "fulfillment_type": text(metadata.get("fulfillment"), 64),
                "fulfillment_status": text(row.get("fulfillment_status"), 64),
                "fulfillment_version": int(row.get("fulfillment_version") or 0),
                "pharmacy_id": text(metadata.get("pharmacy_id"), 256),
                "channel": text(metadata.get("channel") or "web", 32).lower(),
                "payment_method": text(metadata.get("payment"), 64),
                "payment_status": text(metadata.get("payment_status"), 64),
                "pricing_state": text(metadata.get("pricing_state"), 64),
                "city": text(metadata.get("city"), 256),
                "promo_code": text(metadata.get("promo_code"), 128),
                "is_demo": bool(row.get("is_demo")),
                "customer_key_hash": hash_identifier(row.get("customer_id"), salt),
            }
        )
        raw_items = metadata.get("line_items")
        if not isinstance(raw_items, list):
            raw_items = []
        for index, raw in enumerate(raw_items[:200], start=1):
            line = raw if isinstance(raw, dict) else {}
            quantity = decimal_text(line.get("quantity")) or "0"
            unit_price = decimal_text(line.get("unit_price", line.get("unitPrice")))
            line_total = decimal_text(line.get("total"))
            items.append(
                {
                    "batch_id": batch_id,
                    "source_order_id": order_id,
                    "line_number": index,
                    "source_product_id": text(line.get("product_id", line.get("productId")), 256),
                    "source_variant_id": text(line.get("variant_id", line.get("variantId")), 256),
                    "sku": text(line.get("sku"), 256),
                    "title": text(line.get("product_title", line.get("title")), 1_024),
                    "quantity": quantity,
                    "unit_price": unit_price,
                    "line_total": line_total,
                    "placed_at": iso(placed_at),
                }
            )
    return headers, items


def customer_events(rows: list[dict[str, Any]], batch_id: str, salt: str) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for row in rows:
        properties = json_object(row.get("properties"))
        context = json_object(row.get("context"))
        result.append(
            {
                "batch_id": batch_id,
                "source_event_id": str(row["id"]),
                "idempotency_key": text(row.get("idempotency_key"), 512),
                "event_name": text(row.get("event_name"), 256),
                "event_version": int(row.get("event_version") or 1),
                "profile_key_hash": hash_identifier(row.get("profile_id"), salt),
                "customer_key_hash": hash_identifier(row.get("customer_id"), salt),
                "anonymous_id_hash": text(row.get("anonymous_id_hash"), 256),
                "session_id_hash": text(row.get("session_id_hash"), 256),
                "event_source": text(row.get("source"), 32),
                "occurred_at": iso(row["occurred_at"]),
                "received_at": iso(row["received_at"]),
                "source_product_id": text(property_value(properties, "product_id", "productId"), 256),
                "source_variant_id": text(property_value(properties, "variant_id", "variantId"), 256),
                "source_order_id": text(property_value(properties, "order_id", "orderId"), 256),
                "category_id": text(property_value(properties, "category_id", "categoryId"), 256),
                "quantity": decimal_text(property_value(properties, "quantity", "qty")),
                "amount": decimal_text(property_value(properties, "amount", "total", "value")),
                "currency_code": text(property_value(properties, "currency", "currency_code"), 3).upper(),
                "page_path": safe_page_path(
                    property_value(properties, "page_path", "path") or context.get("page_path")
                ),
                "campaign": text(
                    property_value(properties, "campaign", "utm_campaign") or context.get("utm_campaign"),
                    512,
                ),
                "analytics_consent": analytics_consent(row.get("consent_snapshot")),
            }
        )
    return result


def render_transform(batch_id: str) -> str:
    template = (DWH_ROOT / "sql" / "jobs" / "storefront_batch.sql").read_text(encoding="utf-8")
    return template.replace("{{batch_id}}", str(uuid.UUID(batch_id)))


def main() -> None:
    args = parse_args()
    if args.batch_size < 1 or args.batch_size > 20_000:
        raise ValueError("--batch-size must be between 1 and 20000")
    load_env_file()
    database_url = source_url()
    salt = privacy_salt()
    try:
        import psycopg
        from psycopg.rows import dict_row
    except ImportError as exc:
        raise RuntimeError("Install DWH dependencies: pip install -r dwh/requirements.txt") from exc

    client = ClickHouseClient()
    saved_watermark = checkpoint(client)
    lower = (
        datetime(1970, 1, 1, tzinfo=timezone.utc)
        if args.full
        else args.since or saved_watermark - timedelta(minutes=5)
    )
    batch_id = str(uuid.uuid4())
    run_id = str(uuid.uuid4())

    with psycopg.connect(database_url, row_factory=dict_row) as connection:
        with connection.transaction():
            with connection.cursor() as cursor:
                cursor.execute("SET TRANSACTION READ ONLY")
                cursor.execute("SELECT now() AS upper_watermark")
                upper = cursor.fetchone()["upper_watermark"]
                sources = {
                    "products": fetch_rows(
                        cursor,
                        """SELECT product.id, product.handle, product.title, product.brand,
                                  product.manufacturer, product.country, product.mnn, product.atc,
                                  product.rx_otc, product.active, product.source_created_at,
                                  product.source_updated_at, product.source_seen_at,
                                  coalesce(product.metadata->>'ware_id', product.metadata->>'wareId', '') AS ware_id,
                                  coalesce(state.mapping_source, '') AS mapping_source
                           FROM catalog_products product
                           LEFT JOIN catalog_offer_sync_state state ON state.product_id = product.id
                           WHERE greatest(product.updated_at, coalesce(state.updated_at, product.updated_at)) >= %s
                             AND greatest(product.updated_at, coalesce(state.updated_at, product.updated_at)) < %s""",
                        lower,
                        upper,
                    ),
                    "variants": fetch_rows(
                        cursor,
                        """SELECT id, product_id, title, sku, barcode, price_amount,
                                  original_price_amount, currency_code, active, updated_at
                           FROM catalog_variants
                           WHERE updated_at >= %s AND updated_at < %s""",
                        lower,
                        upper,
                    ),
                    "categories": fetch_rows(
                        cursor,
                        """SELECT id, parent_id, handle, name, rank, active, updated_at
                           FROM catalog_categories
                           WHERE updated_at >= %s AND updated_at < %s""",
                        lower,
                        upper,
                    ),
                    "product_categories": fetch_rows(
                        cursor,
                        """SELECT product_id, category_id, is_primary
                           FROM catalog_product_categories
                           WHERE %s::timestamptz IS NOT NULL AND %s::timestamptz IS NOT NULL""",
                        lower,
                        upper,
                    ),
                    "pharmacies": fetch_rows(
                        cursor,
                        """SELECT id, external_id, name, city, address, latitude, longitude,
                                  active, updated_at
                           FROM catalog_pharmacies
                           WHERE updated_at >= %s AND updated_at < %s""",
                        lower,
                        upper,
                    ),
                    "offers": fetch_rows(
                        cursor,
                        """SELECT product_id, variant_id, pharmacy_id, price_amount, currency_code,
                                  stock_quantity, in_stock, source_updated_at, updated_at
                           FROM catalog_pharmacy_offers
                           WHERE %s::timestamptz IS NOT NULL AND updated_at < %s""",
                        lower,
                        upper,
                    ),
                    "warehouse": fetch_rows(
                        cursor,
                        """SELECT product_id, variant_id, warehouse_code, city, stock_quantity,
                                  in_transit_quantity, source_updated_at, updated_at
                           FROM catalog_warehouse_stocks
                           WHERE %s::timestamptz IS NOT NULL AND updated_at < %s""",
                        lower,
                        upper,
                    ),
                    "orders": fetch_rows(
                        cursor,
                        """SELECT id, source_system, source_order_id, display_number, placed_at,
                                  total_amount, currency_code, status, status_version, item_count,
                                  delivery_method, customer_id, is_demo, metadata, updated_at,
                                  fulfillment_status, fulfillment_version
                           FROM site_orders
                           WHERE updated_at >= %s AND updated_at < %s""",
                        lower,
                        upper,
                    ),
                    "events": fetch_rows(
                        cursor,
                        """SELECT id, idempotency_key, event_name, event_version, profile_id,
                                  customer_id, anonymous_id_hash, session_id_hash, source,
                                  occurred_at, received_at, properties, context, consent_snapshot
                           FROM cdp_events
                           WHERE received_at >= %s AND received_at < %s""",
                        lower,
                        upper,
                    ),
                }

    upper = upper.astimezone(timezone.utc)
    normalized: dict[str, tuple[str, list[dict[str, Any]]]] = {
        "products": (
            "dwh_raw.storefront_catalog_product",
            catalog_products(sources["products"], batch_id),
        ),
        "variants": (
            "dwh_raw.storefront_catalog_variant",
            catalog_variants(sources["variants"], batch_id),
        ),
        "categories": (
            "dwh_raw.storefront_catalog_category",
            catalog_categories(sources["categories"], batch_id),
        ),
        "product_categories": (
            "dwh_raw.storefront_product_category",
            product_categories(sources["product_categories"], batch_id, upper),
        ),
        "pharmacies": (
            "dwh_raw.storefront_pharmacy",
            pharmacies(sources["pharmacies"], batch_id),
        ),
        "offers": (
            "dwh_raw.storefront_pharmacy_offer",
            offers(sources["offers"], batch_id, upper),
        ),
        "warehouse": (
            "dwh_raw.storefront_warehouse_stock",
            warehouse_stocks(sources["warehouse"], batch_id, upper),
        ),
        "events": (
            "dwh_raw.storefront_customer_event",
            customer_events(sources["events"], batch_id, salt),
        ),
    }
    order_headers, order_items = orders_and_items(sources["orders"], batch_id, salt)
    normalized["orders"] = ("dwh_raw.storefront_site_order", order_headers)
    normalized["order_items"] = ("dwh_raw.storefront_site_order_item", order_items)
    normalized["snapshot_manifest"] = (
        "dwh_raw.storefront_snapshot_manifest",
        [
            {
                "batch_id": batch_id,
                "captured_at": iso(upper),
                "offer_count": len(sources["offers"]),
                "warehouse_stock_count": len(sources["warehouse"]),
                "product_category_count": len(sources["product_categories"]),
            }
        ],
    )
    rows_read = sum(len(value) for value in sources.values())
    rows_written = 0
    job_run(client, run_id, lower, upper, "running", rows_read=rows_read)
    try:
        for name, (table, rows) in normalized.items():
            written = client.insert_json_rows(table, rows, batch_size=args.batch_size)
            rows_written += written
            print(f"raw {name}: {written}")
        client.execute_script(render_transform(batch_id))
        client.execute(
            "INSERT INTO dwh_meta.ingestion_checkpoint "
            "(source_system, job_name, cursor_value, watermark) VALUES ("
            f"'storefront', '{JOB_NAME}', {client.quote(iso(upper) or '')}, "
            f"parseDateTime64BestEffort({client.quote(iso(upper) or '')}))"
        )
        job_run(
            client,
            run_id,
            lower,
            upper,
            "succeeded",
            rows_read=rows_read,
            rows_written=rows_written,
        )
        print(
            f"storefront batch={batch_id} source_rows={rows_read} "
            f"raw_rows={rows_written} watermark={iso(upper)}"
        )
    except Exception as exc:
        job_run(
            client,
            run_id,
            lower,
            upper,
            "failed",
            rows_read=rows_read,
            rows_written=rows_written,
            error=str(exc),
        )
        raise


if __name__ == "__main__":
    main()
