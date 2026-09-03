import { createHash } from "node:crypto";

export type EpharmOrderCreated = {
  eventId: string;
  orderId: string;
  number: string;
  pharmacyExternalId: string;
  createdAt: string;
  total: number;
  currency: "KZT";
  delivery: "pickup" | "pharmacy";
  paymentMethod: "cash" | "card" | "kaspi" | "halyk";
  paymentStatus: "pending" | "paid" | "demo_no_charge";
  demo: boolean;
  pickupCode: string;
  lines: Array<{
    productId: string;
    sku: string;
    title: string;
    quantity: number;
    unitPrice: number | null;
  }>;
};

type OrderSnapshot = {
  id: string;
  display_number: number;
  placed_at: Date | string;
  total_amount: number | string;
  currency_code: string;
  pickup_code: string | null;
  delivery_method: string;
  is_demo: boolean;
  metadata: Record<string, unknown> | null;
};

function text(value: unknown, max: number): string {
  return String(value ?? "").trim().slice(0, max);
}

function nonNegativeMoney(value: unknown): number | null {
  const number = Number(value);
  const cents = Math.round(number * 100);
  if (!Number.isFinite(number) || number < 0 || !Number.isSafeInteger(cents)) return null;
  if (Math.abs(number - cents / 100) > 1e-9) return null;
  return cents / 100;
}

function optionalNonNegativeMoney(value: unknown): number | null | undefined {
  if (value === null || value === undefined || value === "") return null;
  return nonNegativeMoney(value) ?? undefined;
}

function positiveInteger(value: unknown): number | null {
  const number = Number(value);
  return Number.isSafeInteger(number) && number > 0 ? number : null;
}

function fulfillment(value: unknown): "pickup" | "pharmacy" | null {
  const normalized = text(value, 32).toLowerCase();
  return normalized === "pickup" || normalized === "pharmacy" ? normalized : null;
}

function paymentMethod(value: unknown): EpharmOrderCreated["paymentMethod"] | null {
  const normalized = text(value, 32).toLowerCase();
  return ["cash", "card", "kaspi", "halyk"].includes(normalized)
    ? normalized as EpharmOrderCreated["paymentMethod"]
    : null;
}

function paymentStatus(value: unknown): EpharmOrderCreated["paymentStatus"] | null {
  const normalized = text(value, 32).toLowerCase();
  return ["pending", "paid", "demo_no_charge"].includes(normalized)
    ? normalized as EpharmOrderCreated["paymentStatus"]
    : null;
}

export function requiresEpharmFulfillment(
  row: Pick<OrderSnapshot, "metadata">,
): boolean {
  return fulfillment(row.metadata?.fulfillment) !== null;
}

/**
 * Builds the only payload allowed to leave the storefront. It intentionally has
 * no customer identity, phone, email, delivery address, comments or arbitrary metadata.
 */
export function buildEpharmOrderCreated(
  row: OrderSnapshot,
  eventId: string,
): EpharmOrderCreated | null {
  const metadata = row.metadata || {};
  const pharmacyExternalId = text(metadata.pharmacy_id, 256);
  const delivery = fulfillment(metadata.fulfillment);
  const method = paymentMethod(metadata.payment);
  const status = paymentStatus(metadata.payment_status || "pending");
  const pickupCode = text(row.pickup_code, 6);
  const total = nonNegativeMoney(row.total_amount);
  const createdDate = new Date(row.placed_at);
  const createdAt = Number.isFinite(createdDate.valueOf()) ? createdDate.toISOString() : "";
  const rawLines = Array.isArray(metadata.line_items) ? metadata.line_items.slice(0, 200) : [];
  const lines = rawLines.map((raw) => {
    const line = raw && typeof raw === "object" ? raw as Record<string, unknown> : {};
    const productId = text(line.product_id || line.productId, 256);
    const sku = text(line.sku, 256);
    const quantity = positiveInteger(line.quantity);
    const unitPrice = optionalNonNegativeMoney(line.unit_price ?? line.unitPrice);
    return {
      productId,
      sku,
      title: text(line.product_title || line.title || sku || productId, 500),
      quantity,
      unitPrice,
    };
  });
  if (
    !text(row.id, 256)
    || !/^[0-9a-f-]{36}$/i.test(eventId)
    || !pharmacyExternalId
    || !delivery
    || !method
    || !status
    || !/^\d{6}$/.test(pickupCode)
    || total === null
    || !createdAt
    || String(row.currency_code || "").toUpperCase() !== "KZT"
    || lines.length === 0
    || lines.some((line) => !line.productId || !line.title || line.quantity === null || line.unitPrice === undefined)
    || (row.is_demo !== (status === "demo_no_charge"))
  ) return null;

  return {
    eventId,
    orderId: text(row.id, 256),
    number: String(row.display_number),
    pharmacyExternalId,
    createdAt,
    total,
    currency: "KZT",
    delivery,
    paymentMethod: method,
    paymentStatus: status,
    demo: row.is_demo,
    pickupCode,
    lines: lines.map((line) => ({
      productId: line.productId,
      sku: line.sku,
      title: line.title,
      quantity: line.quantity!,
      unitPrice: line.unitPrice as number | null,
    })),
  };
}

export function frozenOrderBody(event: EpharmOrderCreated): { body: string; sha256: string } {
  const body = JSON.stringify(event);
  return { body, sha256: createHash("sha256").update(body, "utf8").digest("hex") };
}
