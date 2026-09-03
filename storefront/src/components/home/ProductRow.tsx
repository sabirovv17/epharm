"use client";

import { useRef } from "react";
import Link from "next/link";
import { ChevronLeft, ChevronRight } from "lucide-react";
import { ProductCard } from "@/components/product/ProductCard";
import { useLang } from "@/lib/i18n/LanguageContext";
import type { Product } from "@/lib/types";

export function ProductRow({ titleKey, subtitleKey, products }: { titleKey: string; subtitleKey?: string; products: Product[] }) {
  const ref = useRef<HTMLDivElement>(null);
  const { t } = useLang();
  const scroll = (dir: number) => ref.current?.scrollBy({ left: dir * 480, behavior: "smooth" });

  // Никаких пустых каруселей: если бэкенд не отдал товары — секцию не показываем.
  if (!products.length) return null;

  return (
    <section className="mx-auto max-w-7xl px-4 sm:px-6">
      <div className="mb-6 flex items-end justify-between gap-4">
        <div>
          <h2 className="font-display text-2xl font-bold tracking-tight text-slate-900 sm:text-3xl">{t(titleKey)}</h2>
          {subtitleKey && <p className="mt-1.5 text-slate-500">{t(subtitleKey)}</p>}
        </div>
        <div className="flex items-center gap-3">
          <Link href="/catalog" className="hidden shrink-0 text-sm font-semibold text-slate-900 underline-offset-4 transition hover:underline sm:block">
            {t("common.viewAll")}
          </Link>
          <div className="hidden gap-1.5 md:flex">
            <button onClick={() => scroll(-1)} aria-label="←" className="grid h-10 w-10 place-items-center rounded-full border border-slate-300 text-slate-700 transition hover:border-slate-900 hover:text-slate-900">
              <ChevronLeft className="h-5 w-5" />
            </button>
            <button onClick={() => scroll(1)} aria-label="→" className="grid h-10 w-10 place-items-center rounded-full border border-slate-300 text-slate-700 transition hover:border-slate-900 hover:text-slate-900">
              <ChevronRight className="h-5 w-5" />
            </button>
          </div>
        </div>
      </div>

      <div ref={ref} className="no-scrollbar -mx-4 flex snap-x gap-4 overflow-x-auto px-4 pb-2 sm:mx-0 sm:px-0">
        {products.map((p) => (
          <div key={p.id} className="w-[220px] shrink-0 snap-start sm:w-[240px]">
            <ProductCard product={p} boxed />
          </div>
        ))}
      </div>
    </section>
  );
}
