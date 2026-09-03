/* eslint-disable @typescript-eslint/no-explicit-any */
import { randomUUID } from "node:crypto";
import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import { MEDUSA_REGION, MEDUSA_SALES_CHANNEL, MedusaStoreError, medusaStore } from "@/lib/medusaStore";
import { customerAuthMode, readDemoSession } from "@/lib/customerAuthMode";
import { clientIp, rateLimit } from "@/lib/rateLimit";
import {
  CheckoutQuoteError,
  createCheckoutQuote,
  verifyCheckoutQuote,
  type QuoteItem,
  type SignedQuote,
} from "@/lib/checkoutQuote";
import { recordCompletedMedusaOrder, recordCompletedStorefrontOrder } from "@/lib/orders/store";
import { canonicalizeCheckoutItems } from "@/lib/checkoutItems";
import { readBoundedJson, RequestBodyError } from "@/lib/httpBody";
import { notifyNativeOrderCreated } from "@/lib/push/order-created";

export const dynamic = "force-dynamic";

const NO_STORE = { "cache-control": "no-store" };
const CUSTOMER_COOKIE = "ms_cust";
const DEMO_COOKIE = "inkar_demo_session";
const CART_COOKIE = "ms_cart";
const MAX_CHECKOUT_BODY_BYTES = 64 * 1024;
const CUSTOMER_AUTH_SECRET = process.env.CUSTOMER_AUTH_SECRET || "";
const AUTH_MODE = customerAuthMode();
type CheckoutReplay = { payload: Record<string, unknown>; status: number; expires: number };
type CheckoutAttempt = { pending: boolean; started: number; replay?: CheckoutReplay };
const checkoutGlobal = globalThis as unknown as { __checkoutAttempts?: Map<string, CheckoutAttempt> };
const checkoutAttempts = (checkoutGlobal.__checkoutAttempts ??= new Map<string, CheckoutAttempt>());

function bearer(req: Request) {
  const value = req.headers.get("authorization") || "";
  const match = value.match(/^Bearer\s+(.+)$/i);
  return match?.[1]?.trim() || null;
}

function itemsOf(raw: unknown) {
  const canonical = canonicalizeCheckoutItems(raw);
  return canonical?.map((item) => ({
    product_id: item.productId,
    variant_id: item.variantId,
    quantity: item.quantity,
  })) ?? [];
}

function optionFor(options: any[], delivery: string) {
  const pattern = delivery === "pickup" ? /pickup|самовывоз|аптек/i : delivery === "post" ? /post|почт/i : /courier|достав|delivery/i;
  return options.find((option) => pattern.test(`${option?.name || ""} ${option?.metadata?.type || ""}`)) || options[0];
}

function providerFor(providers: any[], payment: string) {
  if (payment === "cash") return providers.find((p) => /system_default|manual/i.test(String(p?.id || "")));
  return providers.find((p) => /kaspi|halyk|stripe|card/i.test(String(p?.id || "")));
}

export async function POST(req: Request) {
  let body: Record<string, unknown>;
  try {
    const parsed = await readBoundedJson<unknown>(req, MAX_CHECKOUT_BODY_BYTES);
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
      throw new RequestBodyError(400, "invalid_json");
    }
    body = parsed as Record<string, unknown>;
  } catch (error) {
    if (error instanceof RequestBodyError) {
      return NextResponse.json({ error: error.code }, {
        status: error.status,
        headers: NO_STORE,
      });
    }
    return NextResponse.json({ error: "invalid_json" }, { status: 400, headers: NO_STORE });
  }
  const ip = clientIp(req);
  const rawIdempotency = req.headers.get("x-idempotency-key") || "";
  const idempotency = /^[A-Za-z0-9._:-]{8,128}$/.test(rawIdempotency) ? `${ip}:${rawIdempotency}` : "";
  const requestNow = Date.now();
  for (const [key, value] of checkoutAttempts) {
    if ((value.replay?.expires || value.started + 2 * 60_000) < requestNow) checkoutAttempts.delete(key);
  }
  if (idempotency) {
    const previous = checkoutAttempts.get(idempotency);
    if (previous?.replay) {
      return NextResponse.json(previous.replay.payload, {
        status: previous.replay.status,
        headers: { ...NO_STORE, "x-idempotency-replayed": "true" },
      });
    }
    if (previous?.pending) {
      return NextResponse.json({ error: "checkout_in_progress" }, { status: 409, headers: NO_STORE });
    }
  }
  if (!rateLimit(`checkout:${ip}`, 6, 10 * 60_000, requestNow)) {
    return NextResponse.json({ error: "too_many_orders" }, { status: 429, headers: NO_STORE });
  }
  const items = itemsOf(body?.cartItems ?? body?.items);
  const delivery = String(body?.delivery || "courier");
  const requestedPayment = String(body?.payment || "cash");
  const phone = String(body?.phone || "").replace(/\D/g, "");
  const city = String(body?.city || "Алматы").trim().slice(0, 100);
  const address = String(body?.address || "").trim().slice(0, 300);
  const name = String(body?.name || "Покупатель").trim().slice(0, 100) || "Покупатель";
  if (!items.length || !["courier", "pickup", "post"].includes(delivery)
      || phone.length < 11 || (delivery !== "pickup" && !address)) {
    return NextResponse.json({ error: "invalid_checkout" }, { status: 400, headers: NO_STORE });
  }
  if (idempotency) checkoutAttempts.set(idempotency, { pending: true, started: requestNow });

  const success = (payload: Record<string, unknown>, status: number) => {
    if (idempotency) {
      checkoutAttempts.set(idempotency, {
        pending: false,
        started: requestNow,
        replay: { payload, status, expires: Date.now() + 10 * 60_000 },
      });
    }
    return NextResponse.json(payload, { status, headers: NO_STORE });
  };
  const reject = (payload: Record<string, unknown>, status: number) => {
    if (idempotency) checkoutAttempts.delete(idempotency);
    return NextResponse.json(payload, { status, headers: NO_STORE });
  };

  // Web использует httpOnly cookie, мобильное приложение — тот же customer token
  // в Authorization. Оба пути создают заказ одного покупателя в Medusa.
  const cookieStore = await cookies();
  const authorization = bearer(req);
  const auth = authorization || cookieStore.get(CUSTOMER_COOKIE)?.value || null;
  const demoSession = req.headers.get("x-demo-session") || cookieStore.get(DEMO_COOKIE)?.value || null;
  let step = "cart";
  try {
    let email = String(body?.email || "").trim().toLowerCase();
    const demoSessionId = AUTH_MODE === "demo"
      ? readDemoSession(demoSession, CUSTOMER_AUTH_SECRET)
      : null;
    if (AUTH_MODE === "demo" && !demoSessionId) {
      return reject({ error: "demo_session_required" }, 401);
    }
    const demoCustomer = AUTH_MODE === "demo";
    let customerId: string | undefined;
    if (auth) {
      const me = await medusaStore<{ customer?: any }>("/store/customers/me", { token: auth }).catch(() => null);
      if (me?.customer?.email) email = String(me.customer.email);
      if (me?.customer?.id) customerId = String(me.customer.id);
    }
    const quoteItems: QuoteItem[] = items.map((item) => ({
      productId: item.product_id,
      variantId: item.variant_id,
      quantity: item.quantity,
    }));
    let verifiedQuote: SignedQuote | null = null;
    const requestedFulfillment = delivery === "pickup"
      ? "pickup"
      : body?.fulfillment === "pharmacy"
        ? "pharmacy"
        : "warehouse";
    if (body?.quoteId) {
      verifiedQuote = verifyCheckoutQuote(body.quoteId, quoteItems);
      if (!verifiedQuote) return reject({ error: "quote_invalid_or_expired" }, 409);
    }
    if (!verifiedQuote && demoCustomer) {
      step = "quote";
      const preferredAddress = String(
        body?.pharmacyAddress || body?.pharmacyId || (delivery === "pickup" ? address : ""),
      ).trim().slice(0, 300);
      const generated = await createCheckoutQuote({
        items: quoteItems,
        fulfillment: requestedFulfillment,
        preferredPharmacy: requestedFulfillment === "warehouse"
          ? null
          : { address: preferredAddress, city },
      });
      verifiedQuote = verifyCheckoutQuote(generated.id, quoteItems);
      if (!verifiedQuote) throw new CheckoutQuoteError(503, "quote_verification_failed");
    }
    if (!verifiedQuote) return reject({ error: "quote_required" }, 409);
    if (verifiedQuote.fulfillment !== requestedFulfillment) {
      return reject({ error: "quote_fulfillment_mismatch" }, 409);
    }
    const fulfillmentItems = verifiedQuote.lines.map((line) => ({
      product_id: line.productId,
      variant_id: line.variantId,
      quantity: line.quantity,
      unit_price: line.unitPrice,
    }));

    // A demo checkout is intentionally site-native: the production Medusa has
    // no calculated variant prices, so adding a line item currently returns 500.
    // The signed quote remains the only monetary source of truth.
    const payment = demoCustomer ? "cash" : requestedPayment;
    if (!email || !email.includes("@")) email = `u${phone}@guest.darihana.kz`;
    const checkoutAddress = verifiedQuote.fulfillment === "pickup"
      ? verifiedQuote.pharmacy?.address || address
      : address;
    const promoCode = String(body?.promoCode || "").trim().slice(0, 100);

    if (demoCustomer) {
      step = "persist";
      const stored = await recordCompletedStorefrontOrder({
        idempotencyKey: `demo:${demoSessionId}:${rawIdempotency || randomUUID()}`,
        total: verifiedQuote.total,
        delivery,
        items: fulfillmentItems,
        customerId: customerId || `demo:${demoSessionId}`,
        demo: true,
        metadata: {
          channel: authorization ? "mobile" : "web",
          correlation_id: rawIdempotency || undefined,
          fulfillment: verifiedQuote.fulfillment,
          pharmacy_id: verifiedQuote.pharmacy?.id || "",
          pharmacy_name: verifiedQuote.pharmacy?.name || "",
          pharmacy_address: verifiedQuote.pharmacy?.address || "",
          quoted_goods_total: verifiedQuote.total,
          quote_expires_at: new Date(verifiedQuote.expires).toISOString(),
          pricing_state: "server_quoted",
          payment,
          payment_status: "demo_no_charge",
          city,
          delivery_address: checkoutAddress,
          phone: `+${phone}`,
          email,
          promo_code: promoCode,
          promo_status: promoCode ? "not_applied_in_demo" : undefined,
          comment: String(body?.comment || "").slice(0, 500),
        },
      });
      cookieStore.delete(CART_COOKIE);
      void notifyNativeOrderCreated(String(stored.n)).catch((error) => {
        console.error("[checkout] native order-created push failed", error);
      });
      return success({
        order: {
          id: stored.id,
          n: stored.n,
          date: stored.date,
          sum: stored.sum,
          status: stored.status,
          items: stored.items,
          code: stored.code || "",
          delivery: stored.delivery,
          demo: true,
        },
        adminSync: "stored",
      }, 201);
    }

    const created = await medusaStore<{ cart: any }>("/store/carts", {
      method: "POST", token: auth,
      body: { region_id: MEDUSA_REGION, sales_channel_id: MEDUSA_SALES_CHANNEL, currency_code: "kzt", email },
    });
    let cart = created.cart;
    for (const item of items) {
      step = "items";
      cart = (await medusaStore<{ cart: any }>(`/store/carts/${cart.id}/line-items`, {
        method: "POST",
        token: auth,
        body: { variant_id: item.variant_id, quantity: item.quantity },
      })).cart;
    }

    step = "address";
    const shippingAddress = {
      first_name: name, last_name: "", address_1: checkoutAddress || `${city}, самовывоз`, city,
      postal_code: String(body?.postalCode || "050000"), country_code: "kz", phone: `+${phone}`,
    };
    cart = (await medusaStore<{ cart: any }>(`/store/carts/${cart.id}`, {
      method: "POST", token: auth,
      body: {
        email, shipping_address: shippingAddress, billing_address: shippingAddress,
        metadata: {
          delivery,
          payment,
          fulfillment: verifiedQuote.fulfillment,
          pricing_state: "server_quoted",
          quoted_goods_total: verifiedQuote.total,
          quote_expires_at: new Date(verifiedQuote.expires).toISOString(),
          pharmacy_id: verifiedQuote.pharmacy?.id || "",
          pharmacy_name: verifiedQuote.pharmacy?.name || "",
          pharmacy_address: verifiedQuote.pharmacy?.address || "",
          comment: String(body?.comment || "").slice(0, 500),
        },
      },
    })).cart;

    if (promoCode) {
      step = "promotion";
      cart = (await medusaStore<{ cart: any }>(`/store/carts/${cart.id}/promotions`, { method: "POST", token: auth, body: { promo_codes: [promoCode] } })).cart;
    }

    step = "shipping";
    const shipping = await medusaStore<{ shipping_options?: any[] }>("/store/shipping-options", { token: auth, query: { cart_id: cart.id } });
    const option = optionFor(shipping.shipping_options || [], delivery);
    if (!option?.id) throw new MedusaStoreError(409, { message: "Для корзины не настроен способ доставки" });
    cart = (await medusaStore<{ cart: any }>(`/store/carts/${cart.id}/shipping-methods`, { method: "POST", token: auth, body: { option_id: option.id } })).cart;

    step = "payment";
    const providersData = await medusaStore<{ payment_providers?: any[] }>("/store/payment-providers", { token: auth, query: { region_id: MEDUSA_REGION } });
    const provider = providerFor(providersData.payment_providers || [], payment);
    if (!provider?.id) throw new MedusaStoreError(409, { message: "В регионе не настроен способ оплаты" });
    const collectionData = await medusaStore<{ payment_collection: any }>("/store/payment-collections", { method: "POST", token: auth, body: { cart_id: cart.id } });
    const paymentData = await medusaStore<{ payment_collection: any }>(`/store/payment-collections/${collectionData.payment_collection.id}/payment-sessions`, {
      method: "POST", token: auth, body: { provider_id: provider.id, data: {} },
    });
    const session = paymentData.payment_collection?.payment_sessions?.find((value: any) => value.provider_id === provider.id);
    const redirect = session?.data?.checkout_url || session?.data?.redirect_url;
    if (redirect) {
      return success({ requiresAction: true, redirect, cartId: cart.id }, 202);
    }

    step = "complete";
    const completed = await medusaStore<any>(`/store/carts/${cart.id}/complete`, { method: "POST", token: auth, body: {} });
    if (completed?.type !== "order" || !completed?.order) {
      throw new MedusaStoreError(409, completed?.error || { message: "Заказ не был создан" });
    }
    const order = completed.order;
    const now = order.created_at ? new Date(order.created_at) : new Date();
    const normalized = {
      id: String(order.id),
      n: Number(order.display_id || 0),
      date: new Intl.DateTimeFormat("ru-RU").format(now),
      sum: Math.round(Number(order.total || cart.total || 0)),
      status: "Новый",
      items: Array.isArray(order.items) ? order.items.reduce((sum: number, item: any) => sum + Number(item.quantity || 0), 0) : items.reduce((sum, item) => sum + item.quantity, 0),
      code: "",
      delivery,
      demo: demoCustomer,
    };
    let adminSync: "stored" | "failed" = "stored";
    try {
      const stored = await recordCompletedMedusaOrder({
        order,
        delivery,
        fallbackTotal: Number(order.total || cart.total || verifiedQuote?.total || 0),
        fallbackItems: fulfillmentItems,
        customerId,
        demo: demoCustomer,
        metadata: {
          channel: "web",
          correlation_id: rawIdempotency || String(order.id),
          cart_id: String(cart.id || ""),
          fulfillment: verifiedQuote?.fulfillment || (demoCustomer ? String(body?.fulfillment || "warehouse") : "warehouse"),
          pharmacy_id: verifiedQuote?.pharmacy?.id || "",
          pharmacy_name: verifiedQuote?.pharmacy?.name || "",
          pharmacy_address: verifiedQuote?.pharmacy?.address || "",
          quoted_goods_total: verifiedQuote?.total,
          quote_expires_at: verifiedQuote ? new Date(verifiedQuote.expires).toISOString() : undefined,
          payment,
          payment_status: "pending",
          city,
          delivery_address: checkoutAddress,
          phone: `+${phone}`,
          email,
          promo_code: promoCode,
          comment: String(body?.comment || "").slice(0, 500),
        },
      });
      normalized.code = stored.code || "";
      void notifyNativeOrderCreated(String(stored.n)).catch((error) => {
        console.error("[checkout] native order-created push failed", error);
      });
    } catch (persistenceError) {
      // Medusa has already committed the order. Returning checkout_failed here
      // would prompt a retry and may create a duplicate Medusa order.
      adminSync = "failed";
      console.error("[checkout] completed Medusa order was not persisted for admin", {
        orderId: String(order.id || ""),
        cartId: String(cart.id || ""),
        error: persistenceError instanceof Error ? persistenceError.message : String(persistenceError),
      });
    }
    (await cookies()).delete(CART_COOKIE);
    return success({ order: normalized, adminSync }, 201);
  } catch (error) {
    if (idempotency) checkoutAttempts.delete(idempotency);
    const status = error instanceof CheckoutQuoteError
      ? error.status
      : error instanceof MedusaStoreError
        ? Math.max(400, error.status)
        : 502;
    return NextResponse.json({ error: "checkout_failed", step, message: error instanceof Error ? error.message : "backend unavailable" }, { status, headers: NO_STORE });
  }
}
