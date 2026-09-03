"use client";

import Image from "next/image";
import { useState } from "react";
import type { Product } from "@/lib/types";
import { ProductArt } from "./ProductArt";

/**
 * Фото товара: реальный снимок (product.image) с object-contain; при ошибке/отсутствии —
 * иллюстрация-плейсхолдер ProductArt. Заменяет прямые <ProductArt/> в карточках/деталях.
 */
export function ProductImage({
  product,
  src,
  className,
  imageClassName,
  loading = "lazy",
  fetchPriority,
}: {
  product: Pick<Product, "art" | "image" | "images" | "name">;
  src?: string;
  className?: string;
  imageClassName?: string;
  loading?: "eager" | "lazy";
  fetchPriority?: "high" | "low" | "auto";
}) {
  const candidates = [...new Set([
    src,
    product.image,
    ...(product.images || []),
  ].filter(Boolean) as string[])];
  const [failed, setFailed] = useState<string[]>([]);
  const source = candidates.find((candidate) => !failed.includes(candidate));
  if (source) {
    // Next's image optimizer rejects proxy URLs with a dynamic `path` query
    // in production. The proxy itself validates and caches the image, so let
    // the browser request it directly instead of returning a broken 400.
    const canOptimize = source.startsWith("/api/uploads/");
    return (
      <div className={`relative grid place-items-center overflow-hidden bg-white ${className ?? ""}`}>
        <Image
          src={source}
          alt={product.name}
          fill
          sizes="(max-width: 640px) 50vw, (max-width: 1024px) 33vw, 25vw"
          unoptimized={!canOptimize}
          loading={loading}
          fetchPriority={fetchPriority}
          onError={() => setFailed((current) => current.includes(source) ? current : [...current, source])}
          className={`object-contain ${imageClassName ?? ""}`}
        />
      </div>
    );
  }
  return <ProductArt art={product.art} className={className} />;
}
