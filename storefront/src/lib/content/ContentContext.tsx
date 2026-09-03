"use client";

import { createContext, useContext, useEffect, useRef, useState, useCallback, type ReactNode } from "react";
import { DEFAULT_CONTENT, normalizeContent, type Content, type Order } from "./defaults";

// Реэкспорт модели, чтобы существующие импорты "@/lib/content/ContentContext" не ломались.
export { DEFAULT_CONTENT, ORDER_STATUSES, uid, normalizeContent } from "./defaults";
export type { Banner, Collection, Promo, Order, Story, QuickLink, Content } from "./defaults";

const KEY = "ass_web_cms_v1"; // локальный кэш для мгновенной отрисовки/офлайна
const TOKEN_KEY = "ass_admin_token";

type NewOrder = Pick<Order, "sum" | "items"> & {
  delivery?: string;
  payment?: string;
  name?: string;
  phone?: string;
  email?: string;
  address?: string;
  city?: string;
  pharmacyId?: string;
  pharmacyName?: string;
  pharmacyAddress?: string;
  quoteId?: string;
  fulfillment?: "warehouse" | "pharmacy" | "pickup";
  promoCode?: string;
  comment?: string;
  cartItems: { productId: string; variantId: string; quantity: number }[];
};
type Ctx = {
  content: Content;
  ready: boolean;
  save: (c: Content) => Promise<Content>;
  reset: () => Promise<Content>;
  addOrder: (o: NewOrder) => Promise<Order>;
};
const ContentCtx = createContext<Ctx | null>(null);

export function ContentProvider({ children }: { children: ReactNode }) {
  const [content, setContent] = useState<Content>(DEFAULT_CONTENT);
  const [ready, setReady] = useState(false);
  const contentRef = useRef(content);
  // ref обновляем в эффекте (не во время рендера), чтобы addOrder видел свежий контент
  useEffect(() => { contentRef.current = content; }, [content]);

  useEffect(() => {
    // Стартуем с DEFAULT (совпадает с SSR — без рассинхрона гидрации), затем:
    // 1) мгновенно из локального кэша, чтобы не мигало правками админки.
    try {
      const raw = localStorage.getItem(KEY);
      // eslint-disable-next-line react-hooks/set-state-in-effect -- кэш применяем после гидрации намеренно
      if (raw) setContent(normalizeContent(JSON.parse(raw)));
    } catch {/* ignore */}
    // 2) затем свежий контент с сервера (источник истины — общий для сайта и мобилки).
    let alive = true;
    fetch("/api/content", { cache: "no-store" })
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error("content fetch failed"))))
      .then((j) => {
        if (!alive) return;
        const c = normalizeContent(j);
        setContent(c);
        try { localStorage.setItem(KEY, JSON.stringify(c)); } catch {/* ignore */}
      })
      .catch(() => {/* офлайн — остаёмся на кэше/дефолте */})
      .finally(() => { if (alive) setReady(true); });
    return () => { alive = false; };
  }, []);

  /** The shared server is authoritative: only publish/cache a confirmed save. */
  const persist = useCallback(async (c: Content): Promise<Content> => {
    let token = "";
    try { token = sessionStorage.getItem(TOKEN_KEY) || ""; } catch {/* ignore */}
    const response = await fetch("/api/content", {
      method: "POST",
      headers: { "content-type": "application/json", "x-admin-token": token },
      body: JSON.stringify(c),
    });
    const payload = await response.json().catch(() => null);
    if (!response.ok) {
      const code = payload && typeof payload === "object" && "error" in payload
        ? String(payload.error)
        : `content_save_${response.status}`;
      throw new Error(code);
    }
    const saved = normalizeContent(payload);
    setContent(saved);
    contentRef.current = saved;
    try { localStorage.setItem(KEY, JSON.stringify(saved)); } catch {/* ignore */}
    return saved;
  }, []);

  const reset = useCallback(() => persist(DEFAULT_CONTENT), [persist]);

  const addOrder = useCallback(async (o: NewOrder): Promise<Order> => {
    const r = await fetch("/api/checkout", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-idempotency-key": crypto.randomUUID(),
      },
      body: JSON.stringify(o),
    });
    const data = await r.json().catch(() => ({}));
    if (r.status === 202 && data?.requiresAction && data?.redirect) {
      window.location.assign(String(data.redirect));
      throw new Error("payment_redirect");
    }
    if (!r.ok || !data?.order) throw new Error(data?.error || "order_create_failed");
    const order = data.order as Order;
    setContent((prev) => {
      const next: Content = { ...prev, orders: [order, ...prev.orders.filter((x) => x.id !== order.id)] };
      contentRef.current = next;
      try { localStorage.setItem(KEY, JSON.stringify(next)); } catch {/* ignore */}
      return next;
    });
    return order;
  }, []);

  return <ContentCtx.Provider value={{ content, ready, save: persist, reset, addOrder }}>{children}</ContentCtx.Provider>;
}

export function useContent() {
  const c = useContext(ContentCtx);
  if (!c) throw new Error("useContent must be used within <ContentProvider>");
  return c;
}
