"use client";

import Link from "next/link";
import { ShoppingBag } from "lucide-react";
import { useLang } from "@/lib/i18n/LanguageContext";

export function EmptyCart({ title, text }: { title?: string; text?: string }) {
  const { t } = useLang();
  return (
    <div className="mx-auto max-w-7xl px-4 py-20 sm:px-6">
      <div className="flex flex-col items-center text-center">
        <span className="grid h-20 w-20 place-items-center rounded-3xl bg-slate-50 text-slate-300">
          <ShoppingBag className="h-10 w-10" />
        </span>
        <h1 id="cart-page-title" tabIndex={-1} className="mt-5 font-display text-2xl font-bold text-slate-900">{title ?? t("cart.empty.t")}</h1>
        <p className="mt-2 max-w-sm text-slate-500">{text ?? t("cart.empty.s")}</p>
        <Link href="/catalog" className="mt-6 inline-flex h-12 items-center rounded-xl bg-brand-600 px-6 font-semibold text-white transition hover:bg-brand-700">
          {t("common.toCatalog")}
        </Link>
      </div>
    </div>
  );
}
