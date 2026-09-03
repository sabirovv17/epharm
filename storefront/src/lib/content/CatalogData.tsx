"use client";

import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import type { Product, Category, Brand } from "@/lib/types";

type Data = { products: Product[]; categories: Category[]; brands: Brand[]; ready: boolean };

const Ctx = createContext<Data>({ products: [], categories: [], brands: [], ready: false });
const REFRESH_MS = 5 * 60_000;

/**
 * Загружает каталог из Medusa и автоматически восстанавливает соединение:
 * быстрый retry после ошибки, обновление при возврате сети/вкладки и фоновая синхронизация.
 */
export function CatalogDataProvider({ children }: { children: ReactNode }) {
  const [data, setData] = useState<Data>({ products: [], categories: [], brands: [], ready: false });

  useEffect(() => {
    let alive = true;
    let running = false;
    let failures = 0;
    let lastLoadedAt = 0;
    let timer: ReturnType<typeof setTimeout> | null = null;
    let activeController: AbortController | null = null;

    const schedule = (delay: number) => {
      if (!alive) return;
      if (timer) clearTimeout(timer);
      timer = setTimeout(load, delay);
    };

    const load = async () => {
      if (!alive || running) return;
      running = true;
      activeController = new AbortController();
      const timeout = setTimeout(() => activeController?.abort(), 15_000);
      try {
        const response = await fetch("/api/catalog", { cache: "default", signal: activeController.signal });
        if (!response.ok) throw new Error(`catalog_${response.status}`);
        const payload = await response.json();
        if (!Array.isArray(payload?.products) || payload.products.length === 0) throw new Error("empty_catalog");
        if (!alive) return;
        failures = 0;
        lastLoadedAt = Date.now();
        setData({
          products: payload.products,
          categories: Array.isArray(payload.categories) ? payload.categories : [],
          brands: Array.isArray(payload.brands) ? payload.brands : [],
          ready: true,
        });
        schedule(REFRESH_MS);
      } catch {
        if (!alive) return;
        failures += 1;
        setData((previous) => ({ ...previous, ready: true }));
        schedule(Math.min(60_000, 5_000 * 2 ** Math.min(failures - 1, 4)));
      } finally {
        clearTimeout(timeout);
        activeController = null;
        running = false;
      }
    };

    const reconnect = () => schedule(0);
    const refreshVisible = () => {
      if (document.visibilityState === "visible" && Date.now() - lastLoadedAt >= REFRESH_MS) reconnect();
    };

    // The full catalogue is useful for search/favourites, but downloading it
    // during hydration competes with the hero and PDP images. Start shortly
    // after the first paint; cached category navigation remains independent.
    const initialDelay = window.location.pathname.startsWith("/product/") ? 2_000 : 700;
    schedule(initialDelay);
    window.addEventListener("online", reconnect);
    document.addEventListener("visibilitychange", refreshVisible);
    return () => {
      alive = false;
      if (timer) clearTimeout(timer);
      activeController?.abort();
      window.removeEventListener("online", reconnect);
      document.removeEventListener("visibilitychange", refreshVisible);
    };
  }, []);

  return <Ctx.Provider value={data}>{children}</Ctx.Provider>;
}

export function useCatalogData() {
  return useContext(Ctx);
}
