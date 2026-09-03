"use client";

import { useState } from "react";
import Link from "next/link";
import { ArrowLeft, ArrowRight, Check, Store, Tag, Truck } from "lucide-react";
import { useCart } from "@/lib/cart/CartContext";
import { useLang } from "@/lib/i18n/LanguageContext";
import { ProductImage } from "@/components/ui/ProductImage";
import { QuantityStepper } from "@/components/ui/QuantityStepper";
import { OrderSummary } from "@/components/cart/OrderSummary";
import { EmptyCart } from "@/components/cart/EmptyCart";
import { tenge } from "@/lib/format";
import { ConfirmCartDelete } from "@/components/cart/ConfirmCartDelete";
import { useToast } from "@/lib/ui/ToastContext";

export default function CartPage() {
  const {
    items, savings, setQty, remove, restore, clear,
    isSelected, toggleSelected, selectedSubtotal, selectedCount,
  } = useCart();
  const { t } = useLang();
  const { push } = useToast();
  const [delivery, setDelivery] = useState<"courier" | "pickup">("courier");
  const [promoInput, setPromoInput] = useState("");
  const [promoCode, setPromoCode] = useState("");
  const checkoutHref = `/checkout?delivery=${delivery}${promoCode ? `&promo=${encodeURIComponent(promoCode)}` : ""}`;

  const removeItem = (product: (typeof items)[number]["product"], qty: number) => {
    const wasSelected = isSelected(product.id);
    remove(product.id);
    push(t("cart.removedToast"), {
      actionLabel: t("cart.undo"),
      duration: 6_000,
      tone: "removed",
      onAction: () => {
        restore(product, qty);
        if (!wasSelected) toggleSelected(product.id);
        push(t("cart.restoredToast"));
      },
    });
  };

  const clearCart = () => {
    const removedItems = items.map((item) => ({
      ...item,
      wasSelected: isSelected(item.product.id),
    }));
    clear();
    push(t("cart.clearedToast"), {
      actionLabel: t("cart.undo"),
      duration: 6_000,
      tone: "removed",
      onAction: () => {
        removedItems.forEach(({ product, qty, wasSelected }) => {
          restore(product, qty);
          if (!wasSelected) toggleSelected(product.id);
        });
        push(t("cart.restoredAllToast"));
      },
    });
  };

  if (items.length === 0) return <EmptyCart />;

  return (
    <div className="min-h-screen bg-[#f4f6f8] pb-[calc(7.5rem+env(safe-area-inset-bottom))] lg:pb-10">
      <div className="mx-auto max-w-5xl px-4 py-5 sm:px-6 sm:py-7">
        <Link href="/catalog" className="inline-flex min-h-11 items-center gap-2 rounded-xl px-2 text-sm font-medium text-slate-700 transition hover:bg-white hover:text-brand-700">
          <ArrowLeft className="h-4 w-4" /> {t("common.back")}
        </Link>

        <h1 id="cart-page-title" tabIndex={-1} className="mt-3 font-display text-[28px] font-bold tracking-tight text-slate-950">{t("cart.title")}</h1>

        <section aria-labelledby="cart-delivery-title" className="mt-5">
          <h2 id="cart-delivery-title" className="font-display text-xl font-semibold text-slate-900">{t("co.s2")}</h2>
          <div className="mt-3 grid gap-2">
            <DeliveryChoice
              active={delivery === "pickup"}
              onClick={() => setDelivery("pickup")}
              icon={<Store className="h-5 w-5" />}
              title={t("co.pickup")}
              description="Заберите в аптеке через 30 минут"
              price={t("common.free")}
            />
            <DeliveryChoice
              active={delivery === "courier"}
              onClick={() => setDelivery("courier")}
              icon={<Truck className="h-5 w-5" />}
              title="Доставка"
              description="Доставим за 2–3 часа"
              price="от 800 ₸"
            />
          </div>
        </section>

        <div className="mt-7 flex items-center justify-between gap-4">
          <h2 className="font-display text-2xl font-semibold text-slate-950">{t("cart.title")}</h2>
          <ConfirmCartDelete
            trigger="text"
            onConfirm={clearCart}
            label={t("cart.clear")}
            title={t("cart.clearTitle")}
            description={t("cart.clearDescription")}
            confirmLabel={t("cart.clearConfirm")}
            cancelLabel={t("cart.clearCancel")}
            focusTargetId="cart-page-title"
          />
        </div>

        <div className="mt-3 grid min-w-0 grid-cols-[minmax(0,1fr)] gap-5 lg:grid-cols-[minmax(0,1fr)_360px]">
          <div className="min-w-0 space-y-3">
            {items.map(({ product, qty }) => (
              <article key={product.id} className="flex min-w-0 gap-2 rounded-2xl bg-white p-3 sm:gap-4 sm:p-4">
                <input type="checkbox" checked={isSelected(product.id)} onChange={() => toggleSelected(product.id)} aria-label="Выбрать для заказа" className="mt-1 h-5 w-5 shrink-0 accent-brand-600" />
                <Link href={`/product/${product.slug}`} className="shrink-0 rounded-xl bg-slate-50">
                  <ProductImage product={product} className="h-20 w-20 overflow-hidden rounded-xl sm:h-24 sm:w-24" />
                </Link>
                <div className="flex min-w-0 flex-1 flex-col">
                  <div className="flex items-start justify-between gap-2">
                    <div className="min-w-0">
                      <p className="text-[11px] font-semibold uppercase tracking-wide text-slate-400">{product.brand}</p>
                      <Link href={`/product/${product.slug}`} className="line-clamp-2 text-sm font-semibold text-slate-800 transition hover:text-brand-700 sm:text-base">{product.name}</Link>
                      {product.volume && <p className="mt-0.5 text-xs text-slate-400">{product.volume}</p>}
                    </div>
                    <ConfirmCartDelete
                      onConfirm={() => removeItem(product, qty)}
                      label={t("cart.removeRequest")}
                      title={t("cart.removeTitle")}
                      description={t("cart.removeDescription")}
                      confirmLabel={t("cart.removeConfirm")}
                      cancelLabel={t("cart.removeCancel")}
                      itemName={product.name}
                      focusTargetId="cart-page-title"
                      iconSize="sm"
                    />
                  </div>
                  <div className="mt-auto flex flex-wrap items-end justify-between gap-2 pt-3">
                    <QuantityStepper qty={qty} onChange={(n) => setQty(product.id, n)} />
                    <div className="ml-auto text-right">
                      <div className="font-bold text-slate-900">{tenge(product.price * qty)}</div>
                      {product.oldPrice && <div className="text-xs text-slate-400 line-through">{tenge(product.oldPrice * qty)}</div>}
                    </div>
                  </div>
                </div>
              </article>
            ))}
          </div>

          <aside className="min-w-0">
            <div className="space-y-3">
            <div className="rounded-2xl bg-white p-4">
              <p className="flex items-center gap-1.5 text-sm font-semibold text-slate-900"><Tag className="h-4 w-4 text-brand-600" /> {t("cart.promo")}</p>
              <div className="mt-2 flex gap-2">
                <input value={promoInput} onChange={(event) => setPromoInput(event.target.value.toUpperCase())} placeholder="Промокод" className="h-11 min-w-0 flex-1 rounded-xl border border-slate-200 px-3 text-sm uppercase outline-none transition focus:border-brand-400 focus:ring-2 focus:ring-brand-100" />
                <button type="button" onClick={() => setPromoCode(promoInput.trim())} className="h-11 shrink-0 rounded-xl bg-slate-900 px-4 text-sm font-semibold text-white transition hover:bg-slate-800">{t("cart.apply")}</button>
              </div>
              {promoCode && <p className="mt-2 text-xs font-medium text-brand-700">Промокод {promoCode} будет проверен при оформлении заказа.</p>}
            </div>
            <OrderSummary
              subtotal={selectedSubtotal}
              savings={savings}
              count={selectedCount}
              cta={
                selectedCount > 0 ? (
                  <Link href={checkoutHref} className="hidden h-[52px] items-center justify-center gap-2 rounded-2xl bg-brand-600 font-semibold text-white transition hover:bg-brand-700 lg:flex">
                    {t("sum.checkout")} <ArrowRight className="h-5 w-5" />
                  </Link>
                ) : (
                  <span className="hidden h-[52px] items-center justify-center rounded-2xl bg-slate-200 font-semibold text-slate-400 lg:flex">{t("sum.checkout")}</span>
                )
              }
            />
            </div>
          </aside>
        </div>
      </div>

      <div className="fixed inset-x-0 bottom-0 z-40 border-t border-slate-200 bg-white/95 px-4 pt-3 shadow-[0_-10px_30px_-18px_rgba(15,23,42,0.35)] backdrop-blur [padding-bottom:calc(0.75rem+env(safe-area-inset-bottom))] lg:hidden">
        <div className="mx-auto max-w-md">
          {selectedCount > 0 ? (
            <Link href={checkoutHref} className="flex h-[52px] items-center justify-center gap-2 rounded-2xl bg-brand-600 px-4 text-sm font-semibold text-white transition hover:bg-brand-700">
              {t("sum.checkout")} · {tenge(selectedSubtotal)} <ArrowRight className="h-5 w-5" />
            </Link>
          ) : (
            <span className="flex h-[52px] items-center justify-center rounded-2xl bg-slate-200 px-4 text-sm font-semibold text-slate-400">{t("sum.checkout")}</span>
          )}
        </div>
      </div>
    </div>
  );
}

function DeliveryChoice({ active, onClick, icon, title, description, price }: {
  active: boolean;
  onClick: () => void;
  icon: React.ReactNode;
  title: string;
  description: string;
  price: string;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      className={`flex min-h-[68px] w-full items-center gap-3 rounded-2xl border bg-white px-4 py-3 text-left transition ${active ? "border-brand-500 ring-1 ring-brand-500" : "border-transparent hover:border-slate-200"}`}
    >
      <span className={`grid h-8 w-8 shrink-0 place-items-center rounded-xl ${active ? "bg-brand-600 text-white" : "bg-slate-100 text-slate-500"}`}>
        {active ? <Check className="h-5 w-5" /> : icon}
      </span>
      <span className="min-w-0 flex-1">
        <span className="flex items-center gap-1.5 text-sm font-semibold text-slate-900">{icon}{title}</span>
        <span className="mt-0.5 block text-xs text-slate-500">{description}</span>
      </span>
      <span className="max-w-28 shrink-0 text-right text-xs font-medium text-slate-600 sm:max-w-none sm:text-sm">{price}</span>
    </button>
  );
}
