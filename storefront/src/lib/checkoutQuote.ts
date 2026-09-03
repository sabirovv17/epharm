import crypto from "node:crypto";
import {
  getCachedPharmacyPrices,
  validateLocalProductVariants,
  type LocalPharmacyPrice as PharmaPrice,
} from "@/lib/catalog-db";
import { isStrongRuntimeSecret } from "@/lib/serverSecrets";
import {
  basePricesForFulfillment,
  priceCheckoutLines,
  type CheckoutFulfillment,
  type CheckoutPricingAdjustment,
} from "./checkoutPricing";
import { canonicalizeCheckoutItems, type CanonicalCheckoutItem } from "./checkoutItems";

export type QuoteItem = CanonicalCheckoutItem;

export type QuotePharmacy = Pick<PharmaPrice, "id" | "name" | "city" | "address">;

export type CheckoutQuote = {
  id: string;
  subtotal: number;
  total: number;
  currency: "KZT";
  expiresAt: string;
  pharmacy: QuotePharmacy | null;
  lines: Array<{ productId: string; variantId: string; quantity: number; unitPrice: number; total: number }>;
  adjustments: CheckoutPricingAdjustment[];
};

export type SignedQuote = {
  version: 1;
  expires: number;
  itemsHash: string;
  total: number;
  pharmacy: QuotePharmacy | null;
  fulfillment: CheckoutFulfillment;
  lines: Array<{ productId: string; variantId: string; quantity: number; unitPrice: number }>;
};

export class CheckoutQuoteError extends Error {
  status: number;
  code: string;

  constructor(status: number, code: string) {
    super(code);
    this.status = status;
    this.code = code;
  }
}

function quoteSecret(): string {
  const value = process.env.CHECKOUT_QUOTE_SECRET || process.env.CUSTOMER_AUTH_SECRET;
  if (!isStrongRuntimeSecret(value)) throw new CheckoutQuoteError(503, "quote_secret_not_configured");
  return value!;
}

function itemsHash(items: QuoteItem[]): string {
  return crypto.createHash("sha256").update(JSON.stringify(items)).digest("base64url");
}

function encodeQuote(payload: SignedQuote): string {
  const encoded = Buffer.from(JSON.stringify(payload)).toString("base64url");
  const signature = crypto.createHmac("sha256", quoteSecret()).update(encoded).digest("base64url");
  return `${encoded}.${signature}`;
}

function normalizeAddress(value: string): string[] {
  const ignored = new Set(["г", "город", "ул", "улица", "пр", "проспект", "мкр", "микрорайон", "дом", "д", "аптека"]);
  return String(value || "")
    .toLocaleLowerCase("ru-RU")
    .replace(/ё/g, "е")
    .replace(/[^a-zа-я0-9]+/gi, " ")
    .trim()
    .split(/\s+/)
    .filter((token) => token && !ignored.has(token));
}

function addressScore(pharmacy: PharmaPrice, preferredAddress: string, preferredCity: string): number {
  const wanted = normalizeAddress(`${preferredCity} ${preferredAddress}`);
  const candidate = new Set(normalizeAddress(`${pharmacy.city} ${pharmacy.address || ""} ${pharmacy.name}`));
  if (wanted.length === 0) return 0;
  const numeric = wanted.filter((token) => /^\d/.test(token));
  if (numeric.length > 0 && numeric.some((token) => !candidate.has(token))) return 0;
  const matched = wanted.filter((token) => candidate.has(token)).length;
  return matched / wanted.length;
}

function commonPharmacies(priceLists: PharmaPrice[][]): Array<{ pharmacy: PharmaPrice; total: number; prices: number[] }> {
  if (priceLists.length === 0) return [];
  const byProduct = priceLists.map((list) => new Map(list.map((pharmacy) => [pharmacy.id, pharmacy])));
  const common: Array<{ pharmacy: PharmaPrice; total: number; prices: number[] }> = [];
  for (const pharmacy of priceLists[0]) {
    const matches = byProduct.map((list) => list.get(pharmacy.id));
    if (matches.some((item) => !item)) continue;
    const prices = matches.map((item) => item!.price);
    common.push({ pharmacy, prices, total: prices.reduce((sum, price) => sum + price, 0) });
  }
  return common;
}

export async function createCheckoutQuote(input: {
  items: QuoteItem[];
  fulfillment: CheckoutFulfillment;
  preferredPharmacy?: { address?: string; city?: string } | null;
}): Promise<CheckoutQuote> {
  const items = canonicalizeCheckoutItems(input.items);
  if (!items || items.length > 30) throw new CheckoutQuoteError(400, "invalid_quote_items");

  const [validOwnership, priceInfo] = await Promise.all([
    validateLocalProductVariants(items),
    Promise.all(items.map((item) => getCachedPharmacyPrices(item.productId, { allowStale: true }))),
  ]);
  if (!validOwnership) throw new CheckoutQuoteError(409, "cart_variant_mismatch");
  if (priceInfo.some((info) => !info || info.pharmacies.length === 0)) {
    throw new CheckoutQuoteError(409, "cart_item_unavailable");
  }
  const lists = priceInfo.map((info) => info!.pharmacies);
  let selected: { pharmacy: PharmaPrice; prices: number[] } | null = null;

  if (input.fulfillment === "pickup" || input.fulfillment === "pharmacy") {
    const allCommon = commonPharmacies(lists)
      .map((candidate) => ({
        ...candidate,
        total: candidate.prices.reduce((sum, price, index) => sum + price * items[index].quantity, 0),
      }))
      .sort((left, right) => left.total - right.total || left.pharmacy.name.localeCompare(right.pharmacy.name));
    const preferredCity = normalizeAddress(String(input.preferredPharmacy?.city || "")).join(" ");
    const common = preferredCity
      ? allCommon.filter((candidate) => normalizeAddress(candidate.pharmacy.city).join(" ") === preferredCity)
      : allCommon;
    if (common.length === 0) throw new CheckoutQuoteError(409, "no_common_pharmacy");

    const preferredAddress = String(input.preferredPharmacy?.address || "");
    const preferredCityLabel = String(input.preferredPharmacy?.city || "");
    if (preferredAddress) {
      const matched = common
        .map((candidate) => ({ candidate, score: addressScore(candidate.pharmacy, preferredAddress, preferredCityLabel) }))
        .filter((value) => value.score >= 0.45)
        .sort((left, right) => right.score - left.score || left.candidate.total - right.candidate.total)[0];
      if (!matched) throw new CheckoutQuoteError(409, "selected_pharmacy_unavailable");
      selected = matched.candidate;
    } else {
      selected = common[0];
    }
  }

  const prices = basePricesForFulfillment(
    priceInfo.map((info) => info!.min!),
    selected?.prices ?? null,
    input.fulfillment,
  );
  if (prices.length !== items.length || prices.some((price) => !Number.isSafeInteger(price) || price <= 0)) {
    throw new CheckoutQuoteError(409, "cart_price_unavailable");
  }
  const pricing = priceCheckoutLines(
    items.map((item, index) => ({ unitPrice: prices[index], quantity: item.quantity })),
    input.fulfillment,
  );
  const lines = items.map((item, index) => ({
    ...item,
    unitPrice: prices[index],
    total: pricing.lineTotals[index],
  }));
  const total = pricing.total;
  const pharmacy = selected ? {
    id: selected.pharmacy.id,
    name: selected.pharmacy.name,
    city: selected.pharmacy.city,
    ...(selected.pharmacy.address ? { address: selected.pharmacy.address } : {}),
  } : null;
  const expires = Date.now() + 5 * 60_000;
  const payload: SignedQuote = {
    version: 1,
    expires,
    itemsHash: itemsHash(items),
    total,
    pharmacy,
    fulfillment: input.fulfillment,
    lines: lines.map(({ productId, variantId, quantity, unitPrice }) => ({
      productId,
      variantId,
      quantity,
      unitPrice,
    })),
  };
  return {
    id: encodeQuote(payload),
    subtotal: pricing.subtotal,
    total,
    currency: "KZT",
    expiresAt: new Date(expires).toISOString(),
    pharmacy,
    lines,
    adjustments: pricing.adjustments,
  };
}

export function verifyCheckoutQuote(token: unknown, items: QuoteItem[]): SignedQuote | null {
  const canonicalItems = canonicalizeCheckoutItems(items);
  if (!canonicalItems) return null;
  if (typeof token !== "string" || token.length < 32 || token.length > 4_096) return null;
  const [encoded, supplied] = token.split(".");
  if (!encoded || !supplied) return null;
  const expected = crypto.createHmac("sha256", quoteSecret()).update(encoded).digest();
  let actual: Buffer;
  try { actual = Buffer.from(supplied, "base64url"); } catch { return null; }
  if (actual.length !== expected.length || !crypto.timingSafeEqual(actual, expected)) return null;
  try {
    const payload = JSON.parse(Buffer.from(encoded, "base64url").toString("utf8")) as SignedQuote;
    if (payload.version !== 1 || payload.expires <= Date.now() || payload.itemsHash !== itemsHash(canonicalItems)) return null;
    if (!Number.isSafeInteger(payload.total) || payload.total <= 0) return null;
    if (!Array.isArray(payload.lines) || payload.lines.length !== canonicalItems.length) return null;
    if (payload.lines.some((line, index) =>
      line.productId !== canonicalItems[index].productId
      || line.variantId !== canonicalItems[index].variantId
      || line.quantity !== canonicalItems[index].quantity
      || !Number.isSafeInteger(line.unitPrice)
      || line.unitPrice <= 0
    )) return null;
    return payload;
  } catch {
    return null;
  }
}
