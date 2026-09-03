import { NextResponse } from "next/server";
import { listOrders } from "@/lib/orders/store";
import { isStrongRuntimeSecret } from "@/lib/serverSecrets";

export const dynamic = "force-dynamic";
const NO_STORE = { "cache-control": "no-store" };

function authorized(req: Request) {
  const expected = process.env.ADMIN_TOKEN;
  return Boolean(isStrongRuntimeSecret(expected) && req.headers.get("x-admin-token") === expected);
}

export async function GET(req: Request) {
  if (!authorized(req)) return NextResponse.json({ error: "unauthorized" }, { status: 401, headers: NO_STORE });
  try {
    return NextResponse.json({ orders: await listOrders() }, { headers: NO_STORE });
  } catch (error) {
    console.error("[api/admin/orders] list failed", error);
    return NextResponse.json({ error: "orders_backend_unavailable" }, { status: 503, headers: NO_STORE });
  }
}

export async function PATCH(req: Request) {
  if (!authorized(req)) return NextResponse.json({ error: "unauthorized" }, { status: 401, headers: NO_STORE });
  return NextResponse.json(
    { error: "status_owned_by_epharm", endpoint: "/api/admin/fulfillment/orders" },
    { status: 409, headers: NO_STORE },
  );
}
