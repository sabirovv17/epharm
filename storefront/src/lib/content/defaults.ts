/**
 * Единая модель витринного контента (баннеры, сторис, подборки, промокоды, заказы).
 * БЕЗ "use client" — этот модуль импортируется и серверными route-обработчиками,
 * и клиентским ContentContext. Здесь только типы, дефолты и чистые помощники.
 */

// img/video — ссылки на загруженные файлы (/uploads/...). Приоритет показа: video > img > градиент/иконка.
export type Banner = { id: string; eyebrow: string; title: string; sub: string; cta: string; href: string; from: string; to: string; img: string; video?: string; on: boolean };
export type Collection = { id: string; title: string; href: string; from: string; to: string; icon: string; img?: string };
export type Promo = { id: string; code: string; type: "percent" | "amount" | "free"; value: number; note: string };
export type Order = { id: string; n: number; date: string; sum: number; status: string; items: number; code?: string; delivery?: string };
export type Story = { id: string; label: string; icon: string; href: string; from: string; to: string; img?: string; video?: string; on: boolean };
// Быстрые ссылки — плитки-навигация рядом с рекламным билбордом на главной (панель 2×2).
export type QuickLink = { id: string; label: string; href: string; from: string; to: string; icon: string; img?: string; on: boolean };
export type Content = { banners: Banner[]; collections: Collection[]; promos: Promo[]; orders: Order[]; stories: Story[]; quickLinks: QuickLink[] };

const QUICK_LINK_DEFAULT_IMAGES: Record<string, string> = {
  ql1: "/promo/quick-beauty-unique.webp",
  ql2: "/promo/quick-more-unique.webp",
  ql3: "/promo/quick-vitamins-unique.webp",
  ql4: "/promo/quick-family-unique.webp",
};

export const ORDER_STATUSES = ["Новый", "Собирается", "Доставляется", "Готов к выдаче", "Получен", "Отменён"];

export const DEFAULT_CONTENT: Content = {
  banners: [
    // Живые lifestyle-фото чередуются с готовыми товарными рекламами.
    { id: "b4", eyebrow: "Уход за собой", title: "Красота начинается\nс заботы о себе", sub: "Всё для ежедневного ухода", cta: "Смотреть подборку", href: "/catalog/face-care", from: "#073f2f", to: "#1b7a58", img: "/promo/banner-lifestyle-skincare.webp", on: true },
    { id: "bp-selfielab", eyebrow: "", title: "", sub: "", cta: "", href: "/search?q=SelfieLab", from: "#0b6f3c", to: "#34c97d", img: "/promo/banner-selfielab.webp", on: true },
    { id: "b5", eyebrow: "Для всей семьи", title: "Забота, которая\nвсегда рядом", sub: "Нужное для дома и на каждый день", cta: "Открыть раздел", href: "/catalog/medicines", from: "#075f58", to: "#2aa69b", img: "/promo/banner-lifestyle-family.webp", on: true },
    { id: "bp-ivatherm", eyebrow: "", title: "", sub: "", cta: "", href: "/catalog/face-care", from: "#5a4ac0", to: "#8a7ef0", img: "/promo/banner-ivatherm.webp", on: true },
    { id: "b6", eyebrow: "Витамины и баланс", title: "В движении\nкаждый день", sub: "Витамины и минералы в одном разделе", cta: "Выбрать витамины", href: "/catalog/vitamins", from: "#365f3d", to: "#c28a2e", img: "/promo/banner-lifestyle-wellness.webp", on: true },
    { id: "b7", eyebrow: "Мама и малыш", title: "Нежная забота\nс первых дней", sub: "Товары для мам и малышей", cta: "Смотреть товары", href: "/catalog/mom-baby", from: "#69428f", to: "#b78ada", img: "/promo/banner-lifestyle-mom-baby.webp", on: true },
  ],
  stories: [
    { id: "s2", label: "Акции", icon: "gift", href: "/promotions", from: "#f0833c", to: "#e0531f", img: "/promo/stories/story-promotions-v1.webp", on: true },
    { id: "s3", label: "Уход", icon: "sparkle", href: "/catalog/face-care", from: "#e978b0", to: "#bf3f82", img: "/promo/stories/story-care-v1.webp", on: true },
    { id: "s4", label: "Витамины", icon: "pill", href: "/catalog/vitamins", from: "#34c97d", to: "#0c7d43", img: "/promo/stories/story-vitamins-v1.webp", on: true },
    { id: "s5", label: "Лекарства", icon: "shield", href: "/catalog/medicines", from: "#0da89a", to: "#066a60", img: "/promo/stories/story-medicines-v1.webp", on: true },
    { id: "s6", label: "Малышам", icon: "baby", href: "/catalog/mom-baby", from: "#8a7ef0", to: "#574ac0", img: "/promo/stories/story-baby-v1.webp", on: true },
    { id: "s7", label: "Аптеки", icon: "heart", href: "/pharmacies", from: "#1e94d8", to: "#0c5ba6", img: "/promo/stories/story-pharmacies-v1.webp", on: true },
    { id: "s8", label: "Доставка", icon: "truck", href: "/delivery", from: "#f0b64b", to: "#c87a1c", img: "/promo/stories/story-delivery-v1.webp", on: true },
  ],
  quickLinks: [
    { id: "ql1", label: "Красота",           href: "/catalog/makeup",   icon: "sparkle", img: QUICK_LINK_DEFAULT_IMAGES.ql1, from: "#05291b", to: "#0a6b3a", on: true },
    { id: "ql2", label: "Больше чем аптека", href: "/catalog",          icon: "gift",    img: QUICK_LINK_DEFAULT_IMAGES.ql2, from: "#4d0c1c", to: "#ab1838", on: true },
    { id: "ql3", label: "Витамины",          href: "/catalog/vitamins", icon: "pill",    img: QUICK_LINK_DEFAULT_IMAGES.ql3, from: "#523a07", to: "#8b6a16", on: true },
    { id: "ql4", label: "Мама и малыш",      href: "/catalog/mom-baby", icon: "baby",    img: QUICK_LINK_DEFAULT_IMAGES.ql4, from: "#073b36", to: "#075248", on: true },
  ],
  collections: [
    { id: "c1", title: "Иммунитет и энергия", href: "/catalog/vitamins", from: "#34c97d", to: "#0c7d43", icon: "shield", img: "/promo/care-energy.webp" },
    { id: "c2", title: "Сон и антистресс", href: "/catalog/vitamins", from: "#7b86e6", to: "#4145b0", icon: "moon", img: "/promo/care-sleep.webp" },
    { id: "c3", title: "Сияние кожи", href: "/catalog/face-care", from: "#e978b0", to: "#bf3f82", icon: "sparkle", img: "/promo/care-skin.webp" },
    { id: "c4", title: "Глубокое увлажнение", href: "/catalog/face-care", from: "#3fb6e0", to: "#1685bb", icon: "drop", img: "/promo/care-hydration.webp" },
    { id: "c5", title: "Под защитой солнца", href: "/catalog/sun", from: "#f5b042", to: "#de7a16", icon: "sun", img: "/promo/care-sun.webp" },
    { id: "c6", title: "Сезон аллергии", href: "/catalog/medicines", from: "#2fb3a0", to: "#0e8a7a", icon: "flower", img: "/promo/care-allergy.webp" },
    { id: "c7", title: "Мама и малыш", href: "/catalog/mom-baby", from: "#8a7ef0", to: "#574ac0", icon: "baby", img: "/promo/care-mom-baby.webp" },
  ],
  promos: [],
  orders: [],
};

export function uid() {
  return "x" + Math.random().toString(36).slice(2, 9);
}

const FALLBACK_FROM = "#34c97d";
const FALLBACK_TO = "#0b6f3c";

const STOREFRONT_ROUTES = [
  "/catalog",
  "/search",
  "/promotions",
  "/brands",
  "/favorites",
  "/pharmacies",
  "/delivery",
  "/help",
  "/about",
  "/account",
  "/cart",
  "/product",
  "/ai",
] as const;

/**
 * Keeps editable CMS links on real storefront routes. Old empty/hash links and
 * accidental links to media files used as placeholders fall back to catalog.
 * Absolute http(s) campaign links remain supported.
 */
export function normalizeStorefrontHref(value: unknown): string {
  const href = String(value ?? "").trim();
  if (!href || href === "#" || href.startsWith("//")) return "/catalog";

  if (/^https?:\/\//i.test(href)) {
    try {
      const url = new URL(href);
      return url.protocol === "http:" || url.protocol === "https:" ? href : "/catalog";
    } catch {
      return "/catalog";
    }
  }
  if (!href.startsWith("/")) return "/catalog";

  let url: URL;
  try {
    url = new URL(href, "https://storefront.invalid");
  } catch {
    return "/catalog";
  }

  const pathname = url.pathname.replace(/\/+$/, "") || "/";
  if (pathname === "/") return href;
  if (pathname === "/promo" || pathname.startsWith("/promo/")) return "/catalog";
  if (!STOREFRONT_ROUTES.some((route) => pathname === route || pathname.startsWith(`${route}/`))) return "/catalog";
  if (pathname === "/search" && !url.searchParams.get("q")?.trim()) return "/catalog";
  return href;
}

/** Keeps CMS records saved before the WebP migration working after PNG assets are removed. */
function optimizedMediaUrl(value: unknown): string {
  const url = String(value ?? "");
  if (!url.startsWith("/promo/")) return url;
  return url.replace(/\.png(?=([?#].*)?$)/i, ".webp");
}

/** Гарантирует валидные стопы градиента и флаг показа у элемента с медиа/цветами. */
function normBanner(b: Partial<Banner>): Banner {
  return {
    id: String(b.id ?? uid()), eyebrow: String(b.eyebrow ?? ""), title: String(b.title ?? ""),
    sub: String(b.sub ?? ""), cta: String(b.cta ?? ""), href: normalizeStorefrontHref(b.href),
    from: b.from || FALLBACK_FROM, to: b.to || FALLBACK_TO, img: optimizedMediaUrl(b.img),
    video: b.video ? String(b.video) : "", on: b.on !== false,
  };
}
function normStory(s: Partial<Story>): Story {
  return {
    id: String(s.id ?? uid()), label: String(s.label ?? ""), icon: String(s.icon ?? "sparkle"),
    href: normalizeStorefrontHref(s.href), from: s.from || FALLBACK_FROM, to: s.to || FALLBACK_TO,
    img: optimizedMediaUrl(s.img), video: s.video ? String(s.video) : "", on: s.on !== false,
  };
}

function normQuickLink(q: Partial<QuickLink>): QuickLink {
  const id = String(q.id ?? uid());
  const hasImageField = Object.prototype.hasOwnProperty.call(q, "img");
  return {
    id, label: String(q.label ?? ""), href: normalizeStorefrontHref(q.href),
    from: q.from || FALLBACK_FROM, to: q.to || FALLBACK_TO, icon: String(q.icon ?? "sparkle"),
    img: hasImageField ? optimizedMediaUrl(q.img) : (QUICK_LINK_DEFAULT_IMAGES[id] ?? ""),
    on: q.on !== false,
  };
}

function normCollection(c: Partial<Collection>): Collection {
  return {
    id: String(c.id ?? uid()), title: String(c.title ?? ""), href: normalizeStorefrontHref(c.href),
    from: c.from || FALLBACK_FROM, to: c.to || FALLBACK_TO, icon: String(c.icon ?? "sparkle"),
    img: optimizedMediaUrl(c.img),
  };
}

/** Приводит частичный/«сырой» контент (с диска, из localStorage, из тела POST) к полной форме.
 *  Санитизирует элементы баннеров/сторис (валидный градиент, on), чтобы битый item не ломал верстку. */
export function normalizeContent(p: Partial<Content> | null | undefined): Content {
  const c = p || {};
  return {
    banners: Array.isArray(c.banners) ? c.banners.map(normBanner) : DEFAULT_CONTENT.banners,
    stories: Array.isArray(c.stories) ? c.stories.map(normStory) : DEFAULT_CONTENT.stories,
    collections: Array.isArray(c.collections) ? c.collections.map(normCollection) : DEFAULT_CONTENT.collections,
    // Промокоды и заказы — только Meduza; старые записи CMS намеренно не подмешиваем.
    promos: [],
    orders: [],
    quickLinks: Array.isArray(c.quickLinks) ? c.quickLinks.map(normQuickLink) : DEFAULT_CONTENT.quickLinks,
  };
}
