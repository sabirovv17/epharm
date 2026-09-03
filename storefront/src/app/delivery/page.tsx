"use client";

import Link from "next/link";
import { Truck, Store, Package, CreditCard, Wallet, Sparkles, Clock, ShieldCheck } from "lucide-react";
import { Breadcrumbs } from "@/components/ui/Breadcrumbs";
import { useLang } from "@/lib/i18n/LanguageContext";

const delivery = [
  { icon: Truck, title: "Курьером по Алматы", time: "≈ 30 минут", text: "Бесплатно от 5 000 ₸, иначе 990 ₸. Привезём в течение дня в удобный интервал." },
  { icon: Store, title: "Самовывоз из аптеки", time: "≈ 15 минут", text: "Соберём заказ в ближайшей из 8 аптек сети. Оплата онлайн или на месте." },
  { icon: Package, title: "Почтой по Казахстану", time: "1–5 дней", text: "Доставка Казпочтой в другие города. Стоимость рассчитывается при оформлении." },
];

const payment = [
  { icon: CreditCard, title: "Картой онлайн", text: "Visa, Mastercard — безопасная оплата на сайте." },
  { icon: Wallet, title: "При получении", text: "Наличными или картой курьеру / на кассе аптеки." },
  { icon: Sparkles, title: "Баллами Saumart", text: "До 15% суммы заказа можно покрыть бонусами." },
];

export default function DeliveryPage() {
  const { t } = useLang();
  return (
    <div className="mx-auto max-w-5xl px-4 py-6 sm:px-6">
      <Breadcrumbs items={[{ label: t("common.home"), href: "/" }, { label: t("f.buyers.1") }]} />
      <h1 className="mt-4 font-display text-3xl font-extrabold tracking-tight text-slate-900">{t("f.buyers.1")}</h1>
      <p className="mt-1 text-slate-500">Три способа получить заказ и пять способов оплатить — выбирайте, что удобно.</p>

      <h2 className="mt-8 font-display text-xl font-bold text-slate-900">Доставка</h2>
      <div className="mt-4 grid gap-4 md:grid-cols-3">
        {delivery.map((d) => (
          <div key={d.title} className="rounded-3xl border border-slate-100 bg-white p-6 shadow-soft">
            <span className="grid h-12 w-12 place-items-center rounded-2xl bg-brand-50 text-brand-600"><d.icon className="h-6 w-6" /></span>
            <h3 className="mt-4 font-semibold text-slate-900">{d.title}</h3>
            <p className="mt-1 flex items-center gap-1.5 text-sm font-medium text-brand-700"><Clock className="h-4 w-4" /> {d.time}</p>
            <p className="mt-2 text-sm leading-relaxed text-slate-500">{d.text}</p>
          </div>
        ))}
      </div>

      <h2 className="mt-10 font-display text-xl font-bold text-slate-900">Оплата</h2>
      <div className="mt-4 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {payment.map((p) => (
          <div key={p.title} className="rounded-2xl border border-slate-100 bg-white p-5 shadow-soft">
            <span className="grid h-11 w-11 place-items-center rounded-xl bg-slate-50 text-slate-700"><p.icon className="h-5 w-5" /></span>
            <h3 className="mt-3 text-sm font-semibold text-slate-900">{p.title}</h3>
            <p className="mt-1 text-xs leading-relaxed text-slate-500">{p.text}</p>
          </div>
        ))}
      </div>

      <div className="mt-10 flex flex-col items-start gap-4 rounded-3xl bg-gradient-to-br from-brand-600 to-brand-800 p-6 text-white sm:flex-row sm:items-center sm:justify-between sm:p-8">
        <div className="flex items-start gap-3">
          <ShieldCheck className="mt-0.5 h-6 w-6 shrink-0" />
          <div>
            <h2 className="font-display text-lg font-bold">Рецептурные препараты</h2>
            <p className="mt-1 max-w-md text-sm text-brand-50/90">Товары со значком Rx требуют рецепта. Курьер или фармацевт проверит его при передаче заказа.</p>
          </div>
        </div>
        <Link href="/catalog" className="inline-flex h-12 shrink-0 items-center rounded-2xl bg-white px-6 font-semibold text-brand-700 transition hover:bg-brand-50">В каталог</Link>
      </div>
    </div>
  );
}
