"use client";

import {
  createContext, useContext, useEffect, useMemo, useState, useCallback, type ReactNode,
} from "react";
import { phoneDigits, formatPhone } from "@/lib/phone";

export type Level = "Bronze" | "Silver" | "Gold" | "Platinum";
export type AuthMode = "loading" | "sms" | "demo";

export interface User {
  name: string;
  phone: string;
  email?: string;
  bonus: number;
  level: Level;
  demo?: boolean;
}

interface AuthContextValue {
  user: User | null;
  authMode: AuthMode;
  isModalOpen: boolean;
  openLogin: () => void;
  closeLogin: () => void;
  /** Passwordless вход/регистрация после подтверждения номера SMS-кодом (реальный customer Медузы).
   *  Возвращает текст ошибки или null при успехе. */
  continueWithPhone: (name: string, contact: string, code: string) => Promise<string | null>;
  continueAsDemo: (name: string) => Promise<string | null>;
  logout: () => void;
  updateProfile: (patch: Partial<Pick<User, "name" | "email">>) => void;
  addresses: string[];
  addAddress: (a: string) => void;
  removeAddress: (i: number) => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);
const USER_KEY = "inkar-user-v1"; // только оптимистичный кэш для мгновенной отрисовки; истина — на сервере
const ADDR_KEY = "inkar-addr-v1";

/** DTO сервера (/api/customer) → доменный User (телефон форматируем для показа). */
function toUser(d: unknown): User | null {
  if (!d || typeof d !== "object") return null;
  const o = d as Record<string, unknown>;
  const phone = typeof o.phone === "string" && o.phone ? formatPhone(o.phone) : "";
  return {
    name: String(o.name || "Гость"),
    phone,
    email: typeof o.email === "string" && o.email ? o.email : undefined,
    bonus: Number(o.bonus) || 0,
    level: (["Bronze", "Silver", "Gold", "Platinum"].includes(String(o.level)) ? o.level : "Bronze") as Level,
    demo: o.demo === true,
  };
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [addresses, setAddresses] = useState<string[]>([]);
  const [addressIds, setAddressIds] = useState<string[]>([]);
  const [isModalOpen, setOpen] = useState(false);
  const [authMode, setAuthMode] = useState<AuthMode>("loading");
  const [hydrated, setHydrated] = useState(false);

  // Оптимистичная отрисовка из кэша, затем сверка с сервером (актуальные баллы/уровень или разлогин).
  useEffect(() => {
    /* eslint-disable react-hooks/set-state-in-effect -- разовая гидратация после маунта */
    try {
      const u = localStorage.getItem(USER_KEY);
      if (u) setUser(JSON.parse(u));
      const a = localStorage.getItem(ADDR_KEY);
      if (a) setAddresses(JSON.parse(a));
    } catch { /* ignore */ }
    setHydrated(true);
    /* eslint-enable react-hooks/set-state-in-effect */
    (async () => {
      try {
        const r = await fetch("/api/customer", { cache: "no-store" });
        const m = await r.json().catch(() => ({}));
        setAuthMode(m?.authMode === "demo" ? "demo" : "sms");
        if (m?.transient) return; // бэк недоступен — оставляем кэш, не разлогиниваем
        setUser(toUser(m?.user));
      } catch {
        // Серверный default — sms. При офлайне вход всё равно недоступен, но UI не зависает в loading.
        setAuthMode("sms");
      }
    })();
    fetch("/api/customer/addresses", { cache: "no-store" })
      .then((r) => r.json())
      .then((data) => {
        const list = Array.isArray(data?.addresses) ? data.addresses : [];
        setAddresses(list.map((item: { label?: string }) => String(item.label || "")).filter(Boolean));
        setAddressIds(list.map((item: { id?: string }) => String(item.id || "")));
      })
      .catch(() => { /* офлайн — локальный кэш остаётся */ });
  }, []);

  // Кэшируем профиль для мгновенной отрисовки при следующем заходе.
  useEffect(() => {
    if (!hydrated) return;
    try {
      if (user) localStorage.setItem(USER_KEY, JSON.stringify(user));
      else localStorage.removeItem(USER_KEY);
    } catch { /* ignore */ }
  }, [user, hydrated]);

  useEffect(() => {
    if (hydrated) {
      try { localStorage.setItem(ADDR_KEY, JSON.stringify(addresses)); } catch { /* ignore */ }
    }
  }, [addresses, hydrated]);

  // Вход/регистрация по подтверждённому SMS-номеру. Код проверяется на сервере (/api/customer),
  // там же get-or-create реального customer Медузы; токен — в httpOnly-cookie.
  const continueWithPhone = useCallback(
    async (name: string, contact: string, code: string): Promise<string | null> => {
      const digits = phoneDigits(contact);
      if (digits.length !== 11) return "Введите корректный номер телефона";
      try {
        const r = await fetch("/api/customer", {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ action: "continue", phone: digits, code, name }),
        });
        const m = await r.json().catch(() => ({}));
        if (r.ok && m?.user) {
          setUser(toUser(m.user));
          setOpen(false);
          return null;
        }
        if (m?.error === "bad_code") return "Неверный код. Попробуйте ещё раз.";
        if (m?.error === "bad_phone") return "Введите корректный номер телефона";
        return "Сервис авторизации недоступен. Попробуйте позже.";
      } catch {
        return "Сеть недоступна.";
      }
    },
    [],
  );

  const continueAsDemo = useCallback(async (name: string): Promise<string | null> => {
    try {
      const r = await fetch("/api/customer", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ action: "demo", name }),
      });
      const m = await r.json().catch(() => ({}));
      if (r.ok && m?.user) {
        setUser(toUser(m.user));
        setAuthMode("demo");
        setOpen(false);
        return null;
      }
      if (m?.error === "too_many_requests") return "Слишком много попыток. Попробуйте позже.";
      if (m?.error === "demo_disabled") return "Демо-режим отключён на сервере.";
      return "Не удалось открыть демо-режим. Попробуйте ещё раз.";
    } catch {
      return "Сеть недоступна.";
    }
  }, []);

  const logout = useCallback(() => {
    setUser(null);
    setAddresses([]);
    setAddressIds([]);
    fetch("/api/customer", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ action: "logout" }),
    }).catch(() => {});
  }, []);

  const updateProfile = useCallback(
    (patch: Partial<Pick<User, "name" | "email">>) => {
      setUser((prev) => (prev ? { ...prev, ...patch } : prev)); // оптимистично
      fetch("/api/customer", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ action: "update", ...patch }),
      }).catch(() => {});
    },
    [],
  );

  const addAddress = useCallback((a: string) => {
    setAddresses((prev) => [...prev, a]);
    fetch("/api/customer/addresses", {
      method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ address: a }),
    }).then((r) => r.json()).then((data) => {
      const list = Array.isArray(data?.addresses) ? data.addresses : [];
      if (list.length) {
        setAddresses(list.map((item: { label?: string }) => String(item.label || "")).filter(Boolean));
        setAddressIds(list.map((item: { id?: string }) => String(item.id || "")));
      }
    }).catch(() => {});
  }, []);
  const removeAddress = useCallback((i: number) => {
    const id = addressIds[i];
    setAddresses((prev) => prev.filter((_, idx) => idx !== i));
    setAddressIds((prev) => prev.filter((_, idx) => idx !== i));
    if (id) fetch(`/api/customer/addresses?id=${encodeURIComponent(id)}`, { method: "DELETE" }).catch(() => {});
  }, [addressIds]);

  const value = useMemo<AuthContextValue>(
    () => ({
      user, authMode, isModalOpen,
      openLogin: () => setOpen(true),
      closeLogin: () => setOpen(false),
      continueWithPhone, continueAsDemo, logout, updateProfile,
      addresses, addAddress, removeAddress,
    }),
    [user, authMode, isModalOpen, continueWithPhone, continueAsDemo, logout, updateProfile, addresses, addAddress, removeAddress],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used within <AuthProvider>");
  return ctx;
}
