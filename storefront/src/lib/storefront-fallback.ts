const FALLBACK_TIMEOUT_MS = 60_000;

function fallbackBase(): URL | null {
  const raw = process.env.STOREFRONT_FALLBACK_URL?.trim();
  if (!raw) return null;
  try {
    const url = new URL(raw);
    if (url.protocol !== "https:") return null;
    return url;
  } catch {
    return null;
  }
}

export async function fetchStorefrontFallback(pathname: string, search = "") {
  const base = fallbackBase();
  if (!base || !pathname.startsWith("/api/")) return null;
  const url = new URL(pathname, base);
  url.search = search;
  const response = await fetch(url, {
    cache: "no-store",
    signal: AbortSignal.timeout(FALLBACK_TIMEOUT_MS),
  }).catch(() => null);
  return response?.ok ? response : null;
}

export async function fallbackJsonResponse(pathname: string, search = "") {
  const response = await fetchStorefrontFallback(pathname, search);
  if (!response) return null;
  return new Response(await response.arrayBuffer(), {
    status: response.status,
    headers: {
      "content-type": response.headers.get("content-type") || "application/json; charset=utf-8",
      "cache-control": "public, max-age=15, s-maxage=60, stale-while-revalidate=300",
      "x-catalog-source": "storefront-fallback",
      "x-data-state": "stale",
    },
  });
}
