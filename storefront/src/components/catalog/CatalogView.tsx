"use client";

import { Fragment, useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import Image from "next/image";
import { SlidersHorizontal, X, Check, ChevronDown, ArrowRight } from "lucide-react";
import type { CatNode, Product } from "@/lib/types";
import type { CatalogFacets, PrescriptionFilter } from "@/lib/catalog-query";
import { catalogPromos, type Promo } from "@/lib/data/promos";
import { ProductCard } from "@/components/product/ProductCard";
import { Breadcrumbs } from "@/components/ui/Breadcrumbs";
import { tenge } from "@/lib/format";
import { useLang } from "@/lib/i18n/LanguageContext";
import { cn } from "@/lib/cn";

type Sort = "popular" | "price-asc" | "price-desc" | "rating" | "new";
const sortValues: Sort[] = ["popular", "price-asc", "price-desc"];
const CATALOG_PAGE_SIZE = 24;
const loadCopy = {
  ru: { more: "Показать ещё", loading: "Загружаем…", error: "Не удалось загрузить товары.", retry: "Повторить" },
  kz: { more: "Тағы көрсету", loading: "Жүктелуде…", error: "Тауарларды жүктеу мүмкін болмады.", retry: "Қайталау" },
  en: { more: "Show more", loading: "Loading…", error: "Could not load products.", retry: "Try again" },
} as const;

type CatalogPageResponse = {
  products?: unknown;
  count?: unknown;
  hasMore?: unknown;
  nextOffset?: unknown;
  facets?: unknown;
};

type CatalogRequest = {
  q: string;
  category?: string;
  brands: string[];
  minPrice: number | null;
  maxPrice: number | null;
  inStock: boolean;
  sale: boolean;
  prescription: PrescriptionFilter;
  sort: Sort;
};

function serverSort(sort: Sort): string {
  if (sort === "price-asc") return "price_asc";
  if (sort === "price-desc") return "price_desc";
  return "relevance";
}

function catalogRequestParams(request: CatalogRequest, offset: number): URLSearchParams {
  const params = new URLSearchParams({
    limit: String(CATALOG_PAGE_SIZE),
    offset: String(offset),
    prescription: request.prescription,
    sort: serverSort(request.sort),
  });
  if (request.q) params.set("q", request.q);
  if (request.category) params.set("category", request.category);
  for (const brand of request.brands) params.append("brand", brand);
  if (request.minPrice != null) params.set("price_min", String(request.minPrice));
  if (request.maxPrice != null) params.set("price_max", String(request.maxPrice));
  if (request.inStock) params.set("in_stock", "1");
  if (request.sale) params.set("sale", "1");
  return params;
}

function isCatalogFacets(value: unknown): value is CatalogFacets {
  if (!value || typeof value !== "object") return false;
  const candidate = value as Partial<CatalogFacets>;
  return Array.isArray(candidate.brands)
    && Array.isArray(candidate.categories)
    && Boolean(candidate.price && typeof candidate.price === "object")
    && Boolean(candidate.availability && typeof candidate.availability === "object");
}

function isProduct(value: unknown): value is Product {
  if (!value || typeof value !== "object") return false;
  const candidate = value as Partial<Product>;
  return typeof candidate.id === "string" && typeof candidate.slug === "string" && typeof candidate.name === "string";
}

function uniqueProducts(primary: Product[], secondary: Product[] = []): Product[] {
  const seen = new Set<string>();
  return [...primary, ...secondary].filter((product) => {
    const key = product.id || product.slug;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

/** Путь от корня дерева до узла с данным handle (для крошек и авто-раскрытия). */
function findPath(nodes: CatNode[], handle: string): CatNode[] | null {
  for (const n of nodes) {
    if (n.handle === handle) return [n];
    const sub = findPath(n.children, handle);
    if (sub) return [n, ...sub];
  }
  return null;
}

/** Нормализованный ключ бренда: «NOW Foods»/«Now Foods» → один (регистр и пробелы). */
function brandKey(b: string): string {
  return b.trim().toLowerCase().replace(/[^a-zа-я0-9]+/gi, "-").replace(/^-+|-+$/g, "").slice(0, 48) || "brand";
}

export function CatalogView({
  products,
  tree,
  activeHandle,
  totalCount,
  heading,
  crumbs,
  searchQuery = "",
}: {
  products: Product[];
  tree: CatNode[];
  activeHandle?: string;
  totalCount?: number;
  heading?: string;
  crumbs?: { label: string; href?: string }[];
  searchQuery?: string;
}) {
  const { t, plural, lang } = useLang();
  const loadText = loadCopy[lang];
  const [pageProducts, setPageProducts] = useState<Product[]>(products);
  const [additionalProducts, setAdditionalProducts] = useState<Product[]>([]);
  const [knownTotalCount, setKnownTotalCount] = useState(totalCount);
  const [nextOffset, setNextOffset] = useState(products.length);
  const [hasMore, setHasMore] = useState(totalCount != null && products.length < totalCount);
  const [loadingMore, setLoadingMore] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [loadError, setLoadError] = useState(false);
  const [facets, setFacets] = useState<CatalogFacets | null>(null);
  const [availableBrands, setAvailableBrands] = useState<CatalogFacets["brands"]>([]);
  const [availablePriceBounds, setAvailablePriceBounds] = useState<{ min: number; max: number } | null>(null);
  const activeRequestRef = useRef("");
  const loadedProducts = useMemo(() => uniqueProducts(pageProducts, additionalProducts), [pageProducts, additionalProducts]);
  // Реальное дерево Medusa: путь к активной категории + множество раскрытых узлов.
  const path = useMemo(() => (activeHandle ? findPath(tree, activeHandle) : null), [tree, activeHandle]);
  const activeName = heading ?? path?.[path.length - 1]?.name;
  const openSet = useMemo(() => new Set((path ?? []).map((n) => n.handle)), [path]);

  const fallbackBrands = useMemo(() => {
    const seen = new Map<string, string>(); // нормализованный ключ → первое встретившееся написание
    for (const p of loadedProducts) {
      const orig = (p.brand || "").trim();
      if (orig && !seen.has(brandKey(orig))) seen.set(brandKey(orig), orig);
    }
    return [...seen.entries()]
      .map(([key, name]) => ({ key, name, count: 0 }))
      .sort((a, b) => a.name.localeCompare(b.name, "ru"));
  }, [loadedProducts]);
  const fallbackBounds = useMemo(() => {
    const prices = loadedProducts.map((p) => p.price).filter((n) => n > 0); // priceTBD → price=0; без фильтра max=0 → ползунок «0 ₸ до 0 ₸»
    return { min: prices.length ? Math.min(...prices) : 0, max: prices.length ? Math.max(...prices) : 50000 };
  }, [loadedProducts]);
  const brandOptions = availableBrands.length > 0 ? availableBrands : fallbackBrands;
  const bounds = availablePriceBounds ?? fallbackBounds;

  const [selBrands, setSelBrands] = useState<Set<string>>(new Set());
  const [priceFloor, setPriceFloor] = useState<number | null>(null);
  const [priceCap, setPriceCap] = useState<number | null>(null);
  const priceMax = Math.max(bounds.min, Math.min(priceCap ?? bounds.max, bounds.max));
  const priceMin = Math.min(priceMax, Math.max(bounds.min, priceFloor ?? bounds.min));
  const [onlySale, setOnlySale] = useState(false);
  const [onlyStock, setOnlyStock] = useState(false);
  const [prescription, setPrescription] = useState<PrescriptionFilter>("all");
  const [sort, setSort] = useState<Sort>("popular");
  const [filtersOpen, setFiltersOpen] = useState(false);

  const activeFilterCount = selBrands.size + Number(priceFloor != null || priceCap != null) + Number(onlySale) + Number(onlyStock) + Number(prescription !== "all");
  const requestKey = useMemo(() => JSON.stringify({
    q: searchQuery.trim(),
    category: activeHandle,
    brands: [...selBrands].sort(),
    minPrice: priceFloor == null ? null : priceMin,
    maxPrice: priceCap == null ? null : priceMax,
    inStock: onlyStock,
    sale: onlySale,
    prescription,
    sort,
  } satisfies CatalogRequest), [activeHandle, onlySale, onlyStock, prescription, priceCap, priceFloor, priceMax, priceMin, searchQuery, selBrands, sort]);
  const request = useMemo(() => JSON.parse(requestKey) as CatalogRequest, [requestKey]);

  useEffect(() => {
    if (!filtersOpen) return;
    const previousOverflow = document.body.style.overflow;
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape") setFiltersOpen(false);
    };
    document.body.style.overflow = "hidden";
    window.addEventListener("keydown", closeOnEscape);
    return () => {
      document.body.style.overflow = previousOverflow;
      window.removeEventListener("keydown", closeOnEscape);
    };
  }, [filtersOpen]);

  useEffect(() => {
    const controller = new AbortController();
    activeRequestRef.current = requestKey;
    const loadingFrame = window.requestAnimationFrame(() => {
      if (!controller.signal.aborted && activeRequestRef.current === requestKey) {
        setRefreshing(true);
        setLoadError(false);
      }
    });

    void (async () => {
      try {
        // Products are the blocking part of the interaction. Facets scan the
        // complete price/availability catalogue, so load them independently
        // instead of making the grid wait (or fail) behind that aggregation.
        const productParams = catalogRequestParams(request, 0);
        productParams.set("facets", "0");
        const response = await fetch(`/api/catalog?${productParams.toString()}`, {
          cache: "no-store",
          signal: controller.signal,
        });
        if (!response.ok) throw new Error(`catalog_page_${response.status}`);
        const payload = await response.json() as CatalogPageResponse;
        if (!Array.isArray(payload.products)) throw new Error("catalog_page_invalid");
        if (controller.signal.aborted || activeRequestRef.current !== requestKey) return;

        const nextProducts = payload.products.filter(isProduct);
        const responseCount = typeof payload.count === "number" && Number.isSafeInteger(payload.count) && payload.count >= 0
          ? payload.count
          : nextProducts.length;
        const responseNextOffset = typeof payload.nextOffset === "number" && Number.isSafeInteger(payload.nextOffset) && payload.nextOffset >= 0
          ? payload.nextOffset
          : nextProducts.length;
        const responseHasMore = typeof payload.hasMore === "boolean"
          ? payload.hasMore
          : responseNextOffset < responseCount;

        setPageProducts(nextProducts);
        setAdditionalProducts([]);
        setKnownTotalCount(responseCount);
        setNextOffset(responseNextOffset);
        setHasMore(responseHasMore);

        const facetParams = catalogRequestParams(request, 0);
        facetParams.set("limit", "1");
        facetParams.set("facets", "1");
        void fetch(`/api/catalog?${facetParams.toString()}`, {
          cache: "no-store",
          signal: controller.signal,
        }).then(async (facetResponse) => {
          if (!facetResponse.ok) return;
          const facetPayload = await facetResponse.json() as CatalogPageResponse;
          if (controller.signal.aborted || activeRequestRef.current !== requestKey
              || !isCatalogFacets(facetPayload.facets)) return;
          setFacets(facetPayload.facets);
          if (request.brands.length === 0) setAvailableBrands(facetPayload.facets.brands);
          if (request.minPrice == null && request.maxPrice == null
              && facetPayload.facets.price.min != null && facetPayload.facets.price.max != null) {
            setAvailablePriceBounds({ min: facetPayload.facets.price.min, max: facetPayload.facets.price.max });
          }
        }).catch((error: unknown) => {
          if (!(error instanceof DOMException && error.name === "AbortError")) {
            console.warn("Catalog facets are temporarily unavailable", error);
          }
        });
      } catch (error) {
        if (!(error instanceof DOMException && error.name === "AbortError")) setLoadError(true);
      } finally {
        if (!controller.signal.aborted && activeRequestRef.current === requestKey) setRefreshing(false);
      }
    })();

    return () => {
      window.cancelAnimationFrame(loadingFrame);
      controller.abort();
    };
  }, [request, requestKey]);

  const filtered = loadedProducts;

  const toggleBrand = (b: string) =>
    setSelBrands((prev) => {
      const n = new Set(prev);
      if (n.has(b)) n.delete(b); else n.add(b);
      return n;
    });
  const reset = () => { setSelBrands(new Set()); setPriceFloor(null); setPriceCap(null); setOnlySale(false); setOnlyStock(false); setPrescription("all"); };

  const loadMore = async () => {
    if (loadingMore || !hasMore) return;
    setLoadingMore(true);
    setLoadError(false);
    try {
      const expectedRequest = requestKey;
      const params = catalogRequestParams(request, nextOffset);
      // Filters were already populated by the first request. Load-more only
      // needs products and pagination; omitting facets avoids recomputing and
      // retransmitting the full brand/category aggregate on every page.
      params.set("facets", "0");
      const response = await fetch(`/api/catalog?${params.toString()}`, { cache: "no-store" });
      if (!response.ok) throw new Error(`catalog_page_${response.status}`);
      const payload = await response.json() as CatalogPageResponse;
      if (!Array.isArray(payload.products)) throw new Error("catalog_page_invalid");
      if (activeRequestRef.current !== expectedRequest) return;

      const page = payload.products.filter(isProduct);
      const fallbackOffset = nextOffset + page.length;
      const responseTotal = typeof payload.count === "number" && Number.isSafeInteger(payload.count) && payload.count >= 0
        ? payload.count
        : knownTotalCount;
      const responseNextOffset = typeof payload.nextOffset === "number" && Number.isSafeInteger(payload.nextOffset) && payload.nextOffset >= 0
        ? payload.nextOffset
        : fallbackOffset;
      const responseHasMore = page.length > 0 && (
        typeof payload.hasMore === "boolean"
          ? payload.hasMore
          : responseTotal != null
            ? responseNextOffset < responseTotal
            : page.length === CATALOG_PAGE_SIZE
      );

      setAdditionalProducts((current) => uniqueProducts(current, page));
      setKnownTotalCount(responseTotal);
      setNextOffset(responseNextOffset);
      setHasMore(responseHasMore);
    } catch {
      setLoadError(true);
    } finally {
      setLoadingMore(false);
    }
  };

  // Счётчик в шапке: реальный total категории (из Medusa) пока не активны фильтры, иначе — видимое число.
  const displayCount = knownTotalCount ?? filtered.length;
  const truncated = knownTotalCount != null && knownTotalCount > loadedProducts.length;

  const filters = (
    <div className="space-y-7">
      <FilterGroup title={t("drawer.categories")}>
        <ul className="space-y-0.5">
          <li>
            <Link href="/catalog" className={cn("flex min-h-11 items-center rounded-lg px-3 py-2 text-sm transition", !activeHandle ? "bg-slate-100 font-semibold text-slate-900" : "text-slate-600 hover:bg-slate-50")}>
              {t("catalog.all")}
            </Link>
          </li>
          {tree.map((n) => (
            <TreeNode key={n.id} node={n} activeHandle={activeHandle} openSet={openSet} depth={0} />
          ))}
        </ul>
      </FilterGroup>

      {brandOptions.length > 1 && (
        <FilterGroup title={t("catalog.brand")}>
          <div className="space-y-1">
            {brandOptions.map((brand) => (
              <Checkbox key={brand.key} checked={selBrands.has(brand.key)} onChange={() => toggleBrand(brand.key)} label={`${brand.name}${brand.count > 0 ? ` (${brand.count})` : ""}`} />
            ))}
          </div>
        </FilterGroup>
      )}

      <FilterGroup title={t("catalog.price")}>
        <div className="grid grid-cols-2 gap-2">
          <input type="number" min={bounds.min} max={priceMax} step={50} value={priceFloor ?? ""} placeholder={String(bounds.min)} onChange={(e) => setPriceFloor(e.target.value === "" ? null : Number(e.target.value))} aria-label="Минимальная цена" className="h-11 min-w-0 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-brand-400" />
          <input type="number" min={priceMin} max={bounds.max} step={50} value={priceCap ?? ""} placeholder={String(bounds.max)} onChange={(e) => setPriceCap(e.target.value === "" ? null : Number(e.target.value))} aria-label="Максимальная цена" className="h-11 min-w-0 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-brand-400" />
        </div>
        <input type="range" min={bounds.min} max={bounds.max} step={50} value={priceMax} onChange={(e) => setPriceCap(Number(e.target.value))} className="h-11 w-full accent-slate-900" />
        <div className="mt-1 flex justify-between text-sm text-slate-500">
          <span>{tenge(priceMin)}</span>
          <span className="font-semibold text-slate-800">{t("catalog.priceTo")} {tenge(priceMax)}</span>
        </div>
      </FilterGroup>

      <FilterGroup title={t("catalog.availability")}>
        <Checkbox checked={onlyStock} onChange={() => setOnlyStock((v) => !v)} label={`${t("catalog.inStock")}${facets ? ` (${facets.availability.inStock})` : ""}`} />
      </FilterGroup>

      <FilterGroup title={t("catalog.promos")}>
        <Checkbox checked={onlySale} onChange={() => setOnlySale((v) => !v)} label={t("catalog.onlySale")} />
      </FilterGroup>

      <FilterGroup title="Рецептурность">
        <select value={prescription} onChange={(event) => setPrescription(event.target.value as PrescriptionFilter)} className="h-11 w-full rounded-lg border border-slate-200 bg-white px-3 text-sm outline-none focus:border-brand-400">
          <option value="all">Все товары</option>
          <option value="rx">По рецепту</option>
          <option value="otc">Без рецепта</option>
        </select>
      </FilterGroup>

      <button onClick={reset} className="inline-flex min-h-11 items-center text-sm font-medium text-slate-500 transition hover:text-accent-600">{t("common.reset")}</button>
    </div>
  );

  return (
    <div className="mx-auto max-w-7xl px-4 py-6 sm:px-6">
      <Breadcrumbs
        items={crumbs ?? [
          { label: t("common.home"), href: "/" },
          { label: t("common.catalog"), href: "/catalog" },
          ...(path ?? []).map((n, i) => ({ label: n.name, href: i < (path!.length - 1) ? `/catalog/${n.handle}` : undefined })),
        ]}
      />

      <div className="mt-4 flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="font-display text-3xl font-extrabold tracking-tight text-slate-900">
            {activeName ?? t("catalog.title")}
          </h1>
          <p className="mt-1 text-slate-500">
            {displayCount} {plural(displayCount)}
            {refreshing && <span className="text-slate-400"> · {loadText.loading}</span>}
            {truncated && <span className="text-slate-400"> · {t("catalog.showingFirst")} {loadedProducts.length}</span>}
          </p>
        </div>
        <div className="grid w-full min-w-0 grid-cols-2 gap-2 sm:flex sm:w-auto sm:items-center">
          <button type="button" onClick={() => setFiltersOpen(true)} aria-haspopup="dialog" aria-expanded={filtersOpen} className="inline-flex h-11 min-w-0 items-center justify-center gap-2 rounded-xl border border-slate-200 px-3 text-sm font-medium text-slate-700 transition hover:border-brand-300 lg:hidden">
            <SlidersHorizontal className="h-4 w-4 shrink-0" />
            <span className="truncate">{t("catalog.filters")}</span>
            {activeFilterCount > 0 && (
              <span className="grid h-5 min-w-5 shrink-0 place-items-center rounded-full bg-brand-600 px-1 text-[11px] font-bold text-white" aria-label={`${t("catalog.filters")}: ${activeFilterCount}`}>
                {activeFilterCount}
              </span>
            )}
          </button>
          <SortSelect value={sort} onChange={setSort} options={sortValues.map((v) => ({ value: v, label: t(`sort.${v}`) }))} />
        </div>
      </div>

      <div className="mt-6 grid gap-6 lg:grid-cols-[260px_1fr]">
        <aside className="hidden lg:block">
          <div className="sticky top-28 max-h-[calc(100vh-8rem)] overflow-y-auto pr-1">{filters}</div>
        </aside>

        <div>
          {filtered.length === 0 ? (
            <div className="flex flex-col items-center justify-center rounded-2xl border border-dashed border-slate-200 py-20 text-center">
              <p className="font-semibold text-slate-800">{t("catalog.empty")}</p>
              <p className="mt-1 text-sm text-slate-500">{t("catalog.emptySub")}</p>
              <button onClick={reset} className="mt-4 text-sm font-semibold text-slate-900 underline-offset-4 hover:underline">{t("common.reset")}</button>
            </div>
          ) : (
            <div className="grid grid-flow-row-dense grid-cols-2 gap-3 sm:gap-4 md:grid-cols-3">
              {filtered.map((p, i) => {
                // Реклама вперемешку: чаще вертикальные вставки (размером с одну карточку товара), реже — горизонтальные (на 2 колонки).
                const showPromo = (i + 1) % 5 === 0 && filtered.length - (i + 1) >= 2;
                const slot = Math.floor(i / 5);
                const promo = showPromo ? catalogPromos[slot % catalogPromos.length] : null;
                const tall = slot % 4 !== 0; // 3 из 4 вставок вертикальные, 1 из 4 — горизонтальная
                return (
                  <Fragment key={p.id}>
                    <ProductCard product={p} />
                    {promo && <PromoCell promo={promo} tall={tall} />}
                  </Fragment>
                );
              })}
            </div>
          )}
          {(hasMore || loadError) && knownTotalCount != null && (
            <div className="mt-6 flex flex-col items-center gap-2" aria-live="polite">
              {loadError && <p role="alert" className="text-center text-sm text-rose-600">{loadText.error}</p>}
              <button
                type="button"
                onClick={loadMore}
                disabled={loadingMore || refreshing}
                className="inline-flex min-h-12 w-full items-center justify-center rounded-xl border border-brand-600 bg-white px-6 text-sm font-bold text-brand-700 transition hover:bg-brand-50 disabled:cursor-wait disabled:opacity-60 sm:w-auto sm:min-w-56"
              >
                {loadingMore ? loadText.loading : loadError ? loadText.retry : loadText.more}
              </button>
            </div>
          )}
        </div>
      </div>

      {filtersOpen && (
        <div className="fixed inset-0 z-[70] lg:hidden" role="dialog" aria-modal="true" aria-labelledby="mobile-filter-title">
          <button type="button" className="absolute inset-0 cursor-default bg-slate-900/40 backdrop-blur-sm" onClick={() => setFiltersOpen(false)} aria-label="Закрыть фильтры" />
          <div className="absolute right-0 top-0 flex h-full h-dvh w-[calc(100%-1rem)] max-w-sm flex-col overflow-hidden bg-white">
            <div className="flex items-center justify-between border-b border-slate-100 px-5 py-4">
              <div className="flex min-w-0 items-center gap-2">
                <h2 id="mobile-filter-title" className="truncate font-display text-lg font-bold">{t("catalog.filters")}</h2>
                {activeFilterCount > 0 && <span className="grid h-6 min-w-6 shrink-0 place-items-center rounded-full bg-brand-100 px-1.5 text-xs font-bold text-brand-800">{activeFilterCount}</span>}
              </div>
              <button onClick={() => setFiltersOpen(false)} className="grid h-11 w-11 shrink-0 place-items-center rounded-xl text-slate-500 hover:bg-slate-100" aria-label={t("catalog.filters")}><X className="h-6 w-6" /></button>
            </div>
            <div className="min-w-0 flex-1 overflow-y-auto overscroll-contain p-4 sm:p-5">{filters}</div>
            <div className="border-t border-slate-100 p-4 [padding-bottom:calc(1rem+env(safe-area-inset-bottom))] sm:px-5 sm:pt-5 sm:[padding-bottom:calc(1.25rem+env(safe-area-inset-bottom))]">
              <button onClick={() => setFiltersOpen(false)} className="h-12 w-full rounded-xl bg-slate-900 text-sm font-semibold text-white">
                {t("catalog.show")} {filtered.length} {plural(filtered.length)}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

/** Узел дерева категорий в сайдбаре: ссылка + раскрытие детей. Активный путь раскрыт по умолчанию. */
function TreeNode({ node, activeHandle, openSet, depth }: { node: CatNode; activeHandle?: string; openSet: Set<string>; depth: number }) {
  const isActive = node.handle === activeHandle;
  const hasChildren = node.children.length > 0;
  const [override, setOverride] = useState<boolean | null>(null);
  const open = override === null ? openSet.has(node.handle) : override;

  return (
    <li>
      <div className={cn("flex items-center rounded-lg transition", isActive ? "bg-slate-100" : "hover:bg-slate-50")}>
        <Link
          href={`/catalog/${node.handle}`}
          className={cn("flex min-h-11 min-w-0 flex-1 items-center truncate py-2 pr-2 text-sm transition", isActive ? "font-semibold text-slate-900" : depth === 0 ? "text-slate-700" : "text-slate-500")}
          style={{ paddingLeft: 12 + depth * 14 }}
        >
          {node.name}
        </Link>
        {hasChildren && (
          <button
            type="button"
            onClick={() => setOverride(!open)}
            aria-label={open ? "Свернуть" : "Развернуть"}
            className="grid h-11 w-11 shrink-0 place-items-center rounded-lg text-slate-400 transition hover:bg-slate-100 hover:text-slate-600"
          >
            <ChevronDown className={cn("h-4 w-4 transition-transform", open && "rotate-180")} />
          </button>
        )}
      </div>
      {hasChildren && open && (
        <ul className="space-y-0.5">
          {node.children.map((c) => (
            <TreeNode key={c.id} node={c} activeHandle={activeHandle} openSet={openSet} depth={depth + 1} />
          ))}
        </ul>
      )}
    </li>
  );
}

/** Рекламный баннер-ячейка в сетке каталога (на 2 колонки), в духе Sephora. */
function PromoCell({ promo, tall = false }: { promo: Promo; tall?: boolean }) {
  return (
    <Link
      href={promo.href}
      className={cn(
        "group relative flex flex-col justify-between overflow-hidden rounded-2xl p-5 text-white",
        promo.className,
        // Вертикальная вставка = одна ячейка (растягивается в высоту карточки товара);
        // горизонтальная — на две колонки.
        tall ? "" : "col-span-2",
      )}
    >
      <Image
        src={promo.img}
        alt=""
        fill
        sizes={tall ? "(max-width: 767px) 50vw, 33vw" : "(max-width: 767px) 100vw, 66vw"}
        className="object-cover transition duration-500 group-hover:scale-105"
        style={{ objectPosition: promo.position ?? "center" }}
      />
      <span className={cn("absolute inset-0 bg-gradient-to-r", promo.overlay ?? "from-slate-950/90 via-slate-950/65 to-slate-950/10")} />
      <div className="relative z-10">
        {promo.eyebrow && <p className="text-[11px] font-bold uppercase tracking-[0.14em] text-white/85">{promo.eyebrow}</p>}
        <h3 className="mt-2 font-display text-2xl font-extrabold leading-tight">{promo.title}</h3>
        {promo.subtitle && <p className="mt-1.5 text-sm text-white/90">{promo.subtitle}</p>}
      </div>
      <span className="relative z-10 mt-5 inline-flex w-fit items-center gap-1.5 rounded-full bg-white px-4 py-2 text-xs font-bold text-slate-900 transition-all group-hover:gap-2.5">
        {promo.cta} <ArrowRight className="h-3.5 w-3.5" />
      </span>
    </Link>
  );
}

function FilterGroup({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div>
      <h3 className="mb-3 text-xs font-bold uppercase tracking-[0.08em] text-slate-900">{title}</h3>
      {children}
    </div>
  );
}

function Checkbox({ checked, onChange, label }: { checked: boolean; onChange: () => void; label: string }) {
  return (
    <button type="button" onClick={onChange} aria-pressed={checked} className="flex min-h-11 w-full min-w-0 items-center gap-2.5 rounded-lg px-1 py-2 text-left text-sm text-slate-700 transition hover:text-slate-900">
      <span className={cn("grid h-5 w-5 shrink-0 place-items-center rounded-md border transition", checked ? "border-slate-900 bg-slate-900 text-white" : "border-slate-300 bg-white")}>
        {checked && <Check className="h-3.5 w-3.5" />}
      </span>
      <span className="min-w-0 break-words">{label}</span>
    </button>
  );
}

function SortSelect({ value, onChange, options }: { value: Sort; onChange: (s: Sort) => void; options: { value: Sort; label: string }[] }) {
  return (
    <div className="relative min-w-0">
      <select value={value} onChange={(e) => onChange(e.target.value as Sort)} className="h-11 w-full min-w-0 appearance-none truncate rounded-xl border border-slate-200 bg-white pl-3 pr-8 text-sm font-medium text-slate-700 outline-none transition hover:border-brand-300 focus:border-brand-400 sm:w-auto sm:pl-4 sm:pr-10">
        {options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
      </select>
      <ChevronDown className="pointer-events-none absolute right-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
    </div>
  );
}
