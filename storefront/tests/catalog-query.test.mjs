import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import {
  CatalogQueryError,
  buildCatalogBrands,
  buildCatalogFacets,
  cataloguePagination,
  filterAndSortCatalog,
  parseCatalogQuery,
} from "../src/lib/catalog-query.ts";

function product(id, overrides = {}) {
  return {
    id,
    slug: `product-${id}`,
    name: `Product ${id}`,
    brand: "Alpha Pharma",
    categorySlug: "medicines",
    categoryHandles: ["medicines"],
    price: 1_000,
    priceTBD: false,
    rating: 0,
    reviews: 0,
    badges: [],
    art: { kind: "box", hue: 0 },
    inStock: true,
    stockPharmacies: 1,
    prescription: false,
    ...overrides,
  };
}

test("parseCatalogQuery keeps the legacy 100-item page and supports page/offset aliases", () => {
  const legacy = parseCatalogQuery(new URLSearchParams());
  assert.equal(legacy.limit, 100);
  assert.equal(legacy.offset, 0);
  assert.equal(legacy.page, 1);
  assert.equal(legacy.includeFacets, true);

  const paged = parseCatalogQuery(new URLSearchParams("limit=20&page=3&price_min=100&in_stock=true"));
  assert.equal(paged.offset, 40);
  assert.equal(paged.page, 3);
  assert.equal(paged.minPrice, 100);
  assert.equal(paged.inStock, true);

  const explicitOffset = parseCatalogQuery(new URLSearchParams("limit=20&page=9&offset=60"));
  assert.equal(explicitOffset.offset, 60);
  assert.equal(explicitOffset.page, 4);
});

test("facets are included by default and can be explicitly omitted for load-more", () => {
  assert.equal(parseCatalogQuery(new URLSearchParams("offset=24&facets=0")).includeFacets, false);
  assert.equal(parseCatalogQuery(new URLSearchParams("offset=24&includeFacets=false")).includeFacets, false);
  assert.equal(parseCatalogQuery(new URLSearchParams("facets=1")).includeFacets, true);
  assert.throws(
    () => parseCatalogQuery(new URLSearchParams("facets=maybe")),
    (error) => error instanceof CatalogQueryError && error.field === "facets",
  );
});

test("parseCatalogQuery normalizes repeated brand filters and validates unsafe ranges", () => {
  const query = parseCatalogQuery(new URLSearchParams("brand=Alpha%20Pharma&brand=alpha-pharma,Beta&brand=Beta"));
  assert.deepEqual(query.brands, ["alpha-pharma", "beta"]);

  assert.throws(
    () => parseCatalogQuery(new URLSearchParams("limit=251")),
    (error) => error instanceof CatalogQueryError && error.field === "limit",
  );
  assert.throws(
    () => parseCatalogQuery(new URLSearchParams("minPrice=200&maxPrice=100")),
    (error) => error instanceof CatalogQueryError && error.field === "minPrice",
  );
  assert.throws(
    () => parseCatalogQuery(new URLSearchParams("category=../admin")),
    (error) => error instanceof CatalogQueryError && error.field === "category",
  );
});

test("server-side pagination reaches products beyond the former first 100", () => {
  const products = Array.from({ length: 255 }, (_, index) => product(String(index).padStart(3, "0")));
  const query = parseCatalogQuery(new URLSearchParams("limit=50&offset=200"));
  const matched = filterAndSortCatalog(products, query);
  const page = matched.slice(query.offset, query.offset + query.limit);
  const pagination = cataloguePagination(matched.length, query);

  assert.equal(page.length, 50);
  assert.equal(page[0].id, "200");
  assert.equal(page.at(-1).id, "249");
  assert.equal(pagination.total, 255);
  assert.equal(pagination.hasNext, true);
  assert.equal(pagination.nextOffset, 250);
});

test("filters are applied before pagination with OR brands and AND dimensions", () => {
  const products = [
    product("a", { brand: "Alpha Pharma", price: 900, categorySlug: "vitamins", categoryHandles: ["health", "vitamins"] }),
    product("b", { brand: "Beta Labs", price: 1_200, categorySlug: "vitamins", categoryHandles: ["health", "vitamins"], prescription: true }),
    product("c", { brand: "Gamma", price: 1_100, categorySlug: "vitamins", categoryHandles: ["health", "vitamins"] }),
    product("d", { brand: "Alpha Pharma", price: 0, priceTBD: true, inStock: false, categorySlug: "vitamins", categoryHandles: ["health", "vitamins"] }),
  ];
  const query = parseCatalogQuery(new URLSearchParams(
    "brand=alpha-pharma&brand=beta-labs&category=health&minPrice=800&maxPrice=1300&prescription=otc",
  ));

  assert.deepEqual(filterAndSortCatalog(products, query).map((item) => item.id), ["a"]);
});

test("price sort is stable and always places unknown prices last", () => {
  const products = [
    product("b", { price: 500 }),
    product("c", { price: 0, priceTBD: true }),
    product("a", { price: 500 }),
    product("d", { price: 900 }),
  ];
  const asc = parseCatalogQuery(new URLSearchParams("sort=price_asc"));
  const desc = parseCatalogQuery(new URLSearchParams("sort=price_desc"));

  assert.deepEqual(filterAndSortCatalog(products, asc).map((item) => item.id), ["a", "b", "d", "c"]);
  assert.deepEqual(filterAndSortCatalog(products, desc).map((item) => item.id), ["d", "a", "b", "c"]);
});

test("facets and legacy brands are counted from the supplied full catalogue", () => {
  const categories = [
    { id: "cat-health", slug: "health", name: "Health", icon: "Pill", from: "#000", to: "#fff", count: 0 },
    { id: "cat-beauty", slug: "beauty", name: "Beauty", icon: "Sparkles", from: "#000", to: "#fff", count: 0 },
  ];
  const products = [
    product("a", { categorySlug: "vitamins", categoryHandles: ["health", "vitamins"], brand: "Alpha", price: 500 }),
    product("b", { categorySlug: "medicines", categoryHandles: ["health", "medicines"], brand: "Alpha", price: 0, priceTBD: true, inStock: false, prescription: true }),
    product("c", { categorySlug: "beauty", categoryHandles: ["beauty"], brand: "Beta", price: 700, oldPrice: 900, inStock: false }),
  ];

  const facets = buildCatalogFacets(products, categories);
  assert.deepEqual(facets.categories.map(({ slug, count }) => [slug, count]), [["health", 2], ["beauty", 1]]);
  assert.deepEqual(facets.brands.map(({ key, count }) => [key, count]), [["alpha", 2], ["beta", 1]]);
  assert.deepEqual(facets.price, { min: 500, max: 700, unknown: 1 });
  assert.deepEqual(facets.availability, { inStock: 1, outOfStock: 1, unknown: 1 });
  assert.deepEqual(facets.sale, { onSale: 1, regular: 2 });
  assert.deepEqual(facets.prescription, { rx: 1, otc: 2 });

  const brands = buildCatalogBrands(products);
  assert.deepEqual(brands.map(({ slug, name }) => [slug, name]), [["alpha", "Alpha"], ["beta", "Beta"]]);
});

test("search resolves indexed ids before hydrating the expensive product view", async () => {
  const source = await readFile(new URL("../src/lib/catalog-local-read.ts", import.meta.url), "utf8");
  const search = source.slice(
    source.indexOf("export async function searchLocalProducts"),
    source.indexOf("function brandKey"),
  );
  assert.match(search, /FROM catalog_products product/);
  assert.match(search, /product\.search_vector @@ plainto_tsquery/);
  assert.match(search, /WHERE id = ANY\(\$1::text\[\]\)/);
  assert.doesNotMatch(search, /concat_ws\([^)]*variants::text/);
});

test("catalog grid does not wait for full facet aggregation", async () => {
  const source = await readFile(new URL("../src/components/catalog/CatalogView.tsx", import.meta.url), "utf8");
  assert.match(source, /productParams\.set\("facets", "0"\)/);
  assert.match(source, /facetParams\.set\("facets", "1"\)/);
  assert.match(source, /facetParams\.set\("limit", "1"\)/);
});

test("search page forwards its query to the server-backed catalogue", async () => {
  const source = await readFile(new URL("../src/components/search/SearchResults.tsx", import.meta.url), "utf8");
  assert.match(source, /searchQuery=\{q\}/);
  assert.match(source, /key=\{q\}/);
  assert.doesNotMatch(source, /fetch\(`\/api\/search/);
});

test("catalog search uses literal fields instead of Russian stemming", async () => {
  const source = await readFile(new URL("../src/lib/catalog-local-read.ts", import.meta.url), "utf8");
  const candidateBlock = source.slice(
    source.indexOf("const searchMatches"),
    source.indexOf("// Aggregate each price source"),
  );
  const queryBlock = source.slice(
    source.indexOf("if (query.q)", source.indexOf("const catalogPrice")),
    source.indexOf("if (query.category)"),
  );
  assert.match(candidateBlock, /product\.title ILIKE/);
  assert.match(candidateBlock, /lower\(product\.brand\) = lower/);
  assert.match(candidateBlock, /upper\(variant\.sku\) = upper/);
  assert.match(candidateBlock, /variant\.barcode =/);
  assert.match(queryBlock, /product\.id = ANY/);
  assert.doesNotMatch(candidateBlock, /variant\.title ILIKE/);
  assert.doesNotMatch(candidateBlock, /plainto_tsquery/);
});
