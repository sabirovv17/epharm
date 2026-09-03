import { cookies } from "next/headers";
import { secureMedusaBaseUrl } from "@/lib/medusaUrl";

const BASE = secureMedusaBaseUrl(process.env.MEDUSA_URL);
const PK = process.env.MEDUSA_PUBLISHABLE_KEY || "";
const CUSTOMER_COOKIE = "ms_cust";

export type CustomerSession =
  | { status: "authenticated"; customerId: string }
  | { status: "anonymous" }
  | { status: "unavailable" };

function bearer(req: Request): string | null {
  const header = req.headers.get("authorization") || "";
  const match = header.match(/^Bearer\s+(.+)$/i);
  return match ? match[1].trim() : null;
}

/** Validate the web cookie or mobile bearer token against Medusa. */
export async function customerSession(req: Request): Promise<CustomerSession> {
  const token = bearer(req) || (await cookies()).get(CUSTOMER_COOKIE)?.value || null;
  if (!token) return { status: "anonymous" };
  if (!BASE || !PK || process.env.MEDUSA_ENABLED === "false") return { status: "unavailable" };

  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 8_000);
  try {
    const response = await fetch(`${BASE}/store/customers/me`, {
      headers: {
        authorization: `Bearer ${token}`,
        "x-publishable-api-key": PK,
      },
      cache: "no-store",
      signal: controller.signal,
    });
    if (response.status === 401 || response.status === 404) return { status: "anonymous" };
    if (!response.ok) return { status: "unavailable" };
    const payload = await response.json().catch(() => null) as { customer?: { id?: unknown } } | null;
    const customerId = typeof payload?.customer?.id === "string" ? payload.customer.id.trim() : "";
    return customerId ? { status: "authenticated", customerId } : { status: "anonymous" };
  } catch {
    return { status: "unavailable" };
  } finally {
    clearTimeout(timeout);
  }
}
