/* eslint-disable @typescript-eslint/no-explicit-any */
import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import { customerAuthMode, readDemoSession } from "@/lib/customerAuthMode";
import { medusaStore } from "@/lib/medusaStore";
import { listCustomerOrders, type StoredOrder } from "@/lib/orders/store";

export const dynamic = "force-dynamic";
const NO_STORE = { "cache-control": "no-store" };
const AUTH_MODE = customerAuthMode();
const DEMO_COOKIE = "inkar_demo_session";
const CUSTOMER_AUTH_SECRET = process.env.CUSTOMER_AUTH_SECRET || "";
const MAX_PREVIEW_ITEMS = 8;

function text(value: unknown, maxLength = 200): string {
  return String(value ?? "").trim().slice(0, maxLength);
}

function receiptCode(value: unknown): string {
  const code = text(value, 12);
  return /^\d{6}$/.test(code) ? code : "";
}

function deliveryMethod(value: unknown): string {
  const method = text(value, 24).toLowerCase();
  return ["courier", "pickup", "post"].includes(method) ? method : "";
}

function orderPreview(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return value.slice(0, MAX_PREVIEW_ITEMS).map((raw, index) => {
    const item = raw && typeof raw === "object" ? raw as Record<string, unknown> : {};
    const title = text(item.product_title || item.title || item.name, 160);
    const sku = text(item.sku, 80);
    const quantityValue = Number(item.quantity);
    const quantity = Number.isSafeInteger(quantityValue) && quantityValue > 0
      ? quantityValue
      : 1;
    const label = title || (sku ? `SKU ${sku}` : `Товар ${index + 1}`);
    return quantity > 1 ? `${label} × ${quantity}` : label;
  });
}

function storedOrderPreview(order: StoredOrder): string[] {
  return orderPreview(order.metadata?.line_items);
}

function customerStatus(value: unknown): "processing" | "delivered" | "cancelled" {
  const status = String(value || "").trim().toLowerCase();
  if (status.includes("cancel") || status.includes("отмен")) return "cancelled";
  if (status.includes("deliver") || status.includes("complete") || status.includes("получ")) return "delivered";
  return "processing";
}

function demoOrder(order: StoredOrder) {
  return {
    id: `INK-${order.n}`,
    date: order.date,
    status: customerStatus(order.fulfillmentStatus || order.status),
    total: order.sum,
    itemsCount: order.items,
    preview: storedOrderPreview(order),
    code: receiptCode(order.code),
    delivery: deliveryMethod(order.delivery),
  };
}

export async function GET(req: Request) {
  if (AUTH_MODE === "demo") {
    const cookieStore = await cookies();
    const signedSession = req.headers.get("x-demo-session") || cookieStore.get(DEMO_COOKIE)?.value || null;
    const sessionId = readDemoSession(signedSession, CUSTOMER_AUTH_SECRET);
    if (!sessionId) {
      return NextResponse.json({ orders: [], error: "demo_session_required" }, { status: 401, headers: NO_STORE });
    }
    try {
      const orders = await listCustomerOrders(`demo:${sessionId}`, 100);
      return NextResponse.json(
        { orders: orders.filter((order) => order.demo === true).map(demoOrder) },
        { headers: NO_STORE },
      );
    } catch (error) {
      return NextResponse.json(
        { orders: [], error: error instanceof Error ? error.message : "backend unavailable" },
        { status: 502, headers: NO_STORE },
      );
    }
  }

  const header = req.headers.get("authorization") || "";
  const bearer = header.match(/^Bearer\s+(.+)$/i)?.[1]?.trim() || null;
  const token = bearer || (await cookies()).get("ms_cust")?.value || null;
  if (!token) return NextResponse.json({ orders: [] }, { headers: NO_STORE });
  try {
    const [data, customerData] = await Promise.all([
      medusaStore<{ orders?: any[] }>("/store/orders", {
        token,
        query: { limit: 100, offset: 0, order: "-created_at", fields: "+items.*" },
      }),
      medusaStore<{ customer?: { id?: string } }>("/store/customers/me", { token })
        .catch(() => null),
    ]);
    const customerId = text(
      customerData?.customer?.id
      || data.orders?.find((order) => order?.customer_id)?.customer_id,
      256,
    );
    const storedOrders = customerId
      ? await listCustomerOrders(customerId, 100).catch(() => [])
      : [];
    const storedBySourceId = new Map(
      storedOrders.map((order) => [order.sourceOrderId, order]),
    );
    const orders = (data.orders || []).map((order) => {
      const stored = storedBySourceId.get(String(order.id || ""));
      const status = stored?.fulfillmentStatus
        ? customerStatus(stored.fulfillmentStatus)
        : order.status === "canceled"
          ? "cancelled"
          : ["completed", "fulfilled", "delivered"].includes(String(order.fulfillment_status || order.status))
            ? "delivered"
            : "processing";
      const items = Array.isArray(order.items) ? order.items : [];
      const preview = orderPreview(items);
      return {
        id: order.display_id ? `INK-${order.display_id}` : String(order.id),
        date: new Intl.DateTimeFormat("ru-RU").format(new Date(order.created_at || Date.now())),
        status,
        total: Math.round(Number(order.total || 0)),
        itemsCount: items.reduce((sum: number, item: any) => sum + Number(item.quantity || 0), 0),
        preview: preview.length ? preview : stored ? storedOrderPreview(stored) : [],
        code: receiptCode(stored?.code),
        delivery: deliveryMethod(
          stored?.delivery
          || order.metadata?.delivery
          || order.cart?.metadata?.delivery,
        ),
      };
    });
    return NextResponse.json({ orders }, { headers: NO_STORE });
  } catch (error) {
    return NextResponse.json(
      { orders: [], error: error instanceof Error ? error.message : "backend unavailable" },
      { status: 502, headers: NO_STORE },
    );
  }
}
