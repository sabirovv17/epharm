"use client";

import Link from "next/link";
import { Leaf, ShieldCheck, Truck, HeartHandshake, Sparkles } from "lucide-react";
import { Breadcrumbs } from "@/components/ui/Breadcrumbs";
import { useLang } from "@/lib/i18n/LanguageContext";

const stats = [
  { big: "8", label: "аптек в Алматы" },
  { big: "30 мин", label: "средняя доставка" },
  { big: "10 000+", label: "товаров в каталоге" },
  { big: "4.9", label: "рейтинг покупателей" },
];

const values = [
  { icon: ShieldCheck, title: "Только оригинал", text: "Прямые поставки от производителей и сертификаты на каждую партию." },
  { icon: Leaf, title: "Забота, а не продажа", text: "AI-фармацевт Saumi и живые специалисты подбирают то, что действительно нужно." },
  { icon: Truck, title: "Быстро и удобно", text: "Доставка за 30 минут, самовывоз за 15 и честные цены без накруток." },
  { icon: HeartHandshake, title: "Бонусы Saumart", text: "Кэшбэк до 10% и уровни лояльности — выгодно возвращаться." },
];

export default function AboutPage() {
  const { t } = useLang();
  return (
    <div className="mx-auto max-w-5xl px-4 py-6 sm:px-6">
      <Breadcrumbs items={[{ label: t("common.home"), href: "/" }, { label: t("f.company.1") }]} />

      <div className="mt-6 overflow-hidden rounded-3xl bg-gradient-to-br from-brand-600 to-brand-800 p-8 text-white sm:p-12">
        <span className="inline-flex items-center gap-2 rounded-full bg-white/15 px-3.5 py-1.5 text-sm font-medium"><Sparkles className="h-4 w-4" /> Аптека со склада</span>
        <h1 className="mt-4 max-w-2xl font-display text-3xl font-extrabold leading-tight sm:text-4xl">Аптека, которая экономит ваше время и заботится о здоровье</h1>
        <p className="mt-4 max-w-xl text-brand-50/90">Мы убрали лишние звенья между складом и покупателем: честные цены «со склада», быстрая доставка и персональный подбор. Здоровье — это просто.</p>
      </div>

      <div className="mt-6 grid grid-cols-2 gap-4 lg:grid-cols-4">
        {stats.map((s) => (
          <div key={s.label} className="rounded-2xl border border-slate-100 bg-white p-5 text-center shadow-soft">
            <p className="font-display text-2xl font-extrabold text-brand-700">{s.big}</p>
            <p className="mt-1 text-xs font-medium text-slate-500">{s.label}</p>
          </div>
        ))}
      </div>

      <h2 className="mt-10 font-display text-2xl font-bold text-slate-900">Во что мы верим</h2>
      <div className="mt-4 grid gap-4 sm:grid-cols-2">
        {values.map((v) => (
          <div key={v.title} className="flex items-start gap-4 rounded-2xl border border-slate-100 bg-white p-6 shadow-soft">
            <span className="grid h-12 w-12 shrink-0 place-items-center rounded-2xl bg-brand-50 text-brand-600"><v.icon className="h-6 w-6" /></span>
            <div>
              <h3 className="font-semibold text-slate-900">{v.title}</h3>
              <p className="mt-1 text-sm leading-relaxed text-slate-500">{v.text}</p>
            </div>
          </div>
        ))}
      </div>

      <div className="mt-10 flex flex-col items-start gap-4 rounded-3xl border border-slate-100 bg-white p-6 shadow-soft sm:flex-row sm:items-center sm:justify-between sm:p-8">
        <div>
          <h2 className="font-display text-xl font-bold text-slate-900">Готовы попробовать?</h2>
          <p className="mt-1 text-slate-500">Соберите заказ — привезём за 30 минут.</p>
        </div>
        <div className="flex gap-3">
          <Link href="/catalog" className="inline-flex h-12 items-center rounded-2xl bg-brand-600 px-6 font-semibold text-white transition hover:bg-brand-700">В каталог</Link>
          <Link href="/pharmacies" className="inline-flex h-12 items-center rounded-2xl border border-slate-200 px-6 font-semibold text-slate-700 transition hover:border-brand-300">Наши аптеки</Link>
        </div>
      </div>
    </div>
  );
}
