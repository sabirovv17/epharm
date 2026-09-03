"use client";

import { useState, useRef, useEffect } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { Search, TrendingUp } from "lucide-react";
import { useCatalogData } from "@/lib/content/CatalogData";
import { useLang } from "@/lib/i18n/LanguageContext";
import { tenge } from "@/lib/format";
import { cn } from "@/lib/cn";

/** Умный поиск с выпадашкой: «Часто ищут» (реальные бренды) + автодополнение по живому каталогу. */
export function SearchBar({ className }: { className?: string }) {
  const router = useRouter();
  const { products, brands } = useCatalogData();
  const { t } = useLang();
  const [q, setQ] = useState("");
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onDoc = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", onDoc);
    return () => document.removeEventListener("mousedown", onDoc);
  }, [open]);

  const term = q.trim().toLowerCase();
  const popular = brands.slice(0, 6).map((b) => b.name);
  const matches = term
    ? products.filter((p) => `${p.name} ${p.brand}`.toLowerCase().includes(term)).slice(0, 6)
    : [];

  const go = (text: string) => {
    const v = text.trim();
    if (!v) return;
    setOpen(false);
    router.push(`/search?q=${encodeURIComponent(v)}`);
  };

  return (
    <div ref={ref} className={cn("relative", className)}>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          go(q);
        }}
      >
        <div className={cn("flex items-center rounded-full border-2 bg-white pl-5 transition", open ? "border-brand-500 shadow-pop" : "border-brand-200")}>
          <input
            value={q}
            onChange={(e) => setQ(e.target.value)}
            onFocus={() => setOpen(true)}
            placeholder={t("search.ph")}
            className="h-12 flex-1 bg-transparent text-[15px] text-slate-800 outline-none placeholder:text-slate-400"
          />
          <button type="submit" aria-label={t("search.btn")} className="m-1 grid h-10 w-12 shrink-0 place-items-center rounded-full bg-brand-600 text-white transition hover:bg-brand-700">
            <Search className="h-5 w-5" />
          </button>
        </div>
      </form>

      {open && (
        <div className="absolute inset-x-0 top-[calc(100%+8px)] z-50 overflow-hidden rounded-2xl border border-slate-100 bg-white p-2 shadow-pop">
          {term ? (
            matches.length > 0 ? (
              <ul>
                {matches.map((p) => (
                  <li key={p.id}>
                    <Link
                      href={`/product/${p.slug}`}
                      onClick={() => setOpen(false)}
                      className="flex items-center gap-3 rounded-xl px-3 py-2.5 transition hover:bg-slate-50"
                    >
                      <Search className="h-4 w-4 shrink-0 text-slate-400" />
                      <span className="min-w-0 flex-1">
                        <span className="block truncate text-sm text-slate-800">{p.name}</span>
                        <span className="text-xs text-slate-400">{p.brand}</span>
                      </span>
                      {!p.priceTBD && <span className="shrink-0 text-sm font-semibold text-slate-900">{tenge(p.price)}</span>}
                    </Link>
                  </li>
                ))}
                <li>
                  <button onClick={() => go(q)} className="mt-1 flex w-full items-center gap-2 rounded-xl px-3 py-2.5 text-sm font-medium text-brand-700 transition hover:bg-brand-50">
                    <Search className="h-4 w-4" /> {t("search.btn")} «{q.trim()}»
                  </button>
                </li>
              </ul>
            ) : (
              <p className="px-3 py-4 text-sm text-slate-400">{t("search.noHint")}</p>
            )
          ) : popular.length > 0 ? (
            <>
              <p className="px-3 py-2 text-sm font-bold text-slate-900">{t("search.popular")}</p>
              <ul>
                {popular.map((name) => (
                  <li key={name}>
                    <button onClick={() => go(name)} className="flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left transition hover:bg-slate-50">
                      <TrendingUp className="h-4 w-4 shrink-0 text-slate-400" />
                      <span className="text-sm text-slate-700">{name}</span>
                    </button>
                  </li>
                ))}
              </ul>
            </>
          ) : (
            <p className="px-3 py-4 text-sm text-slate-400">{t("search.hint")}</p>
          )}
        </div>
      )}
    </div>
  );
}
