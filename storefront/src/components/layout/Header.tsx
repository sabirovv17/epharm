"use client";

import { useState, useEffect } from "react";
import Link from "next/link";
import {
  Menu, X, ShoppingCart, Heart, User, Globe, Coins, Percent, ChevronRight,
  CreditCard, BookOpen, Briefcase, Store, MessageSquare, Phone, Clock, UserRound,
} from "lucide-react";
import type { CatNode, Category } from "@/lib/types";
import { cn } from "@/lib/cn";
import { Logo } from "./Logo";
import { CategoryIcon } from "@/components/ui/CategoryIcon";
import { SearchBar } from "./SearchBar";
import { useCart } from "@/lib/cart/CartContext";
import { useAuth } from "@/lib/auth/AuthContext";
import { useFavorites } from "@/lib/favorites/FavoritesContext";
import { useScanner } from "@/lib/ui/ScannerContext";
import { useLang } from "@/lib/i18n/LanguageContext";
import { langs } from "@/lib/i18n/dict";
import { CitySelector } from "./CitySelector";
import { useCatalogData } from "@/lib/content/CatalogData";
import { publicCategoryHandle, resolveCategoryHandle, sortStorefrontCategories } from "@/lib/category-handles";

const sections: { key: string; href: string; accent?: boolean }[] = [
  { key: "nav.brands", href: "/brands" },
  { key: "nav.pharmacies", href: "/pharmacies" },
  { key: "nav.delivery", href: "/delivery" },
];

const serviceLinks = [
  { key: "top.loyalty", href: "/account/bonuses", icon: CreditCard },
  { key: "top.club", href: "/about", icon: BookOpen },
  { key: "top.jobs", href: "/about", icon: Briefcase },
  { key: "top.rent", href: "/about", icon: Store },
  { key: "top.feedback", href: "/help", icon: MessageSquare },
];

// Real top-level Medusa handles used only during the first network round-trip.
// Live categories/tree replace this list as soon as either endpoint responds.
const immediateCategories: Category[] = [
  { id: "nav-bady", slug: "bady", name: "БАДы", count: 0, icon: "Pill", from: "#d8f3ea", to: "#bce8da" },
  { id: "nav-hygiene", slug: "gigiyena", name: "Гигиена", count: 0, icon: "ShowerHead", from: "#d7f2f7", to: "#bce7ef" },
  { id: "nav-cosmetics", slug: "kosmetika", name: "Косметика", count: 0, icon: "Sparkles", from: "#dfe8fb", to: "#c7d7f5" },
  { id: "nav-medicine", slug: "lekarstva", name: "Лекарства", count: 0, icon: "HeartPulse", from: "#e8dcfb", to: "#d9c4f6" },
  { id: "nav-lenses", slug: "linzy", name: "Линзы", count: 0, icon: "Droplets", from: "#fde2ee", to: "#f8c9df" },
  { id: "nav-family", slug: "mama-i-malysh", name: "Мама и малыш", count: 0, icon: "Baby", from: "#ffedca", to: "#ffe0a3" },
  { id: "nav-medical", slug: "med-pribory-i-izdeliya", name: "Мед. приборы и изделия", count: 0, icon: "Stethoscope", from: "#d8f3ea", to: "#bce8da" },
  { id: "nav-sport", slug: "sport-i-fitnes", name: "Спорт и фитнес", count: 0, icon: "Sparkles", from: "#dfe8fb", to: "#c7d7f5" },
  { id: "nav-adult", slug: "intim", name: "Товары для взрослых", count: 0, icon: "Sparkles", from: "#fde2ee", to: "#f8c9df" },
];

function LangSwitcher() {
  const { lang, setLang } = useLang();
  return (
    <div className="flex items-center gap-1.5 text-sm">
      <Globe className="h-3.5 w-3.5 text-slate-400" />
      {langs.map((l, idx) => (
        <span key={l.code} className="flex items-center gap-1.5">
          {idx > 0 && <span className="text-slate-300">·</span>}
          <button
            onClick={() => setLang(l.code)}
            className={cn("text-xs uppercase tracking-wide transition", lang === l.code ? "font-bold text-brand-700" : "text-slate-500 hover:text-slate-900")}
          >
            {l.label}
          </button>
        </span>
      ))}
    </div>
  );
}

export function Header() {
  const [catalogOpen, setCatalogOpen] = useState(false);
  const [mobileOpen, setMobileOpen] = useState(false);
  const [tree, setTree] = useState<CatNode[]>([]);
  const [catalogMenuTree, setCatalogMenuTree] = useState<CatNode[]>([]);
  const [catalogMenuCategories, setCatalogMenuCategories] = useState<Category[]>(immediateCategories);
  const [mobileMenuCategories, setMobileMenuCategories] = useState<Category[]>(immediateCategories);
  const [hovered, setHovered] = useState(0);
  useEffect(() => {
    let alive = true;
    let failures = 0;
    let timer: ReturnType<typeof setTimeout> | null = null;
    let controller: AbortController | null = null;

    const schedule = (delay: number) => {
      if (!alive) return;
      if (timer) clearTimeout(timer);
      timer = setTimeout(load, delay);
    };
    const load = async () => {
      controller = new AbortController();
      const timeout = setTimeout(() => controller?.abort(), 6_000);
      try {
        const response = await fetch("/api/category-tree", { cache: "default", signal: controller.signal });
        const payload = response.ok ? await response.json() : [];
        if (!Array.isArray(payload) || payload.length === 0) throw new Error("empty_tree");
        if (alive) setTree(payload);
        failures = 0;
        schedule(10 * 60_000);
      } catch {
        failures += 1;
        schedule(Math.min(30_000, 1_000 * 2 ** Math.min(failures - 1, 5)));
      } finally {
        clearTimeout(timeout);
        controller = null;
      }
    };
    const reconnect = () => schedule(0);
    const refreshVisible = () => {
      if (document.visibilityState === "visible") reconnect();
    };

    void load();
    window.addEventListener("online", reconnect);
    document.addEventListener("visibilitychange", refreshVisible);
    return () => {
      alive = false;
      if (timer) clearTimeout(timer);
      controller?.abort();
      window.removeEventListener("online", reconnect);
      document.removeEventListener("visibilitychange", refreshVisible);
    };
  }, []);
  const { count, open } = useCart();
  const { user, openLogin } = useAuth();
  const { count: favCount } = useFavorites();
  const { open: openBonus } = useScanner();
  const { setLang, t, plural } = useLang();
  const { categories } = useCatalogData();
  // The lightweight category tree arrives before the full 100-product client
  // catalogue. Use it immediately so a freshly opened mobile menu is never empty.
  const navCategories = categories.length > 0 ? categories : tree.length > 0 ? tree.map((node, index) => {
    const palette = [
      ["#d8f3ea", "#bce8da"], ["#dfe8fb", "#c7d7f5"], ["#fde2ee", "#f8c9df"],
      ["#ffedca", "#ffe0a3"], ["#e8dcfb", "#d9c4f6"], ["#d7f2f7", "#bce7ef"],
    ][index % 6];
    return { id: node.id, slug: node.handle, name: node.name, count: 0, icon: "Sparkles", from: palette[0], to: palette[1] };
  }) : immediateCategories;

  const chip = "inline-flex shrink-0 items-center gap-1 rounded-full border border-white/20 bg-white/10 px-2.5 py-1.5 text-xs font-semibold text-white transition hover:bg-white/20";
  const action = "flex min-h-11 min-w-11 flex-col items-center justify-center gap-0.5 text-slate-700 transition hover:text-brand-700";
  const badge = "absolute -right-1.5 -top-1.5 grid h-4 min-w-4 place-items-center rounded-full bg-accent-500 px-1 text-[10px] font-bold text-white";
  const presentedNavCategories = sortStorefrontCategories(navCategories.map((category) => {
    const canonical = resolveCategoryHandle(category.slug);
    if (canonical === "lekarstva-i-bady") {
      return { ...category, slug: publicCategoryHandle(canonical), name: t("cat.medicines") };
    }
    if (canonical === "bady") return { ...category, slug: "bady", name: "БАДы" };
    return category;
  }), (category) => category.slug);
  const toggleCatalogMenu = () => {
    if (!catalogOpen) {
      // Keep the rendered links stable until the menu is closed. Live Medusa
      // responses can arrive during a pointer press and must not replace its DOM.
      setCatalogMenuTree(sortStorefrontCategories(tree, (category) => category.handle));
      setCatalogMenuCategories(presentedNavCategories);
      setHovered(0);
    }
    setCatalogOpen((open) => !open);
  };
  const openMobileMenu = () => {
    setMobileMenuCategories(presentedNavCategories);
    setMobileOpen(true);
  };
  const closeCatalogAfterNavigation = () => window.setTimeout(() => setCatalogOpen(false), 0);
  const closeMobileAfterNavigation = () => window.setTimeout(() => setMobileOpen(false), 0);

  return (
    <header className="sticky top-0 z-50 border-b border-slate-100 bg-white">
      {/* ── thin top row (desktop) ── */}
      <div className="hidden border-b border-slate-200 bg-white lg:block">
        <div className="mx-auto flex h-10 max-w-[1600px] items-center gap-5 px-6">
          <div className="shrink-0"><CitySelector /></div>
          <nav aria-label={t("top.services")} className="no-scrollbar flex min-w-0 flex-1 items-center gap-4 overflow-x-auto">
            {serviceLinks.map(({ key, href, icon: Icon }) => (
              <Link key={key} href={href} className="inline-flex shrink-0 items-center gap-1.5 text-[12px] font-medium text-slate-600 transition hover:text-brand-700">
                <Icon className="h-3.5 w-3.5 text-slate-400" /> {t(key)}
              </Link>
            ))}
            <a href="tel:+77000000000" className="inline-flex shrink-0 items-center gap-1.5 text-[12px] font-medium text-slate-700 transition hover:text-brand-700">
              <Phone className="h-3.5 w-3.5 text-slate-400" /> +7 700 000 00 00
            </a>
            <span className="inline-flex shrink-0 items-center gap-1.5 text-[12px] font-medium text-slate-600">
              <Clock className="h-3.5 w-3.5 text-slate-400" /> {t("top.hours")}
            </span>
            {user ? (
              <Link href="/account" className="inline-flex shrink-0 items-center gap-1.5 text-[12px] font-medium text-slate-700 transition hover:text-brand-700">
                <UserRound className="h-3.5 w-3.5 text-slate-400" /> {t("act.account")}
              </Link>
            ) : (
              <button onClick={openLogin} className="inline-flex shrink-0 items-center gap-1.5 text-[12px] font-medium text-slate-700 transition hover:text-brand-700">
                <UserRound className="h-3.5 w-3.5 text-slate-400" /> {t("top.login")}
              </button>
            )}
          </nav>
          <div className="shrink-0"><LangSwitcher /></div>
        </div>
      </div>

      {/* ── main row ── */}
      <div className="mx-auto max-w-[1600px] px-4 py-2.5 sm:px-6">
        <div className="flex min-w-0 items-center gap-2 sm:gap-4">
          <button onClick={openMobileMenu} className="grid h-11 w-11 shrink-0 place-items-center rounded-xl text-slate-700 hover:bg-slate-100 lg:hidden" aria-label="Открыть меню" aria-expanded={mobileOpen} aria-controls="mobile-site-menu">
            <Menu className="h-6 w-6" />
          </button>

          <Logo className="min-w-0 shrink" />

          <button
            onClick={toggleCatalogMenu}
            className="hidden h-12 shrink-0 items-center gap-2 rounded-full bg-brand-600 px-5 font-semibold text-white transition hover:bg-brand-700 lg:inline-flex"
          >
            {catalogOpen ? <X className="h-5 w-5" /> : <Menu className="h-5 w-5" />} {t("nav.catalog")}
          </button>

          <SearchBar className="hidden flex-1 sm:block" />

          <button
            onClick={openBonus}
            className="hidden h-12 shrink-0 items-center gap-2 rounded-full bg-brand-50 px-4 font-semibold text-brand-700 transition hover:bg-brand-100 lg:inline-flex"
          >
            <Coins className="h-5 w-5" /> {t("header.bonus")}
          </button>

          <div className="ml-auto flex shrink-0 items-center gap-1 sm:gap-5">
            <Link href="/favorites" className={cn(action, "hidden sm:flex")}>
              <span className="relative"><Heart className="h-6 w-6" />{favCount > 0 && <span className={badge}>{favCount}</span>}</span>
              <span className="text-[11px]">{t("act.favorites")}</span>
            </Link>
            {user ? (
              <Link href="/account" className={cn(action, "hidden sm:flex")}>
                <User className="h-6 w-6" />
                <span className="text-[11px]">{t("act.account")}</span>
              </Link>
            ) : (
              <button onClick={openLogin} className={cn(action, "hidden sm:flex")}>
                <User className="h-6 w-6" />
                <span className="text-[11px]">{t("act.login")}</span>
              </button>
            )}
            <button onClick={open} className={cn(action, "rounded-xl hover:bg-slate-100 sm:rounded-none sm:hover:bg-transparent")} aria-label={t("act.cart")}>
              <span className="relative"><ShoppingCart className="h-6 w-6" />{count > 0 && <span className={badge}>{count}</span>}</span>
              <span className="hidden text-[11px] sm:block">{t("act.cart")}</span>
            </button>
          </div>
        </div>

        {/* mobile search */}
        <div className="mt-2.5 sm:hidden"><SearchBar /></div>
      </div>

      {/* ── category chips (desktop) ── */}
      <div className="hidden border-t border-brand-800 bg-brand-700 lg:block">
        <div className="no-scrollbar mx-auto flex max-w-[1600px] items-center gap-1.5 overflow-x-auto px-4 py-2.5">
          <Link href="/promotions" className={cn(chip, "border-white bg-white text-brand-800 hover:bg-brand-50")}><Percent className="h-3.5 w-3.5" /> {t("nav.sale")}</Link>
          {presentedNavCategories.map((cat) => (
            <Link key={cat.slug} href={`/catalog/${cat.slug}`} className={chip}>{cat.name}</Link>
          ))}
          <Link href="/catalog" className={cn(chip, "border-white/40 bg-white/15")}>{t("common.viewAll")}</Link>
        </div>
      </div>

      {/* ── Catalog mega menu ── */}
      {catalogOpen && (
        <>
          <button className="fixed inset-0 z-40 cursor-default" onClick={() => setCatalogOpen(false)} aria-label="Закрыть" tabIndex={-1} />
          <div className="absolute inset-x-0 top-full z-50 border-b border-slate-100 bg-white shadow-pop">
            <div className="mx-auto max-w-7xl px-6 py-6">
              {catalogMenuTree.length > 0 ? (
                <div className="grid gap-6 md:grid-cols-[260px_1fr]">
                  {/* верхние категории — наведение раскрывает подкатегории справа */}
                  <ul className="space-y-0.5 md:border-r md:border-slate-100 md:pr-4">
                    {catalogMenuTree.map((c, i) => (
                      <li key={c.id} onMouseEnter={() => setHovered(i)}>
                        <Link
                          href={`/catalog/${c.handle}`}
                          onClick={closeCatalogAfterNavigation}
                          className={cn(
                            "flex items-center justify-between gap-2 rounded-xl px-3 py-2.5 text-sm font-medium transition",
                            i === hovered ? "bg-brand-50 text-brand-700" : "text-slate-700 hover:bg-slate-50",
                          )}
                        >
                          <span className="truncate">{c.name}</span>
                          {c.children.length > 0 && <ChevronRight className="h-4 w-4 shrink-0 opacity-50" />}
                        </Link>
                      </li>
                    ))}
                  </ul>
                  <div className="max-h-[74vh] min-h-[200px] overflow-y-auto pr-1">
                    <Link
                      href={`/catalog/${(catalogMenuTree[hovered] || catalogMenuTree[0]).handle}`}
                      onClick={closeCatalogAfterNavigation}
                      className="font-display text-base font-bold text-slate-900 transition hover:text-brand-700"
                    >
                      {(catalogMenuTree[hovered] || catalogMenuTree[0]).name}
                    </Link>
                    {(catalogMenuTree[hovered] || catalogMenuTree[0]).children.length > 0 ? (
                      // Подкатегория = жирный заголовок-ссылка, под ней — категории 3-го уровня.
                      <div className="mt-4 gap-x-6 [column-fill:balance] sm:columns-2 lg:columns-3">
                        {(catalogMenuTree[hovered] || catalogMenuTree[0]).children.map((s) => (
                          <div key={s.id} className="mb-4 break-inside-avoid">
                            <Link
                              href={`/catalog/${s.handle}`}
                              onClick={closeCatalogAfterNavigation}
                              className="block text-sm font-semibold text-slate-900 transition hover:text-brand-700"
                            >
                              {s.name}
                            </Link>
                            {s.children.length > 0 && (
                              <ul className="mt-1.5 space-y-1">
                                {s.children.map((g) => (
                                  <li key={g.id}>
                                    <Link
                                      href={`/catalog/${g.handle}`}
                                      onClick={closeCatalogAfterNavigation}
                                      className="block truncate text-[13px] text-slate-500 transition hover:text-brand-700"
                                    >
                                      {g.name}
                                    </Link>
                                  </li>
                                ))}
                              </ul>
                            )}
                          </div>
                        ))}
                      </div>
                    ) : (
                      <p className="mt-3 text-sm text-slate-400">Перейти в раздел «{(catalogMenuTree[hovered] || catalogMenuTree[0]).name}»</p>
                    )}
                  </div>
                </div>
              ) : (
                <div className="grid grid-cols-2 gap-2 md:grid-cols-4">
                  {catalogMenuCategories.map((cat) => (
                    <Link key={cat.slug} href={`/catalog/${cat.slug}`} onClick={closeCatalogAfterNavigation} className="flex items-center gap-3 rounded-xl p-3 transition hover:bg-slate-50">
                      <span className="grid h-11 w-11 shrink-0 place-items-center rounded-xl" style={{ background: `linear-gradient(135deg, ${cat.from}, ${cat.to})` }}>
                        <CategoryIcon name={cat.icon} className="h-5 w-5 text-slate-700" />
                      </span>
                      <span>
                        <span className="block text-sm font-medium text-slate-800">{cat.name}</span>
                        {cat.count > 0 && <span className="text-xs text-slate-400">{cat.count} {plural(cat.count)}</span>}
                      </span>
                    </Link>
                  ))}
                </div>
              )}
            </div>
          </div>
        </>
      )}

      {/* ── Mobile menu ── */}
      {mobileOpen && (
        <div className="fixed inset-0 z-[60] lg:hidden">
          <div className="absolute inset-0 bg-slate-900/40 backdrop-blur-sm" onClick={() => setMobileOpen(false)} />
          <div id="mobile-site-menu" role="dialog" aria-modal="true" className="absolute left-0 top-0 flex h-full h-dvh w-[calc(100%-1rem)] max-w-sm flex-col bg-white">
            <div className="flex items-center justify-between border-b border-slate-100 px-5 py-4">
              <Logo />
              <button onClick={() => setMobileOpen(false)} className="grid h-11 w-11 place-items-center rounded-xl text-slate-600 hover:bg-slate-100" aria-label="Закрыть меню">
                <X className="h-6 w-6" />
              </button>
            </div>
            <div className="flex-1 overflow-y-auto p-5">
              <p className="text-xs font-semibold uppercase tracking-wide text-slate-400">{t("drawer.city")}</p>
              <div className="mt-2"><CitySelector full /></div>

              <p className="mt-6 text-xs font-semibold uppercase tracking-wide text-slate-400">{t("drawer.categories")}</p>
              <nav className="mt-2 space-y-1">
                {mobileMenuCategories.map((cat) => (
                  <Link key={cat.slug} href={`/catalog/${cat.slug}`} onClick={closeMobileAfterNavigation} className="flex items-center gap-3 rounded-xl p-2.5 transition hover:bg-slate-50">
                    <span className="grid h-10 w-10 shrink-0 place-items-center rounded-lg" style={{ background: `linear-gradient(135deg, ${cat.from}, ${cat.to})` }}>
                      <CategoryIcon name={cat.icon} className="h-5 w-5 text-slate-700" />
                    </span>
                    <span className="text-sm font-medium text-slate-800">{cat.name}</span>
                  </Link>
                ))}
              </nav>

              <p className="mt-6 text-xs font-semibold uppercase tracking-wide text-slate-400">{t("drawer.sections")}</p>
              <nav className="mt-2 grid grid-cols-2 gap-1">
                {sections.map((n) => (
                  <Link key={n.key} href={n.href} onClick={closeMobileAfterNavigation} className="flex min-h-11 items-center rounded-xl px-2.5 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50">
                    {t(n.key)}
                  </Link>
                ))}
              </nav>

              <p className="mt-6 text-xs font-semibold uppercase tracking-wide text-slate-400">{t("drawer.lang")}</p>
              <div className="mt-2 flex gap-2">
                {langs.map((l) => (
                  <button key={l.code} onClick={() => setLang(l.code)} className="min-h-11 rounded-xl border border-slate-200 px-4 py-2 text-sm font-medium text-slate-700 hover:border-brand-300">
                    {l.label}
                  </button>
                ))}
              </div>
            </div>

            <div className="space-y-2 border-t border-slate-100 p-4 [padding-bottom:calc(1rem+env(safe-area-inset-bottom))]">
              <div className="grid grid-cols-2 gap-2">
                <Link href="/favorites" onClick={closeMobileAfterNavigation} className="relative flex min-h-12 min-w-0 items-center justify-center gap-2 rounded-xl border border-slate-200 px-2 text-sm font-semibold text-slate-700 transition hover:border-brand-300 hover:text-brand-700">
                  <span className="relative shrink-0">
                    <Heart className="h-5 w-5" />
                    {favCount > 0 && <span className={badge}>{favCount}</span>}
                  </span>
                  <span className="truncate">{t("act.favorites")}</span>
                </Link>
                {user ? (
                  <Link href="/account" onClick={closeMobileAfterNavigation} className="flex min-h-12 min-w-0 items-center justify-center gap-2 rounded-xl bg-brand-600 px-2 text-sm font-semibold text-white transition hover:bg-brand-700">
                    <User className="h-5 w-5 shrink-0" /> <span className="truncate">{t("drawer.account")}</span>
                  </Link>
                ) : (
                  <button onClick={() => { setMobileOpen(false); openLogin(); }} className="flex min-h-12 min-w-0 items-center justify-center gap-2 rounded-xl bg-brand-600 px-2 text-sm font-semibold text-white transition hover:bg-brand-700">
                    <User className="h-5 w-5 shrink-0" /> <span>{t("act.login")}</span>
                  </button>
                )}
              </div>
              <button onClick={() => { setMobileOpen(false); openBonus(); }} className="flex min-h-11 w-full items-center justify-center gap-2 rounded-xl border border-slate-200 text-sm font-semibold text-slate-700 transition hover:border-brand-300 hover:text-brand-700">
                <Coins className="h-5 w-5" /> {t("header.bonus")}
              </button>
            </div>
          </div>
        </div>
      )}
    </header>
  );
}
