const CATEGORY_HANDLE_ALIASES: Readonly<Record<string, string>> = Object.freeze({
  vitamins: "bady",
  medicines: "lekarstva-i-bady",
  lekarstva: "lekarstva-i-bady",
  "medical-products": "med-pribory-i-izdeliya",
  "face-care": "kosmetika",
  "body-care": "kosmetika",
  makeup: "kosmetika",
  "mom-baby": "mama-i-malysh",
  hygiene: "gigiyena",
  sun: "zagar-i-zashita-ot-solnca",
});

const STOREFRONT_CATEGORY_ORDER = [
  "lekarstva-i-bady",
  "bady",
  "med-pribory-i-izdeliya",
  "gigiyena",
  "kosmetika",
  "linzy",
  "mama-i-malysh",
  "sport-i-fitnes",
  "intim",
  "drugoe",
  "prochie-tovary",
] as const;

const STOREFRONT_CATEGORY_RANK = new Map<string, number>(
  STOREFRONT_CATEGORY_ORDER.map((handle, index) => [handle, index]),
);

export function resolveCategoryHandle(handle: string | null | undefined): string {
  const normalized = String(handle || "").trim().toLowerCase();
  return CATEGORY_HANDLE_ALIASES[normalized] || normalized;
}

export function publicCategoryHandle(handle: string | null | undefined): string {
  const canonical = resolveCategoryHandle(handle);
  return canonical === "lekarstva-i-bady" ? "lekarstva" : canonical;
}

export function categoryDisplayName(handle: string | null | undefined, fallback: string): string {
  const canonical = resolveCategoryHandle(handle);
  if (canonical === "lekarstva-i-bady") return "Лекарства";
  if (canonical === "bady") return "БАДы";
  return fallback;
}

export function sortStorefrontCategories<T>(
  categories: readonly T[],
  getHandle: (category: T) => string | null | undefined,
): T[] {
  return categories
    .map((category, index) => ({ category, index }))
    .sort((left, right) => {
      const leftRank = STOREFRONT_CATEGORY_RANK.get(resolveCategoryHandle(getHandle(left.category))) ?? 10_000;
      const rightRank = STOREFRONT_CATEGORY_RANK.get(resolveCategoryHandle(getHandle(right.category))) ?? 10_000;
      return leftRank - rightRank || left.index - right.index;
    })
    .map(({ category }) => category);
}
