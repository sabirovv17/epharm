"use client";

import { useMemo } from "react";
import { useSearchParams } from "next/navigation";
import { useCatalogData } from "@/lib/content/CatalogData";
import { CatalogView } from "@/components/catalog/CatalogView";
import { Breadcrumbs } from "@/components/ui/Breadcrumbs";
import { useLang } from "@/lib/i18n/LanguageContext";

export function SearchResults() {
  const sp = useSearchParams();
  const q = (sp.get("q") || "").trim();
  const { t } = useLang();
  const { products: catalogue } = useCatalogData();
  const localResults = useMemo(() => {
    const normalized = q.toLowerCase();
    return normalized
      ? catalogue.filter((product) => `${product.name} ${product.brand}`.toLowerCase().includes(normalized)).slice(0, 40)
      : [];
  }, [catalogue, q]);

  if (!q) {
    return (
      <div className="mx-auto max-w-7xl px-4 py-6 sm:px-6">
        <Breadcrumbs items={[{ label: t("common.home"), href: "/" }, { label: t("search.title") }]} />
        <h1 className="mt-4 font-display text-3xl font-extrabold tracking-tight text-slate-900">{t("search.title")}</h1>
        <p className="mt-1 text-slate-500">{t("search.hint")}</p>
      </div>
    );
  }

  return (
    <CatalogView
      key={q}
      products={localResults}
      tree={[]}
      totalCount={localResults.length}
      searchQuery={q}
      heading={`${t("search.results")} «${q}»`}
      crumbs={[{ label: t("common.home"), href: "/" }, { label: t("search.title") }]}
    />
  );
}
