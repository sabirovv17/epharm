"use client";

import Link from "next/link";
import type { Brand } from "@/lib/types";
import { SectionHeader } from "./SectionHeader";
import { useLang } from "@/lib/i18n/LanguageContext";

export function BrandStrip({ brands }: { brands: Brand[] }) {
  const { t } = useLang();
  const top = brands.slice(0, 8);
  if (!top.length) return null;
  return (
    <section className="mx-auto max-w-7xl px-4 sm:px-6">
      <SectionHeader title={t("home.brands.title")} subtitle={t("home.brands.sub")} href="/brands" />
      <div className="grid grid-cols-2 gap-3 sm:gap-4 md:grid-cols-4">
        {top.map((brand) => (
          <Link
            key={brand.id}
            href={`/brands/${brand.slug}`}
            className="group flex h-32 flex-col items-center justify-center rounded-2xl border border-slate-100 bg-white px-3 text-center transition-all duration-300 hover:-translate-y-1 hover:border-transparent hover:shadow-card"
          >
            <span className="line-clamp-2 font-display text-lg font-extrabold leading-tight tracking-tight transition group-hover:scale-105" style={{ color: `hsl(${brand.hue} 48% 42%)` }}>
              {brand.name}
            </span>
            <span className="mt-1.5 text-xs text-slate-400">{brand.tagline}</span>
          </Link>
        ))}
      </div>
    </section>
  );
}
