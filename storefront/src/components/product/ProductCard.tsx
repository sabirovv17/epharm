"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { Heart, ShoppingBag, Check } from "lucide-react";
import type { Product } from "@/lib/types";
import { cn } from "@/lib/cn";
import { tenge, discountPercent } from "@/lib/format";
import { ProductImage } from "@/components/ui/ProductImage";
import { Stars } from "@/components/ui/Stars";
import { useCart } from "@/lib/cart/CartContext";
import { useFavorites } from "@/lib/favorites/FavoritesContext";
import { useLang } from "@/lib/i18n/LanguageContext";
import { usePrice } from "@/lib/price/usePrice";

/** Карточка товара в стиле Sephora: монохром, бренд капсом, изображение на белом,
 *  hover-кнопка «В корзину» поверх фото, красная цена со скидкой. */
export function ProductCard({ product, boxed = false }: { product: Product; boxed?: boolean }) {
  const { add } = useCart();
  const { has, toggle } = useFavorites();
  const { t } = useLang();
  const fav = has(product.id);
  const [added, setAdded] = useState(false);
  const cardRef = useRef<HTMLElement>(null);
  const [priceInView, setPriceInView] = useState(!product.priceTBD);
  const sale = discountPercent(product.price, product.oldPrice);
  const href = `/product/${product.slug}`;
  const showBrand = Boolean(product.brand) && !["-", "—", ""].includes(product.brand.trim());
  // Реальная цена «от X ₸» по аптекам (calculated_price пуст) — лениво, как в приложении.
  useEffect(() => {
    if (!product.priceTBD) return;
    const node = cardRef.current;
    if (!node || !("IntersectionObserver" in window)) {
      setPriceInView(true);
      return;
    }
    const observer = new IntersectionObserver(([entry]) => {
      if (entry.isIntersecting) {
        setPriceInView(true);
        observer.disconnect();
      }
    }, { rootMargin: "100px" });
    observer.observe(node);
    return () => observer.disconnect();
  }, [product.id, product.priceTBD]);

  const lazyMin = usePrice(product.id, Boolean(product.priceTBD && priceInView));
  const buyPrice: number | null = product.priceTBD ? (typeof lazyMin === "number" ? lazyMin : null) : product.price;
  const available = product.priceTBD ? typeof lazyMin === "number" : product.inStock;

  const handleAdd = (e: React.MouseEvent) => {
    e.preventDefault();
    e.stopPropagation();
    if (buyPrice == null) return; // без цены не кладём в корзину (иначе заказ на 0 ₸)
    add(product.priceTBD ? { ...product, price: buyPrice, priceTBD: false } : product);
    setAdded(true);
    setTimeout(() => setAdded(false), 1200);
  };

  return (
    <article ref={cardRef} className={cn("group relative flex flex-col", boxed && "rounded-2xl border border-slate-200 bg-white p-2.5 transition-shadow duration-200 hover:shadow-card")}>
      <div className={cn("relative aspect-[3/4] overflow-hidden bg-white", boxed && "rounded-lg")}>
        <Link href={href} className="absolute inset-0" aria-label={product.name}>
          <ProductImage
            product={product}
            className="h-full w-full"
            imageClassName="p-3 transition-transform duration-500 ease-[cubic-bezier(.22,1,.36,1)] group-hover:scale-[1.03]"
          />
        </Link>

        {/* Флаги — минималистичные уппер-кейс плашки в духе Sephora */}
        <div className="pointer-events-none absolute left-0 top-2.5 flex flex-col items-start gap-1">
          {sale != null && (
            <span className="bg-accent-500 px-1.5 py-0.5 text-[11px] font-bold leading-none text-white">−{sale}%</span>
          )}
          {product.badges.includes("new") && (
            <span className="bg-slate-900 px-1.5 py-1 text-[9px] font-bold uppercase leading-none tracking-[0.12em] text-white">{t("badge.new")}</span>
          )}
          {product.badges.includes("hit") && (
            <span className="border border-slate-900 bg-white px-1.5 py-1 text-[9px] font-bold uppercase leading-none tracking-[0.12em] text-slate-900">{t("badge.hit")}</span>
          )}
          {product.badges.includes("rx") && (
            <span className="border border-accent-500 bg-white px-1.5 py-1 text-[9px] font-bold uppercase leading-none tracking-[0.12em] text-accent-600">{t("badge.rx")}</span>
          )}
        </div>

        <button
          type="button"
          onClick={(e) => { e.preventDefault(); e.stopPropagation(); toggle(product.id); }}
          aria-label={fav ? "Убрать из избранного" : "В избранное"}
          aria-pressed={fav}
          className="absolute right-1.5 top-1.5 z-10 grid h-8 w-8 place-items-center rounded-full text-slate-500 transition hover:bg-slate-50"
        >
          <Heart className={cn("h-[18px] w-[18px] transition", fav && "fill-slate-900 text-slate-900")} />
        </button>

        {/* Sephora-приём: «В корзину» выезжает поверх фото при наведении (на тач — всегда видна) */}
        {available && product.variantId && !product.prescription && (
          <button
            type="button"
            onClick={handleAdd}
            aria-label={t("pdp.addToCart")}
            className={cn(
              "absolute inset-x-2 bottom-2 z-10 flex h-10 items-center justify-center gap-2 text-[11px] font-semibold uppercase tracking-[0.1em] transition-all duration-200 ease-[cubic-bezier(.22,1,.36,1)]",
              "opacity-100 sm:translate-y-1.5 sm:opacity-0 sm:group-hover:translate-y-0 sm:group-hover:opacity-100",
              added ? "bg-brand-700 text-white" : "bg-slate-900 text-white hover:bg-slate-800",
            )}
          >
            {added ? <Check className="h-4 w-4" /> : <><ShoppingBag className="h-4 w-4" /> {t("pdp.addToCart")}</>}
          </button>
        )}
      </div>

      <div className="mt-3 flex flex-1 flex-col">
        {showBrand && (
          <p className="line-clamp-1 text-[11px] font-bold uppercase tracking-[0.08em] text-slate-900">{product.brand}</p>
        )}
        <h3 className={cn("min-h-9 text-[13px] leading-snug text-slate-600 line-clamp-2", showBrand && "mt-0.5")}>
          <Link href={href} className="transition-colors hover:text-slate-900">{product.name}</Link>
        </h3>
        {product.volume && <p className="mt-0.5 text-xs text-slate-400">{product.volume}</p>}

        {product.reviews > 0 && (
          <div className="mt-1.5 flex items-center gap-1.5">
            <Stars value={product.rating} />
            <span className="text-xs text-slate-400">({product.reviews})</span>
          </div>
        )}

        <div className="mt-2 flex items-baseline gap-2">
          {product.priceTBD ? (
            typeof lazyMin === "number" ? (
              <span className="text-[15px] font-bold tracking-tight text-slate-900">{t("card.from")} {tenge(lazyMin)}</span>
            ) : (
              <span className="text-sm font-semibold text-slate-500">{t("card.priceTBD")}</span>
            )
          ) : (
            <>
              <span className={cn("text-[15px] font-bold tracking-tight", sale != null ? "text-accent-600" : "text-slate-900")}>{tenge(product.price)}</span>
              {product.oldPrice && <span className="text-xs text-slate-400 line-through">{tenge(product.oldPrice)}</span>}
            </>
          )}
        </div>

        {product.prescription && <p className="mt-1 flex items-center gap-1 text-xs font-medium text-amber-600">{t("card.rxOnly")}</p>}
        {!available && lazyMin !== undefined && <p className="mt-1 text-xs font-medium text-slate-400">{t("card.out")}</p>}
      </div>
    </article>
  );
}
