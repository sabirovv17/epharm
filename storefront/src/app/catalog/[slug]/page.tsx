import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { getCategories, getCategoryProducts, getCategoryName, getCatTree } from "@/lib/api";
import { CatalogView } from "@/components/catalog/CatalogView";
import { resolveCategoryHandle } from "@/lib/category-handles";

export async function generateMetadata({ params }: { params: Promise<{ slug: string }> }): Promise<Metadata> {
  const { slug } = await params;
  const categories = await getCategories();
  const name = (await getCategoryName(slug)) || categories.find((c) => c.slug === slug)?.name;
  return { title: name ? `${name} — Inkar` : "Каталог — Inkar" };
}

export default async function CategoryPage({ params }: { params: Promise<{ slug: string }> }) {
  const { slug } = await params;
  const canonicalSlug = resolveCategoryHandle(slug);
  const [{ products, count }, tree, treeName, categories] = await Promise.all([
    getCategoryProducts(canonicalSlug, 24),
    getCatTree(),
    getCategoryName(canonicalSlug),
    getCategories(),
  ]);
  // Имя: реальное дерево Medusa → фолбэк на статичный slug (vitamins/medicines…) → slug.
  const name = treeName ?? categories.find((c) => c.slug === slug)?.name ?? null;
  if (!name && products.length === 0) notFound();

  return (
    <CatalogView
      key={slug}
      products={products}
      tree={tree}
      activeHandle={canonicalSlug}
      totalCount={count}
      heading={name ?? undefined}
    />
  );
}
