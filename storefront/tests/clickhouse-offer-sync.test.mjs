import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import {
  attachClickHouseWarehouseResults,
  clickHouseArrayLiteral,
  normalizeClickHouseOfferRows,
  normalizeClickHouseWarehouseRows,
  resolveProductMappings,
  secureClickHouseUrl,
  validateClickHouseTable,
} from "../scripts/lib/clickhouse-offer-sync.mjs";
import { parseOfferSyncArgs } from "../scripts/lib/medusa-offer-sync.mjs";

test("ClickHouse URL accepts TLS/local tunnels and gates private cleartext", () => {
  assert.equal(secureClickHouseUrl("https://clickhouse.example.kz").protocol, "https:");
  assert.equal(secureClickHouseUrl("http://127.0.0.1:18123").port, "18123");
  assert.throws(
    () => secureClickHouseUrl("http://10.10.1.76:8123"),
    /CLICKHOUSE_ALLOW_INSECURE_PRIVATE_HTTP/,
  );
  assert.equal(
    secureClickHouseUrl("http://10.10.1.76:8123", true).hostname,
    "10.10.1.76",
  );
  assert.equal(
    secureClickHouseUrl("http://host.docker.internal:18123", true).hostname,
    "host.docker.internal",
  );
  assert.throws(
    () => secureClickHouseUrl("http://clickhouse.example.kz:8123", true),
    /must use HTTPS/,
  );
  assert.throws(
    () => secureClickHouseUrl("https://user:secret@clickhouse.example.kz"),
    /Do not embed credentials/,
  );
});

test("ClickHouse identifiers and array parameters are bounded and escaped", () => {
  assert.equal(
    validateClickHouseTable("pharmacy_analytics.fact_pharmacy_daily"),
    "`pharmacy_analytics`.`fact_pharmacy_daily`",
  );
  assert.throws(() => validateClickHouseTable("fact; DROP TABLE x"), /unquoted identifiers/);
  assert.equal(clickHouseArrayLiteral(["abc", "x'y", "a\\b"]), "['abc','x\\'y','a\\\\b']");
});

test("exact ware, material and barcode signals must agree", () => {
  const result = resolveProductMappings([
    {
      productId: "prod_a",
      wareId: "WARE-1",
      variants: [{ id: "variant_a", sku: "MAT-1", barcode: "0123456789012" }],
    },
  ], [
    { ware_id: "ware-1", material_id: "mat-1", barcode: "0123456789012" },
  ]);
  assert.deepEqual(result.unresolved, []);
  assert.deepEqual(result.resolved, [{
    productId: "prod_a",
    wareId: "ware-1",
    variantId: "variant_a",
    materialIds: ["mat-1"],
    barcodes: ["0123456789012"],
    mappingSource: "ware_id",
  }]);

  const conflict = resolveProductMappings([
    {
      productId: "prod_bad",
      wareId: "ware-1",
      variants: [{ id: "variant_bad", sku: "mat-2", barcode: "" }],
    },
  ], [
    { ware_id: "ware-1", material_id: "mat-1", barcode: "" },
    { ware_id: "ware-2", material_id: "mat-2", barcode: "" },
  ]);
  assert.equal(conflict.resolved.length, 0);
  assert.equal(conflict.unresolved[0].reason, "conflicting_exact_identifiers");
});

test("local ware fallback is exact and cross-product collisions fail closed", () => {
  const fallback = resolveProductMappings([
    { productId: "prod_a", wareId: "WARE-1", variants: [] },
  ], []);
  assert.equal(fallback.resolved[0].wareId, "ware-1");
  assert.equal(fallback.resolved[0].mappingSource, "local_ware_id");

  const collision = resolveProductMappings([
    { productId: "prod_a", wareId: "ware-1", variants: [] },
    { productId: "prod_b", wareId: "ware-1", variants: [] },
  ], []);
  assert.equal(collision.resolved.length, 0);
  assert.deepEqual(
    collision.unresolved.map((item) => item.reason),
    ["ware_id_assigned_to_multiple_products", "ware_id_assigned_to_multiple_products"],
  );
});

test("versioned ClickHouse rows preserve price, quantity, source and authoritative empty stock", () => {
  const mappings = [{
    productId: "prod_a",
    wareId: "ware-1",
    variantId: "variant_a",
    materialIds: ["mat-1"],
    barcodes: ["0123"],
    mappingSource: "material_id",
  }];
  const normalized = normalizeClickHouseOfferRows([{
    ware_id: "WARE-1",
    profile_id: 42,
    sname: "Аптека г. Алматы",
    caption: "Абая 1",
    retail_price: 4955.4,
    stock_end: 3.5,
  }], mappings, "2026-07-27");
  assert.equal(normalized[0].normalized.status, "valid");
  assert.deepEqual(normalized[0].normalized.offers[0], {
    id: "ch:42",
    externalId: "42",
    name: "Аптека г. Алматы",
    city: "Алматы",
    address: "Абая 1",
    price: 4955,
    stockQuantity: 3.5,
    variantId: "variant_a",
    sourceUpdatedAt: "2026-07-27T00:00:00.000Z",
    source: "clickhouse_fact_pharmacy_daily",
  });

  const empty = normalizeClickHouseOfferRows([], mappings, "2026-07-27");
  assert.equal(empty[0].normalized.status, "valid");
  assert.deepEqual(empty[0].normalized.offers, []);

  const warehouses = normalizeClickHouseWarehouseRows([{
    material_id: "MAT-1",
    barcode: "0123",
    city: "Алматы",
    warehouse_code: "HEAD-ALM",
    quantity: 12,
    in_transit: 3,
  }], mappings, "2026-07-26");
  assert.deepEqual(warehouses.errorsByProduct, new Map());
  assert.deepEqual(warehouses.stocksByProduct.get("prod_a"), [{
    warehouseCode: "HEAD-ALM",
    city: "Алматы",
    stockQuantity: 12,
    inTransitQuantity: 3,
    variantId: "variant_a",
    sourceUpdatedAt: "2026-07-26T00:00:00.000Z",
  }]);
});

test("one malformed ClickHouse offer fails only its product, not the whole batch", () => {
  const mappings = [
    {
      productId: "prod_bad",
      wareId: "ware-bad",
      variantId: "variant_bad",
      materialIds: [],
      barcodes: [],
      mappingSource: "ware_id",
    },
    {
      productId: "prod_good",
      wareId: "ware-good",
      variantId: "variant_good",
      materialIds: [],
      barcodes: [],
      mappingSource: "ware_id",
    },
  ];
  const normalized = normalizeClickHouseOfferRows([
    {
      ware_id: "ware-bad",
      profile_id: 1,
      sname: "Bad pharmacy",
      caption: "Bad address",
      retail_price: 0,
      stock_end: 2,
    },
    {
      ware_id: "ware-good",
      profile_id: 2,
      sname: "Good pharmacy",
      caption: "Good address",
      retail_price: 5000,
      stock_end: 3,
    },
  ], mappings, "2026-07-27");

  assert.deepEqual(normalized.map((item) => [item.productId, item.normalized.status]), [
    ["prod_bad", "invalid"],
    ["prod_good", "valid"],
  ]);
  assert.equal(normalized[0].normalized.reason, "invalid_clickhouse_pharmacy_offer");
  assert.equal(normalized[1].normalized.offers[0].price, 5000);
});

test("ambiguous and unrequested offer rows do not poison healthy batch neighbours", () => {
  const mappings = [
    {
      productId: "prod_ambiguous_a",
      wareId: "ware-shared",
      variantId: "variant_a",
      materialIds: [],
      barcodes: [],
      mappingSource: "ware_id",
    },
    {
      productId: "prod_ambiguous_b",
      wareId: "ware-shared",
      variantId: "variant_b",
      materialIds: [],
      barcodes: [],
      mappingSource: "ware_id",
    },
    {
      productId: "prod_good",
      wareId: "ware-good",
      variantId: "variant_good",
      materialIds: [],
      barcodes: [],
      mappingSource: "ware_id",
    },
  ];
  const normalized = normalizeClickHouseOfferRows([
    {
      ware_id: "ware-shared",
      profile_id: 1,
      retail_price: 4000,
      stock_end: 1,
    },
    {
      ware_id: "ware-not-requested",
      profile_id: 9,
      retail_price: 1,
      stock_end: 1,
    },
    {
      ware_id: "ware-good",
      profile_id: 2,
      retail_price: 5000,
      stock_end: 3,
    },
  ], mappings, "2026-07-27");

  assert.deepEqual(
    normalized.map((item) => [item.productId, item.normalized.status, item.normalized.reason]),
    [
      ["prod_ambiguous_a", "invalid", "ambiguous_clickhouse_offer_mapping"],
      ["prod_ambiguous_b", "invalid", "ambiguous_clickhouse_offer_mapping"],
      ["prod_good", "valid", null],
    ],
  );
  assert.equal(normalized[2].normalized.offers[0].price, 5000);
});

test("one malformed warehouse row preserves only that product's last-known-good state", () => {
  const mappings = [
    {
      productId: "prod_bad",
      wareId: "ware-bad",
      variantId: "variant_bad",
      materialIds: ["mat-bad"],
      barcodes: ["barcode-bad"],
      mappingSource: "material_id",
    },
    {
      productId: "prod_good",
      wareId: "ware-good",
      variantId: "variant_good",
      materialIds: ["mat-good"],
      barcodes: ["barcode-good"],
      mappingSource: "material_id",
    },
  ];
  const warehouses = normalizeClickHouseWarehouseRows([
    {
      material_id: "mat-bad",
      barcode: "barcode-bad",
      city: "Almaty",
      warehouse_code: "HEAD-BAD",
      quantity: -1,
      in_transit: 0,
    },
    {
      material_id: "not-requested",
      barcode: "",
      city: "Unknown",
      warehouse_code: "",
      quantity: "not-a-number",
      in_transit: 0,
    },
    {
      material_id: "mat-good",
      barcode: "barcode-good",
      city: "Almaty",
      warehouse_code: "HEAD-GOOD",
      quantity: 12,
      in_transit: 2,
    },
  ], mappings, "2026-07-27");

  assert.equal(
    warehouses.errorsByProduct.get("prod_bad"),
    "invalid_clickhouse_warehouse_stock",
  );
  assert.deepEqual(warehouses.stocksByProduct.get("prod_bad"), []);
  assert.equal(warehouses.errorsByProduct.has("prod_good"), false);
  assert.equal(warehouses.stocksByProduct.get("prod_good")[0].stockQuantity, 12);

  const merged = attachClickHouseWarehouseResults([
    {
      productId: "prod_bad",
      normalized: { status: "valid", reason: null, offers: [{ id: "bad-old" }] },
    },
    {
      productId: "prod_good",
      normalized: { status: "valid", reason: null, offers: [{ id: "good-new" }] },
    },
  ], warehouses);
  assert.deepEqual(merged[0], {
    productId: "prod_bad",
    error: "invalid_clickhouse_warehouse_stock",
  });
  assert.equal(merged[1].normalized.status, "valid");
  assert.equal(merged[1].warehouseStocks[0].warehouseCode, "HEAD-GOOD");
});

test("offer worker exposes an explicit ClickHouse source and versioned checkpoint", async () => {
  assert.equal(parseOfferSyncArgs([], {}).source, "medusa");
  assert.equal(
    parseOfferSyncArgs([], { CATALOG_OFFER_SOURCE: "clickhouse" }).source,
    "clickhouse",
  );
  assert.equal(parseOfferSyncArgs(["--source", "clickhouse"], {}).source, "clickhouse");
  assert.throws(() => parseOfferSyncArgs(["--source", "other"], {}), /medusa or clickhouse/);

  const [worker, migration] = await Promise.all([
    readFile(new URL("../scripts/sync-medusa-pharmacy-offers.mjs", import.meta.url), "utf8"),
    readFile(new URL("../db/migrations/008_clickhouse_offer_source.sql", import.meta.url), "utf8"),
  ]);
  assert.match(worker, /PREWHERE snapshot_date = \{snapshot:Date\}/);
  assert.match(worker, /product_material_map/);
  assert.match(worker, /stock_quantity/);
  assert.match(worker, /head_warehouse_stock/);
  assert.match(worker, /attachClickHouseWarehouseResults/);
  assert.match(worker, /CLICKHOUSE_PASSWORD/);
  assert.doesNotMatch(worker, /A2L#|10\.10\.1\.76/);
  assert.match(migration, /source_snapshot_at/);
  assert.match(migration, /catalog_warehouse_stocks/);
  assert.match(migration, /CHECK \(source IN \('medusa', 'clickhouse'\)\)/);
});
