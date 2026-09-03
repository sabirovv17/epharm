import { NextResponse } from "next/server";
import { getCatalogReadCategoryTree } from "@/lib/catalog-read";

export const dynamic = "force-dynamic";
export const revalidate = 0;

const CATEGORY_CACHE = {
  "cache-control": "public, max-age=60, s-maxage=600, stale-while-revalidate=86400",
};

export async function GET() {
  const tree = await getCatalogReadCategoryTree();
  if (!tree) {
    return NextResponse.json(
      { error: "catalog_database_unavailable" },
      { status: 503, headers: { "cache-control": "no-store" } },
    );
  }
  return NextResponse.json(tree, { headers: CATEGORY_CACHE });
}
