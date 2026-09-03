import { NextResponse } from "next/server";
import { getPrices } from "@/lib/api";

export const dynamic = "force-dynamic";

const PRODUCT_ID = /^prod_[A-Za-z0-9]+$/;
const MAX_IDS = 6;
const CONCURRENCY = 3;

/** Пакет минимальных цен для видимых карточек вместо отдельного HTTP на каждую. */
export async function GET(request: Request) {
  const ids = [...new Set((new URL(request.url).searchParams.get("ids") || "").split(","))]
    .map((id) => id.trim())
    .filter((id) => PRODUCT_ID.test(id))
    .slice(0, MAX_IDS);

  if (ids.length === 0) {
    return NextResponse.json({ prices: {} }, { headers: { "cache-control": "public, max-age=30" } });
  }

  const prices: Record<string, { min: number | null; max: number | null; count: number }> = {};
  const failedIds: string[] = [];
  for (let offset = 0; offset < ids.length; offset += CONCURRENCY) {
    const chunk = ids.slice(offset, offset + CONCURRENCY);
    const values = await Promise.all(chunk.map((id) => getPrices(id)));
    chunk.forEach((id, index) => {
      const info = values[index];
      if (!info) failedIds.push(id);
      prices[id] = { min: info?.min ?? null, max: info?.max ?? null, count: info?.count ?? 0 };
    });
  }

  return NextResponse.json(
    { prices, failedIds },
    {
      headers: {
        "cache-control": failedIds.length > 0
          ? "no-store"
          : "public, max-age=30, s-maxage=60, stale-while-revalidate=300",
        ...(failedIds.length > 0 ? { "x-data-state": "partial" } : {}),
      },
    },
  );
}
