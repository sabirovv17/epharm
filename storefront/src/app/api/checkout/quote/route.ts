import { NextResponse } from "next/server";
import { CheckoutQuoteError, createCheckoutQuote, type QuoteItem } from "@/lib/checkoutQuote";
import { clientIp, rateLimit } from "@/lib/rateLimit";

export const dynamic = "force-dynamic";

const NO_STORE = { "cache-control": "no-store" };

export async function POST(request: Request) {
  const now = Date.now();
  if (!rateLimit(`checkout-quote:${clientIp(request)}`, 20, 60_000, now)) {
    return NextResponse.json({ error: "quote_rate_limited" }, { status: 429, headers: NO_STORE });
  }
  const body = await request.json().catch(() => null) as Record<string, unknown> | null;
  if (!body) return NextResponse.json({ error: "invalid_quote" }, { status: 400, headers: NO_STORE });
  if (Array.isArray(body.items) && body.items.length > 50) {
    return NextResponse.json({ error: "too_many_quote_items" }, { status: 413, headers: NO_STORE });
  }
  const fulfillment = body.fulfillment === "pickup" || body.fulfillment === "pharmacy"
    ? body.fulfillment
    : "warehouse";
  try {
    const quote = await createCheckoutQuote({
      items: Array.isArray(body.items) ? body.items as QuoteItem[] : [],
      fulfillment,
      preferredPharmacy: body.preferredPharmacy && typeof body.preferredPharmacy === "object"
        ? body.preferredPharmacy as { address?: string; city?: string }
        : null,
    });
    return NextResponse.json({ quote }, { status: 200, headers: NO_STORE });
  } catch (error) {
    if (error instanceof CheckoutQuoteError) {
      return NextResponse.json({ error: error.code }, { status: error.status, headers: NO_STORE });
    }
    return NextResponse.json({ error: "quote_unavailable" }, { status: 503, headers: NO_STORE });
  }
}
