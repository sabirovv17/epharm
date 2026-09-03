"use client";

import { useEffect, useState } from "react";
import type { Order } from "@/lib/data/account";

/**
 * Единственный источник заказов кабинета — Meduza через customer API.
 */
export function useOrders(): Order[] {
  const [orders, setOrders] = useState<Order[]>([]);
  useEffect(() => {
    let alive = true;
    let inFlight = false;
    const refresh = async () => {
      if (inFlight || document.visibilityState !== "visible") return;
      inFlight = true;
      try {
        const response = await fetch("/api/customer/orders", { cache: "no-store" });
        if (!response.ok) throw new Error(`orders_http_${response.status}`);
        const data = await response.json();
        if (alive && Array.isArray(data?.orders)) setOrders(data.orders);
      } catch {
        // Keep the last successful snapshot during a transient network failure.
      } finally {
        inFlight = false;
      }
    };
    const refreshVisible = () => { if (document.visibilityState === "visible") void refresh(); };
    void refresh();
    const timer = window.setInterval(refreshVisible, 15_000);
    document.addEventListener("visibilitychange", refreshVisible);
    return () => {
      alive = false;
      window.clearInterval(timer);
      document.removeEventListener("visibilitychange", refreshVisible);
    };
  }, []);
  return orders;
}
