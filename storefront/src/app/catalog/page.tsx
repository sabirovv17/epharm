import type { Metadata } from "next";
import { getProducts, getCatTree } from "@/lib/api";
import { getCatalogReadPage } from "@/lib/catalog-read";
import { parseCatalogQuery } from "@/lib/catalog-query";
import { CatalogView } from "@/components/catalog/CatalogView";

export const metadata: Metadata = { title: "Каталог — Inkar" };
export const dynamic = "force-dynamic";
export const revalidate = 0;

export default async function CatalogPage({
  searchParams,
}: {
  searchParams: Promise<{ q?: string | string[] }>;
}) {
  const rawSearch = await searchParams;
  const q = Array.isArray(rawSearch.q) ? rawSearch.q[0] : rawSearch.q;
  // The first paint only consumes products and the total. Building the full
  // facet set here scans the complete offer table before any HTML can render;
  // CatalogView requests those facets immediately after hydration instead.
  const params = new URLSearchParams({ limit: "24", offset: "0", facets: "0" });
  if (q) params.set("q", q);
  const initialQuery = parseCatalogQuery(params);
  const [localPage, tree] = await Promise.all([getCatalogReadPage(initialQuery), getCatTree()]);
  const products = localPage?.products ?? (await getProducts()).slice(0, 24);

  return (
    <CatalogView
      key={initialQuery.q || "catalog"}
      products={products}
      tree={tree}
      totalCount={localPage?.count ?? products.length}
      searchQuery={initialQuery.q}
    />
  );
}
