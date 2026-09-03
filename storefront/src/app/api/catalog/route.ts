import { NextResponse } from "next/server";
import { getProducts, getCategories, getBrands, withCategoryCounts } from "@/lib/api";
import { getMedusaCatalogSnapshot, type MedusaCatalogSnapshot } from "@/lib/medusa";
import { getCatalogReadPage } from "@/lib/catalog-read";
import { categoriesWithFullFacetCounts, postgresCatalogMeta } from "@/lib/catalog-api-response";
import { fallbackJsonResponse } from "@/lib/storefront-fallback";
import {
  CatalogQueryError,
  buildCatalogBrands,
  buildCatalogFacets,
  cataloguePagination,
  filterAndSortCatalog,
  parseCatalogQuery,
  type CatalogQuery,
} from "@/lib/catalog-query";

export const dynamic = "force-dynamic";
export const revalidate = 0;

const CATALOG_CACHE = {
  "cache-control": "public, max-age=30, s-maxage=300, stale-while-revalidate=86400",
};
const DEGRADED_CACHE = {
  "cache-control": "public, max-age=10, s-maxage=30, stale-while-revalidate=300",
};
const NO_STORE = { "cache-control": "no-store" };
const MAX_LEGACY_BRANDS = 100;

type CatalogSnapshotResult = {
  snapshot: MedusaCatalogSnapshot;
  source: "postgres" | "medusa" | "medusa_fallback";
  degraded: boolean;
};

async function catalogueSnapshot(): Promise<CatalogSnapshotResult> {
  const preferred = String(process.env.CATALOG_READ_SOURCE || "medusa").trim().toLowerCase();
  if (preferred !== "postgres") {
    return { snapshot: await getMedusaCatalogSnapshot(), source: "medusa", degraded: false };
  }
  return { snapshot: await getMedusaCatalogSnapshot(), source: "medusa_fallback", degraded: true };
}

function filtersForResponse(query: CatalogQuery) {
  return {
    q: query.q || null,
    category: query.category,
    brands: query.brands,
    minPrice: query.minPrice,
    maxPrice: query.maxPrice,
    inStock: query.inStock,
    sale: query.sale,
    prescription: query.prescription,
    sort: query.sort,
  };
}

/**
 * Backwards-compatible catalogue endpoint.
 *
 * Existing clients can keep reading `products`, `categories` and `brands`.
 * New clients can page/filter the complete Medusa snapshot and consume the
 * additional `pagination`, `facets`, `filters` and `meta` fields.
 */
export async function GET(request: Request) {
  let query: CatalogQuery;
  try {
    query = parseCatalogQuery(new URL(request.url).searchParams);
  } catch (error) {
    if (!(error instanceof CatalogQueryError)) throw error;
    return NextResponse.json(
      {
        products: [],
        categories: [],
        brands: [],
        error: { code: error.code, field: error.field },
      },
      { status: 400, headers: NO_STORE },
    );
  }

  if (process.env.CATALOG_READ_SOURCE?.trim().toLowerCase() === "fallback") {
    const url = new URL(request.url);
    const fallback = await fallbackJsonResponse("/api/catalog", url.search);
    if (fallback) return fallback;
  }

  const localPage = await getCatalogReadPage(query);
  if (localPage) {
    const pagination = cataloguePagination(localPage.count, query);
    const generatedAt = new Date().toISOString();
    let facetPayload: Record<string, unknown> = {};
    if (query.includeFacets) {
      if (!localPage.facets) throw new Error("catalog_facets_missing");
      const [categories, brands] = await Promise.all([getCategories(), getBrands()]);
      const fullFacets = categoriesWithFullFacetCounts(categories, localPage.facets);
      facetPayload = {
        categories: fullFacets.categories,
        // Keep the legacy field useful without repeating a multi-hundred-KB
        // full brand directory on every paginated catalogue response.
        brands: brands.slice(0, MAX_LEGACY_BRANDS),
        facets: fullFacets.facets,
      };
    }
    const response = NextResponse.json(
      {
        products: localPage.products,
        ...facetPayload,
        schemaVersion: 2,
        count: localPage.count,
        catalogTotal: localPage.catalogTotal,
        limit: query.limit,
        offset: query.offset,
        page: pagination.page,
        hasMore: pagination.hasNext,
        nextOffset: pagination.nextOffset,
        pagination,
        filters: filtersForResponse(query),
        meta: postgresCatalogMeta({
          catalogTotal: localPage.catalogTotal,
          facetProductCount: localPage.count,
          loadedCount: localPage.products.length,
          generatedAt,
          includeFacets: query.includeFacets,
        }),
      },
      { headers: CATALOG_CACHE },
    );
    response.headers.set("x-data-state", "fresh");
    response.headers.set("x-catalog-source", "postgres");
    return response;
  }

  if (process.env.MEDUSA_ENABLED === "false") {
    return NextResponse.json(
      {
        products: [],
        categories: [],
        brands: [],
        error: { code: "catalog_database_unavailable" },
        meta: { source: "postgres", degraded: true, dataState: "unavailable" },
      },
      { status: 503, headers: NO_STORE },
    );
  }

  const [categories, snapshotResult] = await Promise.all([
    query.includeFacets ? getCategories() : Promise.resolve([]),
    catalogueSnapshot()
      .then((result) => ({ ok: true as const, ...result }))
      .catch((error: unknown) => ({ ok: false as const, error })),
  ]);

  let snapshot: MedusaCatalogSnapshot;
  let degraded = false;
  let source: CatalogSnapshotResult["source"] | "legacy_cache" = "medusa";
  if (snapshotResult.ok) {
    snapshot = snapshotResult.snapshot;
    degraded = snapshotResult.degraded;
    source = snapshotResult.source;
  } else {
    degraded = true;
    source = "legacy_cache";
    console.error("[catalog] full snapshot unavailable; using the legacy cached page", snapshotResult.error);
    const products = await getProducts();
    snapshot = {
      products,
      sourceCount: products.length,
      loadedCount: products.length,
      complete: false,
      stale: true,
      generatedAt: new Date().toISOString(),
    };
  }

  const matched = filterAndSortCatalog(snapshot.products, query);
  const products = matched.slice(query.offset, query.offset + query.limit);
  const pagination = cataloguePagination(matched.length, query);
  const state = degraded || !snapshot.complete ? "degraded" : snapshot.stale ? "stale" : "fresh";
  const response = NextResponse.json(
    {
      // Original response shape. Values now come from the complete snapshot,
      // while `products` is the requested server-side page.
      products,
      ...(query.includeFacets ? {
        categories: withCategoryCounts(categories, snapshot.products),
        brands: buildCatalogBrands(snapshot.products),
        facets: buildCatalogFacets(matched, categories),
      } : {}),

      // Additive v2 fields; old clients safely ignore them.
      schemaVersion: 2,
      count: matched.length,
      catalogTotal: snapshot.sourceCount,
      limit: query.limit,
      offset: query.offset,
      page: pagination.page,
      hasMore: pagination.hasNext,
      nextOffset: pagination.nextOffset,
      pagination,
      filters: filtersForResponse(query),
      meta: {
        source,
        priceScope: source === "postgres" ? "variant_or_pharmacy_offer" : "medusa_calculated_price",
        availabilityScope: source === "postgres" ? "variant_or_pharmacy_offer" : "medusa_calculated_price",
        generatedAt: snapshot.generatedAt,
        sourceCount: snapshot.sourceCount,
        loadedCount: snapshot.loadedCount,
        complete: snapshot.complete,
        stale: snapshot.stale,
        degraded,
        dataState: state,
        responseScope: "bounded_page",
        ...(query.includeFacets ? {
          facetScope: "filtered_catalog",
          facetProductCount: matched.length,
          facetBrandScope: "all_matching_products",
          facetCategoryScope: "assigned_category_handles",
        } : {
          facetScope: "omitted",
        }),
      },
    },
    { headers: degraded ? DEGRADED_CACHE : CATALOG_CACHE },
  );
  response.headers.set("x-data-state", state);
  response.headers.set("x-catalog-source", source);
  return response;
}
