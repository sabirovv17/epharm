import { NextResponse } from "next/server";
import { searchProducts } from "@/lib/api";
import { getCatalogReadPage } from "@/lib/catalog-read";
import { parseCatalogQuery } from "@/lib/catalog-query";
import { fallbackJsonResponse } from "@/lib/storefront-fallback";

export const dynamic = "force-dynamic";

/** Серверный поиск по всей базе Medusa (q), а не по первым 100 загруженным. */
export async function GET(req: Request) {
  const q = (new URL(req.url).searchParams.get("q") || "").trim();
  if (!q) return NextResponse.json({ products: [] }, { headers: { "cache-control": "no-store" } });
  if (q.length > 120) {
    return NextResponse.json(
      { products: [], error: { code: "too_long", field: "q" } },
      { status: 400, headers: { "cache-control": "no-store" } },
    );
  }
  if (process.env.CATALOG_READ_SOURCE?.trim().toLowerCase() === "fallback") {
    const fallback = await fallbackJsonResponse("/api/search", `?q=${encodeURIComponent(q)}`);
    if (fallback) return fallback;
  }
  const params = new URLSearchParams({
    q,
    limit: "48",
    offset: "0",
    facets: "0",
  });
  const page = await getCatalogReadPage(parseCatalogQuery(params));
  // Keep the original Medusa-backed path as a last-known-real fallback when
  // the verified PostgreSQL snapshot is temporarily unavailable.
  const products = page?.products ?? await searchProducts(q, 48);
  return NextResponse.json({ products }, { headers: { "cache-control": "no-store" } });
}
