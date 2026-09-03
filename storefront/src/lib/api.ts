/**
 * Единственная точка доступа фронтенда к данным каталога.
 * Источник истины — реальный Medusa (серверный fetch с publishable-ключом).
 * Никаких мок-товаров: если бэкенд недоступен, отдаём пустой список, а UI
 * показывает аккуратное «каталог временно недоступен» вместо выдуманных данных.
 * Витринный маркетинг-контент (баннеры/сторис/подборки) — отдельный CMS-слой.
 */
import type { Brand, CatNode, Category, Product, ProductBadge } from "@/lib/types";
import { plural } from "@/lib/format";
import {
  getCatalogReadBrandProducts,
  getCatalogReadBrandCounts,
  getCatalogReadCategoryProducts,
  getCatalogReadCategoryTree,
  getCatalogReadProduct,
  getCatalogReadProducts,
  searchCatalogReadProducts,
} from "@/lib/catalog-read";
import { MEDUSA_ON, getKnownMedusaProduct, getMedusaProducts, getMedusaProductByHandle, getCategoryTree, getMedusaCategoryProducts, searchMedusaProducts, getPharmacyPrices, type PriceInfo } from "@/lib/medusa";
import { resolveLocalFirstProduct } from "@/lib/product-read";
import { resolveCategoryHandle } from "@/lib/category-handles";

const CATEGORY_PALETTE = [
  ["#0f766e", "#14b8a6"], ["#166534", "#22c55e"], ["#1d4ed8", "#60a5fa"],
  ["#7e22ce", "#c084fc"], ["#be123c", "#fb7185"], ["#b45309", "#fbbf24"],
] as const;

function categoryIcon(handle: string, name: string): string {
  const key = `${handle} ${name}`.toLowerCase();
  if (key.includes("бад") || key.includes("витамин") || key.includes("bady")) return "Pill";
  if (key.includes("космет") || key.includes("красот") || key.includes("kosmetika")) return "Sparkles";
  if (key.includes("лекар") || key.includes("lekarstva")) return "HeartPulse";
  if (key.includes("гиги") || key.includes("gigiyena")) return "ShowerHead";
  if (key.includes("мама") || key.includes("малыш") || key.includes("mama")) return "Baby";
  if (key.includes("мед") || key.includes("pribory")) return "Stethoscope";
  if (key.includes("солн") || key.includes("загар")) return "Sun";
  if (key.includes("линз")) return "Droplets";
  return "Sparkles";
}

async function allProducts(): Promise<Product[]> {
  return getCatalogReadProducts();
}

export async function getCategories(): Promise<Category[]> {
  const tree = await getCatTree();
  return tree.map((node, index) => {
    const [from, to] = CATEGORY_PALETTE[index % CATEGORY_PALETTE.length];
    return { id: node.id, slug: node.handle, name: node.name, icon: categoryIcon(node.handle, node.name), from, to, count: 0 };
  });
}

/** Реальные счётчики по категориям из живого каталога (exact-привязка либо ключевые слова). */
export function withCategoryCounts(cats: Category[], prods: Product[]): Category[] {
  return cats.map((c) => ({ ...c, count: prods.filter((p) => p.categorySlug === c.slug || p.categoryHandles?.includes(c.slug)).length }));
}

function brandSlug(name: string): string {
  return name.toLowerCase().replace(/[^a-zа-я0-9]+/gi, "-").replace(/^-+|-+$/g, "").slice(0, 48) || "brand";
}
function brandHue(name: string): number {
  let h = 0;
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) >>> 0;
  return h % 360;
}

/** Бренды строятся из реального каталога (Medusa): уникальные по убыванию числа товаров. */
export async function getBrands(): Promise<Brand[]> {
  const local = await getCatalogReadBrandCounts();
  if (local) {
    return local.map(({ name, count }, index) => ({
      id: `br${index}`,
      slug: brandSlug(name),
      name,
      tagline: `${count} ${plural(count, "товар", "товара", "товаров")}`,
      hue: brandHue(name),
    }));
  }
  const all = await allProducts();
  const counts = new Map<string, number>();
  for (const p of all) {
    const b = (p.brand || "").trim();
    if (b && b !== "—") counts.set(b, (counts.get(b) ?? 0) + 1);
  }
  if (counts.size === 0) return [];
  return [...counts.entries()]
    .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
    .map(([name, cnt], i) => ({
      id: "br" + i,
      slug: brandSlug(name),
      name,
      tagline: `${cnt} ${plural(cnt, "товар", "товара", "товаров")}`,
      hue: brandHue(name),
    }));
}

export async function getProductsByBrand(slug: string): Promise<Product[]> {
  const local = await getCatalogReadBrandProducts(slug);
  if (local) return local;
  const all = await allProducts();
  return all.filter((p) => brandSlug(p.brand) === slug);
}

export async function getProducts(): Promise<Product[]> {
  return allProducts();
}

export async function getProductsByBadge(badge: ProductBadge): Promise<Product[]> {
  return (await allProducts()).filter((p) => p.badges.includes(badge));
}

export async function getBestsellers(limit = 8): Promise<Product[]> {
  const all = await allProducts();
  const hits = all.filter((p) => p.badges.includes("hit"));
  const candidates = hits.length > 0 ? hits : [...all.filter((p) => p.image), ...all.filter((p) => !p.image)];
  return [...new Map(candidates.map((product) => [product.id, product])).values()].slice(0, limit);
}

export async function getNewArrivals(limit = 8): Promise<Product[]> {
  const all = await allProducts();
  const n = all.filter((p) => p.badges.includes("new"));
  const candidates = n.length > 0 ? n : [...all.filter((p) => p.image), ...all.filter((p) => !p.image)].reverse();
  return [...new Map(candidates.map((product) => [product.id, product])).values()].slice(0, limit);
}

export async function getDeals(limit = 8): Promise<Product[]> {
  const all = await allProducts();
  const deals = all.filter((p) => p.oldPrice);
  return deals.slice(0, limit);
}

async function getMedusaProductFallback(slug: string): Promise<Product | null> {
  if (!MEDUSA_ON) return null;
  // A product opened from a listing is already known in this process. Render
  // that summary immediately and warm the rich PDP record in the background.
  const known = getKnownMedusaProduct(slug);
  if (known) {
    if (!known.description) void getMedusaProductByHandle(slug).catch(() => undefined);
    return known;
  }
  try {
    const cached = (await getMedusaProducts(100)).find((product) => product.slug === slug);
    if (cached) {
      void getMedusaProductByHandle(slug).catch(() => undefined);
      return cached;
    }
  } catch {
    // The direct lookup below still handles products outside the first page.
  }
  return getMedusaProductByHandle(slug);
}

/**
 * Shared local-first PDP resolver. It may throw only when the local record was
 * absent/unavailable and the final direct Medusa fallback failed.
 */
export async function resolveProductBySlug(slug: string): Promise<Product | null> {
  return resolveLocalFirstProduct(slug, {
    local: getCatalogReadProduct,
    fallback: getMedusaProductFallback,
  });
}

export async function getProductBySlug(slug: string): Promise<Product | null> {
  try {
    return await resolveProductBySlug(slug);
  } catch {
    return getKnownMedusaProduct(slug);
  }
}

/** Серверный поиск по всей базе Medusa (а не по 100 загруженным). */
export async function searchProducts(q: string, limit = 40): Promise<Product[]> {
  const query = q.trim();
  if (!query) return [];
  const localResult = await searchCatalogReadProducts(query, limit);
  if (localResult) return localResult;
  if (!MEDUSA_ON) return [];
  let local: Product[] = [];
  try {
    const normalized = query.toLowerCase();
    local = (await getMedusaProducts(100))
      .filter((product) => `${product.name} ${product.brand}`.toLowerCase().includes(normalized))
      .slice(0, limit);
  } catch {
    // Серверный поиск ниже остаётся основным для товаров за пределами прогретой сотни.
  }
  try {
    const remote = await searchMedusaProducts(query, limit);
    return remote.length > 0 ? remote : local;
  } catch {
    return local;
  }
}

/** Реальные цены товара по аптекам (calculated_price пуст). */
export async function getPrices(productId: string): Promise<PriceInfo | null> {
  return getPharmacyPrices(productId);
}

/** База названия без фасовки (дозировка/кол-во) — для группировки вариантов одного товара. */
function baseName(name: string): string {
  return String(name)
    .replace(/№\s?\d+/gi, "")
    .replace(/\d+[.,]?\d*\s*(мкг|мг|мл|капсул|капс|таб|саше|пак|доз|шт|г|л)(?![а-яёa-z])/gi, "")
    .replace(/[.,;()]+/g, " ")
    .replace(/\s+/g, " ")
    .trim()
    .toLowerCase();
}

/** Варианты одного товара (одна база, разная фасовка), включая текущий. Пусто, если вариант один. */
export async function getVariants(product: Product): Promise<Product[]> {
  const base = baseName(product.name);
  if (!base) return [];
  const all = (await getCatalogReadBrandProducts(brandSlug(product.brand), 250)) ?? await allProducts();
  const group = all.filter((p) => p.brand === product.brand && baseName(p.name) === base);
  // текущий товар может быть за пределами первой сотни — добавляем вручную
  if (!group.some((p) => p.slug === product.slug)) group.push(product);
  group.sort((a, b) => a.name.localeCompare(b.name, "ru"));
  return group.length > 1 ? group : [];
}

export async function getRelated(product: Product, limit = 5): Promise<Product[]> {
  const localCategory = product.categorySlug
    ? await getCatalogReadCategoryProducts(product.categorySlug, 250)
    : undefined;
  const all = localCategory?.products ?? await allProducts();
  const base = baseName(product.name);
  // исключаем сам товар и его другие фасовки (они в селекторе «Фасовка», не в «похожих»)
  const notVariant = (p: Product) => p.slug !== product.slug && baseName(p.name) !== base;
  const same = all.filter((p) => notVariant(p) && p.categorySlug && p.categorySlug === product.categorySlug);
  return (same.length ? same : all.filter(notVariant)).slice(0, limit);
}

export async function getProductsByCategory(slug: string): Promise<Product[]> {
  const local = await getCatalogReadCategoryProducts(slug, 250);
  if (local) return local.products;
  const all = await allProducts();
  // Реальное дерево: товар принадлежит категории или её родителю (по handle).
  return all.filter((p) => p.categorySlug === slug || p.categoryHandles?.includes(slug));
}

/** Поиск узла дерева по handle (рекурсивно). */
function findCatNode(nodes: CatNode[], handle: string): CatNode | null {
  for (const n of nodes) {
    if (n.handle === handle) return n;
    const f = findCatNode(n.children, handle);
    if (f) return f;
  }
  return null;
}

/** Реальное дерево категорий Medusa (с защитой от падения → пустой массив). */
export async function getCatTree(): Promise<CatNode[]> {
  const local = await getCatalogReadCategoryTree();
  if (local) return local;
  try {
    return await getCategoryTree();
  } catch {
    return [];
  }
}

/** Имя категории по handle из реального дерева Medusa. */
export async function getCategoryName(handle: string): Promise<string | null> {
  return findCatNode(await getCatTree(), resolveCategoryHandle(handle))?.name ?? null;
}

/** Реальные товары категории + total `count`. Резолвит handle → id через дерево; иначе фолбэк по ключевым словам. */
export async function getCategoryProducts(handle: string, limit = 60): Promise<{ products: Product[]; count: number }> {
  const resolvedHandle = resolveCategoryHandle(handle);
  const local = await getCatalogReadCategoryProducts(resolvedHandle, limit);
  if (local) return local;
  const node = findCatNode(await getCatTree(), resolvedHandle);
  if (node) {
    try {
      return await getMedusaCategoryProducts(node.id, limit);
    } catch {
      // Fall through to the persisted real-Medusa catalogue below.
    }
  }
  const cached = await allProducts();
  const matching = cached.filter((product) => (
    product.categorySlug === resolvedHandle || product.categoryHandles?.includes(resolvedHandle)
  ));
  return { products: matching.slice(0, limit), count: matching.length };
}
