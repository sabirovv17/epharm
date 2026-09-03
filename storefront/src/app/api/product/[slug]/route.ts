import { NextResponse } from "next/server";
import { resolveProductBySlug } from "@/lib/api";
import { getKnownMedusaProduct } from "@/lib/medusa";
import { fallbackJsonResponse } from "@/lib/storefront-fallback";

export const dynamic = "force-dynamic";

const HANDLE = /^[a-zA-Z0-9][a-zA-Z0-9_-]{0,199}$/;

/** Rich PDP fields are streamed after the fast catalogue summary is visible. */
export async function GET(_request: Request, { params }: { params: Promise<{ slug: string }> }) {
  const { slug } = await params;
  if (!HANDLE.test(slug)) return NextResponse.json({ product: null }, { status: 400 });
  if (process.env.CATALOG_READ_SOURCE?.trim().toLowerCase() === "fallback") {
    const fallback = await fallbackJsonResponse(`/api/product/${encodeURIComponent(slug)}`);
    if (fallback) return fallback;
  }
  try {
    const product = await resolveProductBySlug(slug);
    if (!product) {
      return NextResponse.json(
        { product: null },
        { status: 404, headers: { "cache-control": "no-store" } },
      );
    }
    return NextResponse.json(
      { product },
      { status: 200, headers: { "cache-control": "public, max-age=30, s-maxage=120, stale-while-revalidate=1800" } },
    );
  } catch {
    const stale = getKnownMedusaProduct(slug);
    if (stale) {
      return NextResponse.json(
        { product: stale, degraded: true },
        {
          status: 200,
          headers: {
            "cache-control": "public, max-age=15, s-maxage=60, stale-while-revalidate=86400",
            "x-data-state": "stale",
          },
        },
      );
    }
    return NextResponse.json(
      { product: null, degraded: true },
      { status: 503, headers: { "cache-control": "no-store", "retry-after": "5" } },
    );
  }
}
