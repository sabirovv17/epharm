"use client";

import { createContext, useContext, useEffect, useState, useCallback, type ReactNode } from "react";
import { dict, type Lang } from "./dict";
import { plural as ruPlural } from "@/lib/format";

interface LanguageContextValue {
  lang: Lang;
  setLang: (l: Lang) => void;
  t: (key: string) => string;
  plural: (n: number, kind?: "products" | "reviews" | "pharmacies") => string;
}

const LanguageContext = createContext<LanguageContextValue | null>(null);
const KEY = "inkar-lang-v1";

export function LanguageProvider({ children }: { children: ReactNode }) {
  const [lang, setLangState] = useState<Lang>("ru");

  useEffect(() => {
    try {
      const saved = localStorage.getItem(KEY) as Lang | null;
      // eslint-disable-next-line react-hooks/set-state-in-effect -- гидратация языка из localStorage после маунта
      if (saved && dict[saved]) setLangState(saved);
    } catch {
      /* ignore */
    }
  }, []);

  const setLang = useCallback((l: Lang) => {
    setLangState(l);
    try { localStorage.setItem(KEY, l); } catch { /* ignore */ }
    if (typeof document !== "undefined") document.documentElement.lang = l;
  }, []);

  const t = useCallback((key: string) => dict[lang][key] ?? dict.ru[key] ?? key, [lang]);

  const plural = useCallback(
    (n: number, kind: "products" | "reviews" | "pharmacies" = "products") => {
      if (lang === "ru") {
        if (kind === "reviews") return ruPlural(n, "отзыв", "отзыва", "отзывов");
        if (kind === "pharmacies") return ruPlural(n, "аптека", "аптеки", "аптек");
        return ruPlural(n, "товар", "товара", "товаров");
      }
      if (lang === "kz") return kind === "reviews" ? "пікір" : kind === "pharmacies" ? "дәріхана" : "тауар";
      if (kind === "reviews") return n === 1 ? "review" : "reviews";
      if (kind === "pharmacies") return n === 1 ? "pharmacy" : "pharmacies";
      return n === 1 ? "item" : "items";
    },
    [lang],
  );

  return <LanguageContext.Provider value={{ lang, setLang, t, plural }}>{children}</LanguageContext.Provider>;
}

export function useLang() {
  const ctx = useContext(LanguageContext);
  if (!ctx) throw new Error("useLang must be used within <LanguageProvider>");
  return ctx;
}
