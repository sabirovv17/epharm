"use client";

import Link from "next/link";
import Image from "next/image";
import { useLang } from "@/lib/i18n/LanguageContext";
import { useContent } from "@/lib/content/ContentContext";
import { cmsTr } from "@/lib/content/cmsI18n";
import { CollectionIcon } from "@/components/ui/CollectionIcon";

export function ProblemCollections() {
  const { t, lang } = useLang();
  const { content } = useContent();
  const items = content.collections;
  if (!items.length) return null;

  return (
    <section className="mx-auto max-w-7xl px-4 sm:px-6">
      <h2 className="mb-6 font-display text-2xl font-bold tracking-tight text-slate-900 sm:text-3xl">{t("prob.title")}</h2>
      <div className="flex gap-3 overflow-x-auto no-scrollbar pb-1 sm:grid sm:grid-cols-4 sm:gap-4 sm:overflow-visible lg:grid-cols-7">
        {items.map((it) => (
          <Link
            key={it.id}
            href={it.href}
            className="group relative flex aspect-[4/5] min-w-[44%] flex-shrink-0 flex-col overflow-hidden rounded-2xl p-4 text-white shadow-soft transition-all duration-300 hover:-translate-y-1 hover:shadow-card sm:min-w-0"
            style={!it.img ? { background: `linear-gradient(150deg, ${it.from}, ${it.to})` } : undefined}
          >
            {it.img && (
              <>
                <Image src={it.img} alt="" fill sizes="(min-width: 1024px) 180px, (min-width: 640px) 25vw, 44vw" className="object-cover transition-transform duration-500 group-hover:scale-105" />
                <span
                  className="pointer-events-none absolute inset-0"
                  style={{ background: `linear-gradient(180deg, ${it.from}f2 0%, ${it.from}a6 45%, ${it.to}e6 100%)` }}
                />
              </>
            )}
            <span className="relative z-10 max-w-[88%] font-display text-sm font-extrabold leading-tight sm:text-[15px]">{cmsTr(lang, it.id, "title", it.title)}</span>
            <span className="pointer-events-none absolute right-2.5 top-2.5 z-10 text-base leading-none text-white/55">+</span>
            <span className="pointer-events-none absolute -bottom-2 -right-1 z-10 text-white/75 transition-transform duration-300 group-hover:scale-110">
              <CollectionIcon name={it.icon} className="h-14 w-14 sm:h-16 sm:w-16" />
            </span>
          </Link>
        ))}
      </div>
    </section>
  );
}
