import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { getProductBySlug, getRelated, getVariants, getCategoryName } from "@/lib/api";
import { ProductDetail } from "@/components/product/ProductDetail";
import { ProductViewTracker } from "@/components/cdp/ProductViewTracker";

export async function generateMetadata({ params }: { params: Promise<{ slug: string }> }): Promise<Metadata> {
  const { slug } = await params;
  const product = await getProductBySlug(slug);
  return { title: product ? `${product.name} — ${product.brand} | Inkar` : "Товар — Inkar" };
}

export default async function ProductPage({ params }: { params: Promise<{ slug: string }> }) {
  const { slug } = await params;
  const product = await getProductBySlug(slug);
  if (!product) notFound();

  const [related, variants, categoryName] = await Promise.all([
    getRelated(product),
    getVariants(product),
    product.categorySlug ? getCategoryName(product.categorySlug) : Promise.resolve(null),
  ]);

  return (
    <>
      <ProductViewTracker productId={product.id} variantId={product.variantId} />
      <ProductDetail
        product={product}
        related={related}
        variants={variants}
        categoryName={categoryName ?? undefined}
        categorySlug={product.categorySlug || undefined}
      />
    </>
  );
}
