"use client";

import {
  createContext, useContext, useEffect, useState, useCallback, useMemo, useRef, type ReactNode,
} from "react";
import type { Product } from "@/lib/types";
import {
  emitCartItemAdded, emitCartItemRemoved, emitCartItemsCleared,
} from "@/lib/cdp/client";

export interface CartItem {
  product: Product;
  qty: number;
}

interface CartContextValue {
  items: CartItem[];
  count: number;
  subtotal: number;
  savings: number;
  isOpen: boolean;
  add: (product: Product, qty?: number) => void;
  restore: (product: Product, qty?: number) => void;
  remove: (id: string) => void;
  setQty: (id: string, qty: number) => void;
  clear: () => void;
  // Выбор позиций для оформления (по умолчанию выбраны все; снятые остаются в корзине)
  isSelected: (id: string) => boolean;
  toggleSelected: (id: string) => void;
  selectedItems: CartItem[];
  selectedCount: number;
  selectedSubtotal: number;
  removeSelected: () => void;
  open: () => void;
  close: () => void;
}

const CartContext = createContext<CartContextValue | null>(null);
// v2 сбрасывает старые позиции без обязательного Medusa variant_id.
const STORAGE_KEY = "inkar-cart-v2";

export function CartProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<CartItem[]>([]);
  const [unselected, setUnselected] = useState<string[]>([]);
  const [isOpen, setIsOpen] = useState(false);
  const [hydrated, setHydrated] = useState(false);
  const syncQueueRef = useRef<Promise<void>>(Promise.resolve());

  // Hydrate from localStorage after mount (keeps SSR markup === first client render).
  useEffect(() => {
    /* eslint-disable react-hooks/set-state-in-effect -- разовая гидратация корзины из localStorage после маунта */
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (raw) setItems(JSON.parse(raw));
    } catch {
      /* ignore */
    }
    setHydrated(true);
    /* eslint-enable react-hooks/set-state-in-effect */
  }, []);

  useEffect(() => {
    if (!hydrated) return;
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(items));
    } catch {
      /* ignore */
    }
    const syncItems = items
      .filter((item) => item.product.variantId)
      .map((item) => ({ variantId: item.product.variantId, quantity: item.qty }));
    const timer = window.setTimeout(() => {
      syncQueueRef.current = syncQueueRef.current
        .catch(() => { /* keep the queue usable after a failed request */ })
        .then(async () => {
          await fetch("/api/cart", {
            method: "POST",
            headers: { "content-type": "application/json" },
            body: JSON.stringify({ action: "sync", items: syncItems }),
          });
        })
        .catch(() => { /* UI остаётся оптимистичным; backend повторно синхронизируется при следующем изменении. */ });
    }, 250);
    return () => window.clearTimeout(timer);
  }, [items, hydrated]);

  const add = useCallback((product: Product, qty = 1) => {
    emitCartItemAdded({ productId: product.id, variantId: product.variantId }, qty);
    setItems((prev) => {
      const idx = prev.findIndex((it) => it.product.id === product.id);
      if (idx >= 0) {
        const next = [...prev];
        next[idx] = { ...next[idx], qty: next[idx].qty + qty };
        return next;
      }
      return [...prev, { product, qty }];
    });
    setIsOpen(true);
  }, []);

  const remove = useCallback((id: string) => {
    const current = items.find((item) => item.product.id === id);
    if (current) {
      emitCartItemRemoved(
        { productId: current.product.id, variantId: current.product.variantId },
        current.qty,
      );
    }
    setItems((prev) => prev.filter((it) => it.product.id !== id));
    setUnselected((prev) => prev.filter((x) => x !== id));
  }, [items]);

  const restore = useCallback((product: Product, qty = 1) => {
    const restoredQty = Math.max(1, qty);
    emitCartItemAdded(
      { productId: product.id, variantId: product.variantId },
      restoredQty,
    );
    setItems((prev) => {
      const current = prev.find((item) => item.product.id === product.id);
      if (!current) return [...prev, { product, qty: restoredQty }];
      return prev.map((item) => (
        item.product.id === product.id
          ? { ...item, qty: item.qty + restoredQty }
          : item
      ));
    });
    setUnselected((prev) => prev.filter((id) => id !== product.id));
  }, []);

  const setQty = useCallback((id: string, qty: number) => {
    const current = items.find((item) => item.product.id === id);
    if (current && qty > current.qty) {
      emitCartItemAdded(
        { productId: current.product.id, variantId: current.product.variantId },
        qty - current.qty,
        "quantity_increase",
      );
    } else if (current && qty < current.qty) {
      emitCartItemRemoved(
        { productId: current.product.id, variantId: current.product.variantId },
        current.qty - Math.max(0, qty),
        "quantity_decrease",
      );
    }
    if (qty <= 0) setUnselected((prev) => prev.filter((x) => x !== id));
    setItems((prev) =>
      qty <= 0
        ? prev.filter((it) => it.product.id !== id)
        : prev.map((it) => (it.product.id === id ? { ...it, qty } : it)),
    );
  }, [items]);

  const clear = useCallback(() => {
    emitCartItemsCleared(items.map(({ product, qty }) => ({
      productId: product.id,
      variantId: product.variantId,
      quantity: qty,
    })));
    setItems([]);
    setUnselected([]);
  }, [items]);

  const toggleSelected = useCallback((id: string) => {
    setUnselected((prev) => (prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]));
  }, []);

  // Удалить оформленные (выбранные) позиции, оставив невыбранные в корзине.
  const removeSelected = useCallback(() => {
    setItems((prev) => prev.filter((it) => unselected.includes(it.product.id)));
    setUnselected([]);
  }, [unselected]);

  const value = useMemo<CartContextValue>(() => {
    const count = items.reduce((s, it) => s + it.qty, 0);
    const subtotal = items.reduce((s, it) => s + it.product.price * it.qty, 0);
    const savings = items.reduce(
      (s, it) => s + (it.product.oldPrice ? (it.product.oldPrice - it.product.price) * it.qty : 0),
      0,
    );
    const selectedItems = items.filter((it) => !unselected.includes(it.product.id));
    const selectedCount = selectedItems.reduce((s, it) => s + it.qty, 0);
    const selectedSubtotal = selectedItems.reduce((s, it) => s + it.product.price * it.qty, 0);
    return {
      items, count, subtotal, savings, isOpen,
      add, restore, remove, setQty, clear,
      isSelected: (id: string) => !unselected.includes(id),
      toggleSelected, selectedItems, selectedCount, selectedSubtotal, removeSelected,
      open: () => setIsOpen(true),
      close: () => setIsOpen(false),
    };
  }, [items, unselected, isOpen, add, restore, remove, setQty, clear, toggleSelected, removeSelected]);

  return <CartContext.Provider value={value}>{children}</CartContext.Provider>;
}

export function useCart() {
  const ctx = useContext(CartContext);
  if (!ctx) throw new Error("useCart must be used within <CartProvider>");
  return ctx;
}
