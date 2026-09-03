"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { ArrowLeft, Truck, Store, CreditCard, Wallet, CheckCircle2, MapPin, Sparkles, Tag } from "lucide-react";
import { useCart } from "@/lib/cart/CartContext";
import { useContent, type Promo } from "@/lib/content/ContentContext";
import { useAuth } from "@/lib/auth/AuthContext";
import { useToast } from "@/lib/ui/ToastContext";
import { useLang } from "@/lib/i18n/LanguageContext";
import { EmptyCart } from "@/components/cart/EmptyCart";
import { tenge } from "@/lib/format";
import { cn } from "@/lib/cn";
import { formatPhone } from "@/lib/phone";
import { pharmaciesForCity } from "@/lib/pharmacies";
import { PharmacyMapPicker } from "@/components/checkout/PharmacyMapPicker";
import { usePush } from "@/components/push/PushProvider";

const inputCls =
  "h-12 w-full rounded-xl border border-transparent bg-slate-50 px-4 text-sm text-slate-800 outline-none transition placeholder:text-slate-400 focus:border-brand-400 focus:bg-white focus:ring-2 focus:ring-brand-100";
const AUTH_REQUIRED_MESSAGE = "Войдите или зарегистрируйтесь, чтобы оформить заказ.";

type CheckoutQuote = {
  id: string;
  total: number;
  currency: "KZT";
  expiresAt: string;
  pharmacy: { id: string; name: string; city: string; address?: string } | null;
};

// Точки самовывоза — из общего списка аптек (almatyPharmacies); выбор на карте (PharmacyMapPicker).

// Промокод окончательно проверяет Meduza при создании корзины.

// Подсказки адреса — частые улицы/районы Алматы для умного поиска при вводе адреса.
const ALMATY_STREETS = [
  "пр. Абая", "пр. Достык", "пр. Аль-Фараби", "пр. Райымбека", "пр. Назарбаева",
  "пр. Сейфуллина", "пр. Гагарина", "пр. Абылай хана", "пр. Суюнбая",
  "ул. Толе би", "ул. Жандосова", "ул. Розыбакиева", "ул. Сатпаева", "ул. Богенбай батыра",
  "ул. Тимирязева", "ул. Шевченко", "ул. Жарокова", "ул. Майлина", "ул. Кабанбай батыра",
  "ул. Брусиловского", "ул. Ауэзова", "ул. Макатаева", "ул. Маметова", "ул. Пушкина",
  "ул. Гоголя", "ул. Байтурсынова", "ул. Муратбаева", "ул. Желтоксан", "ул. Курмангазы",
  "ул. Кунаева", "ул. Зенкова", "ул. Утеген батыра", "ул. Саина", "ул. Момышулы",
  "мкр. Самал-2", "мкр. Орбита-3", "мкр. Коктем-2", "мкр. Айнабулак-3", "мкр. Аксай-3",
  "мкр. Жетысу-2", "мкр. Таугуль", "мкр. Мамыр", "мкр. Казахфильм", "мкр. Дубок-2",
];

export default function CheckoutPage() {
  const { selectedItems, selectedSubtotal, selectedCount, removeSelected } = useCart();
  const { addOrder } = useContent();
  const { user, openLogin } = useAuth();
  const { push } = useToast();
  const { t, plural } = useLang();
  const { sendEvent } = usePush();
  const isDemo = user?.demo === true;

  const [delivery, setDelivery] = useState<"courier" | "pickup">("courier");
  const [pharmacy, setPharmacy] = useState(0); // индекс выбранной аптеки самовывоза (almatyPharmacies)
  const [mapOpen, setMapOpen] = useState(false);
  const [promo, setPromo] = useState<Promo | null>(null);
  const [quote, setQuote] = useState<CheckoutQuote | null>(null);
  const [quoteLoading, setQuoteLoading] = useState(false);
  const [quoteError, setQuoteError] = useState("");
  const [quoteRefresh, setQuoteRefresh] = useState(0);
  const [placed, setPlaced] = useState<string | null>(null);
  const [placedCode, setPlacedCode] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState("");
  const [addr, setAddr] = useState("");
  const [city, setCity] = useState("Алматы");
  const [addrFocus, setAddrFocus] = useState(false);
  const [phoneInput, setPhoneInput] = useState("");
  const [phoneEdited, setPhoneEdited] = useState(false);
  const phoneRef = useRef<HTMLInputElement>(null);
  const nameRef = useRef<HTMLInputElement>(null);
  const addressRef = useRef<HTMLInputElement>(null);
  const commentRef = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const requested = params.get("delivery");
    const requestedPromo = params.get("promo")?.trim().toUpperCase();
    if (requested === "pickup") {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- hydrate the explicit cart delivery choice after mount
      setDelivery(requested);
    }
    if (requestedPromo) {
      setPromo({ id: requestedPromo, code: requestedPromo, type: "amount", value: 0, note: "Проверяется сервером" });
    }
  }, []);

  const cityPharmacies = pharmaciesForCity(city);
  const pharmIdx = cityPharmacies.length ? Math.min(pharmacy, cityPharmacies.length - 1) : 0;
  const selectedPharmacy = cityPharmacies[pharmIdx];
  const fulfillment = delivery === "pickup" ? "pickup" : "warehouse";
  const payment: "card" | "cash" = delivery === "pickup" ? "cash" : "card";
  const preferredPharmacyAddress = fulfillment === "pickup" ? selectedPharmacy?.address || "" : "";
  const preferredPharmacyCity = fulfillment === "warehouse" ? "" : selectedPharmacy?.city || city;

  /* eslint-disable react-hooks/set-state-in-effect -- network quote state is intentionally reset when cart/fulfillment changes */
  useEffect(() => {
    const items = selectedItems
      .filter((item) => item.product.variantId)
      .map((item) => ({ productId: item.product.id, variantId: item.product.variantId!, quantity: item.qty }));
    if (!items.length || (delivery === "pickup" && !preferredPharmacyAddress)) {
      return;
    }
    const controller = new AbortController();
    setQuote(null);
    setQuoteLoading(true);
    setQuoteError("");
    fetch("/api/checkout/quote", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        items,
        fulfillment,
        preferredPharmacy: fulfillment !== "warehouse"
          ? { address: preferredPharmacyAddress, city: preferredPharmacyCity }
          : null,
      }),
      signal: controller.signal,
    })
      .then(async (response) => {
        const data = await response.json().catch(() => ({}));
        if (!response.ok || !data?.quote) throw new Error(String(data?.error || "quote_unavailable"));
        setQuote(data.quote as CheckoutQuote);
      })
      .catch((error) => {
        if (controller.signal.aborted) return;
        setQuote(null);
        setQuoteError(error instanceof Error ? error.message : "quote_unavailable");
      })
      .finally(() => {
        if (!controller.signal.aborted) setQuoteLoading(false);
      });
    return () => controller.abort();
  }, [selectedItems, delivery, fulfillment, preferredPharmacyAddress, preferredPharmacyCity, quoteRefresh]);
  /* eslint-enable react-hooks/set-state-in-effect */

  useEffect(() => {
    if (!quote) return;
    const delay = Math.max(1_000, new Date(quote.expiresAt).getTime() - Date.now() - 15_000);
    const timer = window.setTimeout(() => setQuoteRefresh((value) => value + 1), delay);
    return () => window.clearTimeout(timer);
  }, [quote]);

  if (placed) return <Success order={placed} code={placedCode} delivery={delivery} />;
  if (selectedCount === 0) return <EmptyCart text={t("cart.checkoutEmpty.s")} />;

  const quotedFallback = selectedSubtotal;
  const goods = quote?.total ?? quotedFallback;
  const balance = user?.bonus ?? 0;
  const total = goods;
  // Умный поиск адреса: подсказки улиц Алматы по мере ввода (до первой цифры — номера дома).
  const addrQuery = addr.trim().toLowerCase();
  const addrSuggestions = addrFocus && addrQuery && !/\d/.test(addrQuery)
    ? ALMATY_STREETS.filter((s) => s.toLowerCase().includes(addrQuery)).slice(0, 5)
    : [];
  const displayedPhone = phoneEdited ? phoneInput : formatPhone(user?.phone ?? "");
  const phoneReady = displayedPhone.replace(/\D/g, "").length >= 11;
  const destinationReady = delivery === "pickup" ? cityPharmacies.length > 0 : addr.trim().length > 0;
  const formActionable = phoneReady && destinationReady && !!quote && !quoteLoading && !quoteError;
  const showMobileCta = !user || formActionable;
  const visibleFormError = user && formError === AUTH_REQUIRED_MESSAGE ? "" : formError;
  const quoteErrorText = quoteError === "selected_pharmacy_unavailable"
    ? "В выбранной аптеке сейчас нельзя собрать всю корзину. Выберите другую аптеку."
    : quoteError === "no_common_pharmacy"
      ? "Не нашли одну аптеку, где есть все выбранные товары. Измените корзину или выберите доставку со склада."
      : quoteError
        ? "Не удалось получить актуальные цены и остатки. Попробуйте ещё раз."
        : "";

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (submitting) return;
    setFormError("");
    if (!user) {
      setFormError(AUTH_REQUIRED_MESSAGE);
      push(AUTH_REQUIRED_MESSAGE);
      openLogin();
      return;
    }
    // Обязателен только телефон; имя — необязательно; адрес — для курьера/почты.
    const phoneDigits = (phoneRef.current?.value ?? "").replace(/\D/g, "");
    if (phoneDigits.length < 11) {
      const message = "Введите номер телефона";
      setFormError(message);
      push(message);
      phoneRef.current?.scrollIntoView({ behavior: "smooth", block: "center" });
      phoneRef.current?.focus();
      return;
    }
    if (delivery !== "pickup" && !addr.trim()) {
      const message = "Укажите адрес доставки";
      setFormError(message);
      push(message);
      addressRef.current?.scrollIntoView({ behavior: "smooth", block: "center" });
      addressRef.current?.focus();
      return;
    }
    if (!quote || quoteLoading || quoteError) {
      const message = "Не удалось подтвердить актуальные цены. Обновите котировку и попробуйте ещё раз.";
      setFormError(message);
      push(message);
      return;
    }
    setSubmitting(true);
    try {
      const order = await addOrder({
        sum: total,
        items: selectedCount,
        delivery,
        payment: isDemo ? "cash" : payment,
        name: nameRef.current?.value || user?.name || "Покупатель",
        phone: phoneDigits,
        email: user?.email,
        address: delivery === "pickup" ? (cityPharmacies[pharmIdx]?.address || city) : addr,
        city,
        quoteId: quote.id,
        fulfillment,
        pharmacyId: quote.pharmacy?.id,
        pharmacyName: quote.pharmacy?.name,
        pharmacyAddress: quote.pharmacy?.address,
        promoCode: promo?.code,
        comment: commentRef.current?.value || "",
        cartItems: selectedItems
          .filter((item) => item.product.variantId)
          .map((item) => ({ productId: item.product.id, variantId: item.product.variantId!, quantity: item.qty })),
      });
      await sendEvent("order.created", {
        orderId: order.id,
        orderNumber: order.n,
        code: order.code || "",
        url: "/account/orders",
      }).catch(() => {});
      removeSelected();
      setPlacedCode(order.code || "");
      setPlaced("INK-" + order.n);
      window.scrollTo({ top: 0 });
    } catch (error) {
      if (error instanceof Error && error.message === "payment_redirect") return;
      if (error instanceof Error && error.message === "demo_session_required") {
        setFormError(AUTH_REQUIRED_MESSAGE);
        push(AUTH_REQUIRED_MESSAGE);
        openLogin();
      } else if (error instanceof Error && /quote_(invalid_or_expired|required)/.test(error.message)) {
        setQuote(null);
        setQuoteRefresh((value) => value + 1);
        setFormError("Цены или остатки изменились. Мы обновляем расчёт — подтвердите заказ ещё раз.");
        push("Расчёт заказа обновляется");
      } else {
        push("Не удалось оформить заказ. Попробуйте ещё раз.");
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <form
      onSubmit={submit}
      className={cn(
        "mx-auto min-h-screen max-w-5xl bg-[#f4f6f8] px-4 pt-5 shadow-[0_0_0_100vmax_#f4f6f8] [clip-path:inset(0_-100vmax)] sm:px-6 sm:pt-7",
        showMobileCta ? "pb-[calc(7rem+env(safe-area-inset-bottom))] lg:pb-6" : "pb-6",
      )}
    >
      <Link href="/cart" className="inline-flex min-h-11 items-center gap-2 rounded-xl px-2 text-sm font-medium text-slate-700 transition hover:bg-white hover:text-brand-700">
        <ArrowLeft className="h-4 w-4" /> {t("common.back")}
      </Link>
      <h1 className="mt-3 font-display text-[28px] font-bold tracking-tight text-slate-950">{t("co.title")}</h1>
      {visibleFormError && (
        <div role="alert" className="mt-4 rounded-2xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm font-semibold text-amber-800">
          {visibleFormError}
        </div>
      )}

      <div className="mt-5 grid min-w-0 gap-5 lg:grid-cols-[minmax(0,1fr)_350px]">
        <div className="min-w-0 space-y-3">
          <Section title={t("co.s1")}>
            <div className="grid gap-3 sm:grid-cols-2">
              <input ref={nameRef} defaultValue={user?.name && user.name !== "Покупатель" ? user.name : ""} placeholder={t("co.name")} className={inputCls} />
              <input
                ref={phoneRef}
                type="tel"
                inputMode="tel"
                autoComplete="tel"
                value={displayedPhone}
                onChange={(e) => {
                  setPhoneEdited(true);
                  setPhoneInput(formatPhone(e.currentTarget.value));
                }}
                placeholder="+7 (___) ___-__-__"
                className={inputCls}
              />
            </div>
          </Section>

          <Section title={t("co.s2")}>
            <div className="grid gap-3 sm:grid-cols-2">
              <RadioCard active={delivery === "courier"} onClick={() => setDelivery("courier")} icon={<Truck className="h-5 w-5" />} title="Доставка" text="Яндекс Go, 2–3 часа" />
              <RadioCard active={delivery === "pickup"} onClick={() => setDelivery("pickup")} icon={<Store className="h-5 w-5" />} title={t("co.pickup")} text="Из аптеки через 30 минут" />
            </div>
            <div className="mt-3 space-y-2">
              <input value={city} onChange={(e) => setCity(e.target.value)} placeholder={t("co.city")} className={inputCls} />
              {delivery === "pickup" ? (
                <div className="space-y-2">
                  <div className="flex items-center gap-3 rounded-xl border border-brand-500 bg-brand-50/60 p-3 ring-1 ring-brand-500">
                    <span className="grid h-10 w-10 shrink-0 place-items-center rounded-lg bg-brand-50 text-brand-600"><Store className="h-5 w-5" /></span>
                    <span className="min-w-0 flex-1">
                      <span className="block text-sm font-semibold text-slate-900">{cityPharmacies[pharmIdx]?.address ?? "—"}</span>
                      <span className="block text-xs text-slate-500">{cityPharmacies[pharmIdx]?.hours ?? ""}</span>
                    </span>
                  </div>
                  <button type="button" onClick={() => setMapOpen(true)}
                    className="flex h-11 w-full items-center justify-center gap-2 rounded-xl border border-brand-500 font-semibold text-brand-700 transition hover:bg-brand-50">
                    <MapPin className="h-5 w-5" /> Выбрать аптеку на карте
                  </button>
                </div>
              ) : (
                <div className="relative">
                  <input ref={addressRef} value={addr} onChange={(e) => setAddr(e.target.value)} onFocus={() => setAddrFocus(true)} onBlur={() => setTimeout(() => setAddrFocus(false), 150)} placeholder={t("co.address")} autoComplete="off" className={inputCls} />
                  {addrSuggestions.length > 0 && (
                    <ul className="absolute z-20 mt-1 max-h-64 w-full overflow-auto rounded-xl border border-slate-200 bg-white py-1 shadow-lg">
                      {addrSuggestions.map((s) => (
                        <li key={s}>
                          <button type="button" onMouseDown={(e) => e.preventDefault()} onClick={() => { setAddr(s + ", "); setAddrFocus(false); }}
                            className="flex min-h-11 w-full items-center gap-2 px-3 py-2 text-left text-sm text-slate-700 transition hover:bg-slate-50">
                            <MapPin className="h-4 w-4 shrink-0 text-brand-600" /><span className="flex-1 truncate">{s}</span>
                          </button>
                        </li>
                      ))}
                    </ul>
                  )}
                </div>
              )}
            </div>
          </Section>

          <PharmacyMapPicker
            open={mapOpen}
            city={city}
            initialIndex={pharmIdx}
            onClose={() => setMapOpen(false)}
            onPick={(_, i) => setPharmacy(i)}
          />

          <Section title={t("co.s3")}>
            <div className="grid gap-3 sm:grid-cols-2">
              {delivery === "courier" && !isDemo ? (
                <RadioCard active onClick={() => {}} icon={<CreditCard className="h-5 w-5" />} title="Онлайн-оплата" text="100% предоплата картой" />
              ) : (
                <RadioCard active onClick={() => {}} icon={<Wallet className="h-5 w-5" />} title={isDemo ? "Демо-заказ" : "Оплата в аптеке"} text={isDemo ? "Без списания денег" : "При получении заказа"} />
              )}
            </div>
            {!isDemo && delivery === "courier" && (
              <p className="mt-3 rounded-xl border border-brand-100 bg-brand-50/60 p-3 text-sm text-brand-800">
                После создания заказа откроется защищённая страница подключённого платёжного провайдера.
              </p>
            )}
          </Section>

          <Section title={t("co.s4")}>
            <textarea ref={commentRef} rows={3} placeholder={t("co.comment")} className={cn(inputCls, "h-auto py-3")} />
          </Section>
        </div>

        <aside className="min-w-0">
          <div className="sticky top-28 space-y-4">
            <div className="rounded-2xl bg-white p-4">
              <p className="flex items-center gap-1.5 text-sm font-semibold text-slate-900"><Tag className="h-4 w-4 text-brand-600" /> {t("cart.promo")}</p>
              {promo ? <p className="mt-2 text-sm font-medium text-brand-700">{promo.code} · будет проверен при подтверждении</p> : <Link href="/cart" className="mt-2 inline-flex text-sm font-semibold text-brand-700 hover:underline">Добавить в корзине</Link>}
            </div>

            <div className="rounded-2xl bg-white p-4">
              <span className="flex items-center gap-1.5 text-sm font-semibold text-slate-900"><Sparkles className="h-4 w-4 text-brand-600" /> Бонусный баланс</span>
              <p className="mt-2 text-xs text-slate-500">Доступно {balance.toLocaleString("ru-RU")} {t("sum.points")}.</p>
              <p className="mt-2 rounded-xl bg-amber-50 px-3 py-2 text-xs font-medium text-amber-800">Списание бонусов временно недоступно до завершения серверной синхронизации программы лояльности.</p>
            </div>

            <div className="rounded-2xl bg-white p-5">
              <h2 className="font-display text-xl font-semibold text-slate-900">{t("sum.title")}</h2>
              <dl className="mt-4 space-y-2.5 text-sm">
                <Row label={`${selectedCount} ${plural(selectedCount)}`} value={tenge(goods)} />
                <Row label="Доставка" value={delivery === "pickup" ? "Бесплатно" : "от 800 ₸"} />
              </dl>
              <div className="mt-4 flex items-end justify-between border-t border-slate-100 pt-4">
                <span className="text-slate-600">{t("sum.total")}</span>
                <span className="font-display text-xl font-bold text-slate-900">{tenge(total)}</span>
              </div>
              {quoteLoading && <p className="mt-2 text-xs text-slate-500">Проверяем цены и остатки…</p>}
              {quote?.pharmacy && <p className="mt-2 text-xs text-brand-700">Заказ соберёт: {quote.pharmacy.name}{quote.pharmacy.address ? `, ${quote.pharmacy.address}` : ""}</p>}
              {quoteErrorText && (
                <div role="alert" className="mt-3 rounded-xl border border-amber-200 bg-amber-50 p-3 text-xs text-amber-800">
                  <p>{quoteErrorText}</p>
                  <button type="button" onClick={() => setQuoteRefresh((value) => value + 1)} className="mt-2 font-semibold underline underline-offset-2">Проверить ещё раз</button>
                </div>
              )}
              <button type="submit" disabled={submitting || (!!user && !formActionable)} className="mt-5 hidden h-[52px] w-full rounded-2xl bg-brand-600 font-semibold text-white transition hover:bg-brand-700 disabled:cursor-not-allowed disabled:opacity-60 lg:block">{!user ? "Войти или зарегистрироваться" : submitting ? "Оформляем…" : quoteLoading ? "Проверяем цены…" : t("co.confirm")}</button>
            </div>
            <p className="px-2 text-center text-xs text-slate-400">{t("co.consent")}</p>
          </div>
        </aside>
      </div>

      {showMobileCta && (
        <div
          className="fixed inset-x-0 z-40 border-t border-slate-200 bg-white/95 px-4 pt-3 shadow-[0_-10px_30px_-18px_rgba(15,23,42,0.35)] backdrop-blur [padding-bottom:calc(0.75rem+env(safe-area-inset-bottom))] lg:hidden"
          style={{ bottom: "env(keyboard-inset-height, 0px)" }}
        >
          <div className="mx-auto max-w-md">
            <button
              type="submit"
              disabled={submitting}
              className="flex h-[52px] w-full min-w-0 items-center justify-center rounded-2xl bg-brand-600 px-4 text-sm font-semibold text-white transition hover:bg-brand-700 disabled:cursor-wait disabled:opacity-70"
            >
              <span className="truncate">{!user ? "Войти или зарегистрироваться" : submitting ? "Оформляем…" : `${t("co.confirm")} · ${tenge(total)}`}</span>
            </button>
          </div>
        </div>
      )}
    </form>
  );
}

function Row({ label, value, accent }: { label: string; value: string; accent?: boolean }) {
  return (
    <div className="flex min-w-0 justify-between gap-3">
      <dt className="min-w-0 text-slate-500">{label}</dt>
      <dd className={cn("shrink-0 text-right font-medium", accent ? "text-brand-700" : "text-slate-800")}>{value}</dd>
    </div>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="rounded-2xl bg-white p-4 sm:p-5">
      <h2 className="font-display text-xl font-semibold text-slate-900">{title}</h2>
      <div className="mt-4">{children}</div>
    </section>
  );
}

function RadioCard({ active, onClick, icon, title, text }: { active: boolean; onClick: () => void; icon: React.ReactNode; title: string; text: string }) {
  return (
    <button type="button" onClick={onClick} aria-pressed={active} className={cn("flex min-h-[68px] items-start gap-3 rounded-2xl border bg-white p-4 text-left transition", active ? "border-brand-500 ring-1 ring-brand-500" : "border-slate-200 hover:border-slate-300")}>
      <span className={cn("grid h-9 w-9 shrink-0 place-items-center rounded-lg", active ? "bg-brand-600 text-white" : "bg-slate-100 text-slate-500")}>{icon}</span>
      <span>
        <span className="block text-sm font-semibold text-slate-900">{title}</span>
        <span className="block text-xs text-slate-500">{text}</span>
      </span>
    </button>
  );
}

function Success({ order, code, delivery }: { order: string; code: string; delivery: "courier" | "pickup" }) {
  const { t } = useLang();
  const hintKey = delivery === "pickup" ? "co.receiptPickupHint" : "co.receiptDeliveryHint";
  return (
    <div className="mx-auto max-w-xl px-4 py-16 text-center sm:px-6">
      <span className="mx-auto grid h-20 w-20 place-items-center rounded-full bg-brand-100 text-brand-600"><CheckCircle2 className="h-11 w-11" /></span>
      <h1 className="mt-6 font-display text-3xl font-extrabold tracking-tight text-slate-900">{t("co.done.t")}</h1>
      <p className="mt-3 text-slate-500">{t("co.done.s1")} <span className="font-semibold text-slate-800">{order}</span>{t("co.done.s2")}</p>
      {code && (
        <div className="mx-auto mt-8 max-w-sm break-inside-avoid rounded-2xl border border-brand-200 bg-brand-50/60 p-5 print:border-slate-300 print:bg-white print:text-black">
          <p className="text-sm font-semibold text-brand-800">{t("co.receiptTitle")}</p>
          <p className="mt-3 text-xs text-slate-500">{t("co.receiptCode")}</p>
          <p
            aria-label={code.split("").join(" ")}
            className="select-all font-display text-4xl font-extrabold tabular-nums tracking-[0.16em] text-brand-800 sm:text-5xl print:text-black"
          >
            {code}
          </p>
          <p className="mt-3 text-xs text-brand-700 print:text-slate-700">{t(hintKey)}</p>
        </div>
      )}
      <div className="mt-8 flex flex-wrap justify-center gap-3">
        <Link href="/" className="inline-flex h-12 items-center rounded-xl border border-slate-200 px-6 font-semibold text-slate-700 transition hover:border-brand-300">{t("co.done.home")}</Link>
        <Link href="/account/orders" className="inline-flex h-12 items-center rounded-xl bg-brand-600 px-6 font-semibold text-white transition hover:bg-brand-700">{t("co.done.orders")}</Link>
      </div>
    </div>
  );
}
