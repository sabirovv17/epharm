import assert from "node:assert/strict";
import test from "node:test";
import {
  aurmaHandleScore,
  barcodeFromOpenFactsKey,
  canonicalHandleScore,
  distinctiveHandleTokens,
  extractEuropharmaProductLinks,
  normalizeBarcode,
  openFactsImageScore,
  parseAptekaPlusProductHtml,
  parseEuropharmaProductHtml,
  selectCandidate,
  stableImageUuid,
  titleSimilarity,
} from "../scripts/lib/product-image-enrichment.mjs";

test("parses AptekaPlus product card with its exact visible barcode", () => {
  const html = '<h1 class="title">\u041e\u0419\u041b\u0415\u0421\u0415\u041d 10 \u043c\u043b</h1><span>\u0428\u0442\u0440\u0438\u0445\u043a\u043e\u0434: </span><span>7640153061799</span><img src="https://bitrix.aptekaplus.kz/cdn/?q=/upload/iblock/3b6/oylesen.jpg">';
  assert.deepEqual(parseAptekaPlusProductHtml(html), {
    barcode: "7640153061799",
    raw_barcode: "7640153061799",
    image: "https://bitrix.aptekaplus.kz/cdn/?q=/upload/iblock/3b6/oylesen.jpg",
    name: "\u041e\u0419\u041b\u0415\u0421\u0415\u041d 10 \u043c\u043b",
  });
  assert.equal(parseAptekaPlusProductHtml('<h1>\u0411\u0435\u0437 \u0448\u0442\u0440\u0438\u0445\u043a\u043e\u0434\u0430</h1>'), null);
});

test("normalizes common transliteration variants before proposing a page", () => {
  assert.ok(canonicalHandleScore(
    "oylesen-vitamin-d3-kholekaltsiferol",
    "oylesen-vitamin-d3-holekalciferol",
  ) > 0.8);
});

test("extracts unique Europharma product links", () => {
  const html = '<a class="card-product__link" href="/medoklav">A</a><a class="x card-product__link y" href="/medoklav">A</a>';
  assert.deepEqual(extractEuropharmaProductLinks(html, "https://oral.europharma.kz/ru"), ["https://oral.europharma.kz/medoklav"]);
});

test("parses Europharma metadata only with an exact barcode", () => {
  const html = '<meta itemprop="name" content="Медоклав 156,25 мг"><meta itemprop="image" content="https://st.europharma.kz/image.webp"><meta itemprop="brand" content="Медокеми"><meta itemprop="sku" content="123"><meta itemprop="mpn" content="5290931000926">';
  assert.deepEqual(parseEuropharmaProductHtml(html), {
    barcode: "5290931000926",
    raw_barcode: "5290931000926",
    image: "https://st.europharma.kz/image.webp",
    name: "Медоклав 156,25 мг",
    brand: "Медокеми",
    sku: "123",
  });
  assert.equal(parseEuropharmaProductHtml('<meta itemprop="name" content="Нет штрихкода">'), null);
});

test("normalizes UPC and GTIN without fuzzy matching", () => {
  assert.equal(normalizeBarcode("733739001078"), "0733739001078");
  assert.equal(normalizeBarcode("4603423004936"), "4603423004936");
  assert.equal(normalizeBarcode("not-a-barcode"), null);
});

test("extracts normalized barcode and ranks front images", () => {
  const key = "data/073/373/900/1078/front_en.12.400.jpg";
  assert.equal(barcodeFromOpenFactsKey(key), "0733739001078");
  assert.ok(openFactsImageScore(key) > openFactsImageScore("data/073/373/900/1078/1.400.jpg"));
  assert.ok(openFactsImageScore(key) >= 85);
});

test("selects authoritative source before a generic open dataset", () => {
  const chosen = selectCandidate(
    { source: "openfoodfacts", image_score: 100 },
    { source: "nkt", image_score: 1 },
  );
  assert.equal(chosen.source, "nkt");
});

test("creates a stable UUID filename and compares titles", () => {
  assert.equal(stableImageUuid("p1", "558E64BB-2A8E-4E7E-805F-E4FE7A5B15ED"), "558E64BB-2A8E-4E7E-805F-E4FE7A5B15ED");
  assert.equal(stableImageUuid("p1", null), stableImageUuid("p1", null));
  assert.ok(titleSimilarity("Now Foods Глицин 1000 мг капсулы", "NOW Glycine 1000 mg capsules") > 0.2);
});

test("proposes Aurma pages conservatively before barcode verification", () => {
  assert.ok(aurmaHandleScore(
    "vezilyut-peptid-bio-200mg-30-kaps",
    "46500-vezilyut-30-kaps",
  ) >= 0.7);
  assert.equal(aurmaHandleScore(
    "amoksiklav-500mg-14-tabletki",
    "123-amoksiklav-1000mg-14-tabletki",
  ), 0);
  assert.deepEqual(distinctiveHandleTokens("46503-now-glitsin-1000mg-100-kapsul"), ["glitsin"]);
});
