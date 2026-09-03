export type CanonicalCheckoutItem = {
  productId: string;
  variantId: string;
  quantity: number;
};

const PRODUCT_ID = /^prod_[A-Za-z0-9]+$/;
const VARIANT_ID = /^variant_[A-Za-z0-9]+$/;
const MAX_RAW_ITEMS = 50;
const MAX_QUANTITY_PER_VARIANT = 99;

/**
 * Produces the one item representation that both quote signing and order
 * creation must consume. Invalid or ambiguous quantities are rejected rather
 * than rounded/defaulted, and duplicate variant lines are merged before the
 * per-variant limit is enforced.
 */
export function canonicalizeCheckoutItems(raw: unknown): CanonicalCheckoutItem[] | null {
  if (!Array.isArray(raw) || raw.length === 0 || raw.length > MAX_RAW_ITEMS) return null;

  const merged = new Map<string, CanonicalCheckoutItem>();
  for (const entry of raw) {
    if (!entry || typeof entry !== "object" || Array.isArray(entry)) return null;
    const value = entry as Record<string, unknown>;
    const productId = String(value.productId ?? value.product_id ?? "").trim();
    const variantId = String(value.variantId ?? value.variant_id ?? "").trim();
    const quantity = value.quantity;
    if (!PRODUCT_ID.test(productId)
        || !VARIANT_ID.test(variantId)
        || typeof quantity !== "number"
        || !Number.isSafeInteger(quantity)
        || quantity < 1
        || quantity > MAX_QUANTITY_PER_VARIANT) {
      return null;
    }

    const key = `${productId}:${variantId}`;
    const nextQuantity = (merged.get(key)?.quantity ?? 0) + quantity;
    if (nextQuantity > MAX_QUANTITY_PER_VARIANT) return null;
    merged.set(key, { productId, variantId, quantity: nextQuantity });
  }

  return [...merged.values()].sort((left, right) => (
    left.productId.localeCompare(right.productId)
      || left.variantId.localeCompare(right.variantId)
  ));
}
