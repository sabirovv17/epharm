"use client";

import { useState } from "react";
import { ChevronDown, Phone, Mail, MessageCircle, Clock } from "lucide-react";
import { Breadcrumbs } from "@/components/ui/Breadcrumbs";
import { useLang } from "@/lib/i18n/LanguageContext";

const FAQ: { q: string; a: string }[] = [
  { q: "Как сделать заказ?", a: "Добавьте товары в корзину, перейдите к оформлению, укажите контакты и способ получения (курьер, самовывоз или почта) и подтвердите заказ. Регистрация не обязательна, но с аккаунтом копятся бонусы Saumart." },
  { q: "Сколько стоит доставка и за сколько привезут?", a: "Доставка курьером по Алматы — бесплатно при заказе от 5 000 ₸, обычно за 30 минут. Самовывоз из аптеки готов за 15 минут. Для рецептурных препаратов курьер проверит рецепт при передаче." },
  { q: "Какие способы оплаты?", a: "Банковская карта Visa/Mastercard онлайн, а также оплата наличными или картой при получении. Часть суммы можно покрыть бонусами Saumart (до 15% от заказа)." },
  { q: "Как работают бонусы Saumart?", a: "За каждый заказ начисляется кэшбэк баллами (3–10% в зависимости от уровня). 1 балл = 1 ₸. Баллами можно оплатить до 15% следующего заказа. Уровень растёт вместе с суммой покупок." },
  { q: "Можно ли вернуть товар?", a: "Лекарства и средства гигиены надлежащего качества возврату и обмену не подлежат (Постановление Правительства РК). Косметику и приборы можно вернуть в течение 14 дней, если они не были в использовании и сохранён чек." },
  { q: "Нужен ли рецепт?", a: "Рецептурные препараты помечены значком Rx. При доставке курьеру или на кассе самовывоза нужно предъявить действующий рецепт от врача — без него такие препараты не отпускаются." },
  { q: "Товара нет в наличии — что делать?", a: "Нажмите «Сообщить о поступлении» на карточке товара или спросите AI-консультанта Saumi — он подберёт аналог с тем же действующим веществом из наличия ближайших аптек." },
];

export default function HelpPage() {
  const { t } = useLang();
  const [open, setOpen] = useState(0);

  return (
    <div className="mx-auto max-w-3xl px-4 py-6 sm:px-6">
      <Breadcrumbs items={[{ label: t("common.home"), href: "/" }, { label: t("top.help") }]} />
      <h1 className="mt-4 font-display text-3xl font-extrabold tracking-tight text-slate-900">{t("top.help")}</h1>
      <p className="mt-1 text-slate-500">Частые вопросы о заказах, доставке, оплате и бонусах. Не нашли ответ — напишите нам.</p>

      <div className="mt-6 space-y-2.5">
        {FAQ.map((item, i) => {
          const on = i === open;
          return (
            <div key={i} className="overflow-hidden rounded-2xl border border-slate-100 bg-white shadow-soft">
              <button onClick={() => setOpen(on ? -1 : i)} className="flex w-full items-center justify-between gap-3 px-5 py-4 text-left">
                <span className="font-semibold text-slate-900">{item.q}</span>
                <ChevronDown className={`h-5 w-5 shrink-0 text-slate-400 transition-transform ${on ? "rotate-180" : ""}`} />
              </button>
              {on && <p className="px-5 pb-5 text-sm leading-relaxed text-slate-600">{item.a}</p>}
            </div>
          );
        })}
      </div>

      <div className="mt-8 rounded-3xl bg-gradient-to-br from-brand-600 to-brand-800 p-6 text-white sm:p-8">
        <h2 className="font-display text-xl font-bold">Не нашли ответ?</h2>
        <p className="mt-1 text-brand-50/90">Служба заботы на связи ежедневно с 08:00 до 23:00.</p>
        <div className="mt-5 grid gap-3 sm:grid-cols-3">
          <a href="tel:+77000000000" className="flex items-center gap-2.5 rounded-2xl bg-white/10 px-4 py-3 transition hover:bg-white/20">
            <Phone className="h-5 w-5" /> <span className="text-sm font-semibold">+7 700 000 00 00</span>
          </a>
          <a href="mailto:help@darihana.kz" className="flex items-center gap-2.5 rounded-2xl bg-white/10 px-4 py-3 transition hover:bg-white/20">
            <Mail className="h-5 w-5" /> <span className="text-sm font-semibold">help@darihana.kz</span>
          </a>
          <div className="flex items-center gap-2.5 rounded-2xl bg-white/10 px-4 py-3">
            <MessageCircle className="h-5 w-5" /> <span className="text-sm font-semibold">Чат в приложении</span>
          </div>
        </div>
        <p className="mt-4 flex items-center gap-1.5 text-xs text-brand-50/80"><Clock className="h-3.5 w-3.5" /> Среднее время ответа — 3 минуты</p>
      </div>
    </div>
  );
}
