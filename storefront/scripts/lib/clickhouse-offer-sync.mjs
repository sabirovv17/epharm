const IDENTIFIER_PATTERN = /^[A-Za-z_][A-Za-z0-9_]*$/;
const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

function boundedText(value, maximum = 512) {
  if (value === null || value === undefined) return "";
  return String(value).trim().slice(0, maximum);
}

export function normalizeExactIdentifier(value) {
  return boundedText(value, 256).toLowerCase();
}

export function validateClickHouseTable(value, fallback) {
  const candidate = boundedText(value || fallback, 256);
  const parts = candidate.split(".");
  if (parts.length < 1 || parts.length > 2 || parts.some((part) => !IDENTIFIER_PATTERN.test(part))) {
    throw new Error("ClickHouse table names must be one or two unquoted identifiers");
  }
  return parts.map((part) => `\`${part}\``).join(".");
}

function privateIpv4(hostname) {
  const octets = hostname.split(".").map(Number);
  if (octets.length !== 4 || octets.some((part) => !Number.isInteger(part) || part < 0 || part > 255)) {
    return false;
  }
  return octets[0] === 10
    || (octets[0] === 172 && octets[1] >= 16 && octets[1] <= 31)
    || (octets[0] === 192 && octets[1] === 168);
}

export function secureClickHouseUrl(raw, allowPrivateHttp = false) {
  const url = new URL(boundedText(raw, 2_048));
  if (!["http:", "https:"].includes(url.protocol)) {
    throw new Error("CLICKHOUSE_URL must use http:// or https://");
  }
  if (url.username || url.password) {
    throw new Error("Do not embed credentials in CLICKHOUSE_URL");
  }
  if (url.search || url.hash) {
    throw new Error("CLICKHOUSE_URL must not include query parameters or a fragment");
  }
  const local = new Set(["localhost", "127.0.0.1", "::1"]).has(url.hostname);
  const dockerHostGateway = url.hostname === "host.docker.internal";
  if (url.protocol === "http:" && !local
      && !(allowPrivateHttp && (privateIpv4(url.hostname) || dockerHostGateway))) {
    throw new Error(
      "Remote CLICKHOUSE_URL must use HTTPS; private RFC1918 HTTP requires CLICKHOUSE_ALLOW_INSECURE_PRIVATE_HTTP=true",
    );
  }
  return url;
}

export function clickHouseArrayLiteral(values) {
  if (!Array.isArray(values)) throw new Error("ClickHouse array parameter must be an array");
  return `[${values.map((value) => {
    const text = boundedText(value, 256);
    return `'${text.replaceAll("\\", "\\\\").replaceAll("'", "\\'")}'`;
  }).join(",")}]`;
}

function addIndex(index, key, row) {
  if (!key) return;
  const rows = index.get(key) || [];
  rows.push(row);
  index.set(key, rows);
}

function uniqueWareIds(rows) {
  return new Set(rows.map((row) => normalizeExactIdentifier(row.ware_id)).filter(Boolean));
}

function intersection(sets) {
  if (!sets.length) return new Set();
  return new Set([...sets[0]].filter((item) => sets.slice(1).every((set) => set.has(item))));
}

function matchingVariantIds(product, resolvedWareId, indexes) {
  const ids = new Set();
  for (const variant of product.variants || []) {
    const material = normalizeExactIdentifier(variant?.sku);
    const barcode = normalizeExactIdentifier(variant?.barcode);
    const matches = [
      ...(indexes.byMaterial.get(material) || []),
      ...(indexes.byBarcode.get(barcode) || []),
    ];
    if (matches.some((row) => normalizeExactIdentifier(row.ware_id) === resolvedWareId)) {
      const variantId = boundedText(variant?.id, 128);
      if (variantId) ids.add(variantId);
    }
  }
  return ids.size === 1 ? [...ids][0] : null;
}

/**
 * Resolve a local Medusa product only through exact identifiers.
 *
 * Signals that exist in product_material_map must agree on one ware_id. A
 * product metadata ware_id may be used directly only when no mapping row
 * contradicts it. Ambiguous matches and duplicate ware assignments fail
 * closed so an offer can never land on the wrong medicine.
 */
export function resolveProductMappings(products, mappingRows) {
  const indexes = {
    byMaterial: new Map(),
    byWare: new Map(),
    byBarcode: new Map(),
  };
  for (const raw of mappingRows) {
    const row = {
      material_id: normalizeExactIdentifier(raw?.material_id),
      ware_id: normalizeExactIdentifier(raw?.ware_id),
      barcode: normalizeExactIdentifier(raw?.barcode),
    };
    if (!row.ware_id) continue;
    addIndex(indexes.byMaterial, row.material_id, row);
    addIndex(indexes.byWare, row.ware_id, row);
    addIndex(indexes.byBarcode, row.barcode, row);
  }

  const resolved = [];
  const unresolved = [];
  for (const product of products) {
    const productId = boundedText(product?.productId, 128);
    const directWare = normalizeExactIdentifier(product?.wareId);
    const materialRows = (product?.variants || [])
      .flatMap((variant) => indexes.byMaterial.get(normalizeExactIdentifier(variant?.sku)) || []);
    const barcodeRows = (product?.variants || [])
      .flatMap((variant) => indexes.byBarcode.get(normalizeExactIdentifier(variant?.barcode)) || []);
    const directRows = indexes.byWare.get(directWare) || [];
    const directSignal = directWare ? [{ ware_id: directWare }] : [];
    const signals = [directSignal, directRows, materialRows, barcodeRows]
      .map(uniqueWareIds)
      .filter((set) => set.size > 0);
    let candidates = signals.length > 1 ? intersection(signals) : (signals[0] || new Set());

    if (candidates.size !== 1) {
      unresolved.push({
        productId,
        reason: signals.length > 1 && candidates.size === 0
          ? "conflicting_exact_identifiers"
          : candidates.size > 1
            ? "ambiguous_exact_identifiers"
            : "no_exact_identifier_match",
      });
      continue;
    }

    const wareId = [...candidates][0];
    const sourceRows = [
      ...(indexes.byWare.get(wareId) || []),
      ...materialRows.filter((row) => normalizeExactIdentifier(row.ware_id) === wareId),
      ...barcodeRows.filter((row) => normalizeExactIdentifier(row.ware_id) === wareId),
    ];
    const materialIds = [...new Set(sourceRows.map((row) => row.material_id).filter(Boolean))];
    const barcodes = [...new Set([
      ...sourceRows.map((row) => row.barcode),
      ...(product?.variants || []).map((variant) => normalizeExactIdentifier(variant?.barcode)),
    ].filter(Boolean))];
    resolved.push({
      productId,
      wareId,
      variantId: matchingVariantIds(product, wareId, indexes),
      materialIds,
      barcodes,
      mappingSource: directRows.length
        ? "ware_id"
        : materialRows.length
          ? "material_id"
          : barcodeRows.length
            ? "barcode"
            : "local_ware_id",
    });
  }

  const byWare = new Map();
  for (const item of resolved) {
    const productsForWare = byWare.get(item.wareId) || [];
    productsForWare.push(item.productId);
    byWare.set(item.wareId, productsForWare);
  }
  const collisions = new Set(
    [...byWare.entries()].filter(([, productIds]) => productIds.length > 1).map(([wareId]) => wareId),
  );
  const safeResolved = resolved.filter((item) => {
    if (!collisions.has(item.wareId)) return true;
    unresolved.push({ productId: item.productId, reason: "ware_id_assigned_to_multiple_products" });
    return false;
  });

  return { resolved: safeResolved, unresolved };
}

function cityFromText(text) {
  const value = boundedText(text, 1_024);
  return value.match(/(?:^|[\s,;])г\.?\s*([\p{L}][\p{L}-]+)/iu)?.[1] || "";
}

export function normalizeClickHouseOfferRows(rows, mappings, snapshotDate, maxPharmacies = 2_500) {
  if (!DATE_PATTERN.test(snapshotDate)) throw new Error("ClickHouse fact snapshot is not an ISO date");
  const byWare = new Map();
  for (const mapping of mappings) addIndex(byWare, mapping.wareId, mapping);
  const grouped = new Map(mappings.map((mapping) => [mapping.productId, new Map()]));
  const invalidProducts = new Map();

  for (const raw of rows) {
    const wareId = normalizeExactIdentifier(raw?.ware_id);
    const candidates = new Map(
      (byWare.get(wareId) || []).map((mapping) => [mapping.productId, mapping]),
    );
    // A response row outside the exact request identifiers cannot be safely
    // attributed to a requested product. Ignore it instead of failing the
    // entire batch; no requested snapshot is mutated because of this row.
    if (candidates.size === 0) continue;
    if (candidates.size > 1) {
      for (const productId of candidates.keys()) {
        invalidProducts.set(productId, "ambiguous_clickhouse_offer_mapping");
      }
      continue;
    }
    const mapping = [...candidates.values()][0];
    const externalId = boundedText(raw?.profile_id, 256);
    const price = Math.round(Number(raw?.retail_price));
    const stockQuantity = Number(raw?.stock_end);
    if (!externalId || !Number.isSafeInteger(price) || price <= 0
        || !Number.isFinite(stockQuantity) || stockQuantity <= 0) {
      // One malformed pharmacy row must not poison every other product in the
      // same 100-item batch. Fail this product closed and preserve its
      // last-known-good snapshot while valid neighbours continue to update.
      invalidProducts.set(mapping.productId, "invalid_clickhouse_pharmacy_offer");
      continue;
    }
    const name = boundedText(raw?.sname, 512) || boundedText(raw?.caption, 512) || externalId;
    const address = boundedText(raw?.caption, 1_024);
    const city = boundedText(raw?.city, 256) || cityFromText(`${name} ${address}`).slice(0, 256);
    const offer = {
      id: `ch:${externalId}`.slice(0, 256),
      externalId,
      name,
      city,
      address,
      price,
      stockQuantity,
      variantId: mapping.variantId,
      sourceUpdatedAt: `${snapshotDate}T00:00:00.000Z`,
      source: "clickhouse_fact_pharmacy_daily",
    };
    const productOffers = grouped.get(mapping.productId);
    const previous = productOffers.get(offer.id);
    if (!previous || offer.price < previous.price) productOffers.set(offer.id, offer);
  }

  return mappings.map((mapping) => {
    if (invalidProducts.has(mapping.productId)) {
      return {
        productId: mapping.productId,
        normalized: {
          status: "invalid",
          reason: invalidProducts.get(mapping.productId),
          offers: [],
        },
        sourceSnapshotAt: `${snapshotDate}T00:00:00.000Z`,
        mappingSource: mapping.mappingSource,
      };
    }
    const offers = [...grouped.get(mapping.productId).values()]
      .sort((left, right) => left.price - right.price || left.id.localeCompare(right.id));
    if (offers.length > maxPharmacies) {
      return {
        productId: mapping.productId,
        normalized: { status: "invalid", reason: "pharmacy_snapshot_exceeds_limit", offers: [] },
        sourceSnapshotAt: `${snapshotDate}T00:00:00.000Z`,
        mappingSource: mapping.mappingSource,
      };
    }
    // Unlike the legacy Medusa endpoint, a ClickHouse daily snapshot is
    // explicitly versioned. Zero rows therefore authoritatively means no
    // available pharmacy stock and may clear the previous snapshot.
    return {
      productId: mapping.productId,
      normalized: { status: "valid", reason: null, offers },
      sourceSnapshotAt: `${snapshotDate}T00:00:00.000Z`,
      mappingSource: mapping.mappingSource,
    };
  });
}

export function normalizeClickHouseWarehouseRows(rows, mappings, snapshotDate) {
  if (!DATE_PATTERN.test(snapshotDate)) throw new Error("ClickHouse warehouse snapshot is not an ISO date");
  const byMaterial = new Map();
  const byBarcode = new Map();
  for (const mapping of mappings) {
    for (const materialId of mapping.materialIds || []) {
      const products = byMaterial.get(materialId) || [];
      products.push(mapping);
      byMaterial.set(materialId, products);
    }
    for (const barcode of mapping.barcodes || []) {
      const products = byBarcode.get(barcode) || [];
      products.push(mapping);
      byBarcode.set(barcode, products);
    }
  }
  const grouped = new Map(mappings.map((mapping) => [mapping.productId, new Map()]));
  const errorsByProduct = new Map();
  for (const raw of rows) {
    const materialId = normalizeExactIdentifier(raw?.material_id);
    const barcode = normalizeExactIdentifier(raw?.barcode);
    const candidates = new Map();
    for (const mapping of [
      ...(byMaterial.get(materialId) || []),
      ...(byBarcode.get(barcode) || []),
    ]) {
      candidates.set(mapping.productId, mapping);
    }
    // Rows outside the exact request identifiers have no safe product owner.
    // Ignore them rather than poisoning every product in the batch.
    if (candidates.size === 0) continue;
    if (candidates.size > 1) {
      for (const productId of candidates.keys()) {
        errorsByProduct.set(productId, "ambiguous_clickhouse_warehouse_mapping");
      }
      continue;
    }
    const mapping = [...candidates.values()][0];
    const warehouseCode = boundedText(raw?.warehouse_code, 256);
    const city = boundedText(raw?.city, 256);
    const stockQuantity = Number(raw?.quantity);
    const inTransitQuantity = Number(raw?.in_transit);
    if (!warehouseCode
        || !Number.isFinite(stockQuantity) || stockQuantity < 0
        || !Number.isFinite(inTransitQuantity) || inTransitQuantity < 0) {
      errorsByProduct.set(mapping.productId, "invalid_clickhouse_warehouse_stock");
      continue;
    }
    const key = `${warehouseCode}\u0000${city}`;
    grouped.get(mapping.productId).set(key, {
      warehouseCode,
      city,
      stockQuantity,
      inTransitQuantity,
      variantId: mapping.variantId,
      sourceUpdatedAt: `${snapshotDate}T00:00:00.000Z`,
    });
  }
  const stocksByProduct = new Map(
    mappings.map((mapping) => [
      mapping.productId,
      errorsByProduct.has(mapping.productId)
        ? []
        : [...grouped.get(mapping.productId).values()]
          .sort((left, right) => left.city.localeCompare(right.city)
            || left.warehouseCode.localeCompare(right.warehouseCode)),
    ]),
  );
  return { stocksByProduct, errorsByProduct };
}

/**
 * Merge independently normalized ClickHouse data without allowing one bad
 * warehouse row to invalidate its healthy batch neighbours. A product-level
 * warehouse error becomes a non-success result, so the caller records the
 * failure but does not replace that product's last-known-good offers/stocks.
 */
export function attachClickHouseWarehouseResults(fetched, warehouseNormalization) {
  const { stocksByProduct, errorsByProduct } = warehouseNormalization;
  return fetched.map((item) => {
    const warehouseError = errorsByProduct.get(item.productId);
    if (warehouseError) return { productId: item.productId, error: warehouseError };
    return {
      ...item,
      warehouseStocks: stocksByProduct.get(item.productId) || [],
    };
  });
}
