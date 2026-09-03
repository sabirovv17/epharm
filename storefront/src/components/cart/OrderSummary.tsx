"use client";

import { tenge } from "@/lib/format";
import { useLang } from "@/lib/i18n/LanguageContext";
import { useAuth } from "@/lib/auth/AuthContext";

export function OrderSummary({
  subtotal,
  savings,
  count,
  cta,
}: {
  subtotal: number;
  savings: number;
  count: number;
  cta: React.ReactNode;
}) {
  const { t, plural } = useLang();
  const { user } = useAuth();
  return (
    <div className="sticky top-28 rounded-2xl bg-white p-4 sm:p-5">
      <h2 className="font-display text-xl font-semibold text-slate-900">{t("sum.title")}</h2>
      <dl className="mt-4 space-y-2.5 text-sm">
        <div className="flex justify-between">
          <dt className="text-slate-500">{count} {plural(count)}</dt>
          <dd className="font-medium text-slate-800">{tenge(subtotal + savings)}</dd>
        </div>
        {savings > 0 && (
          <div className="flex justify-between">
            <dt className="text-slate-500">{t("sum.discount")}</dt>
            <dd className="font-medium text-accent-600">−{tenge(savings)}</dd>
          </div>
        )}
      </dl>
      <div className="mt-4 flex items-end justify-between border-t border-slate-100 pt-4">
        <span className="text-slate-600">{t("sum.total")}</span>
        <span className="font-display text-xl font-bold text-slate-900">{tenge(subtotal)}</span>
      </div>
      {user?.name && user?.phone && (
        <p className="mt-1.5 text-sm font-medium text-brand-700">{t("sum.cashback")}: +{tenge(Math.round(subtotal * 0.05))} {t("sum.points")}</p>
      )}
      <div className="mt-5">{cta}</div>
    </div>
  );
}
