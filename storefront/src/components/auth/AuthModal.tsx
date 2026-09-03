"use client";

import { useState } from "react";
import Link from "next/link";
import { X, Gift } from "lucide-react";
import { useAuth } from "@/lib/auth/AuthContext";
import { Logo } from "@/components/layout/Logo";
import { formatPhone, phoneDigits, isFullPhone } from "@/lib/phone";

export function AuthModal() {
  const { authMode, isModalOpen, closeLogin, continueWithPhone, continueAsDemo } = useAuth();
  const [stage, setStage] = useState<"contact" | "code">("contact");
  const [name, setName] = useState("");
  const [phone, setPhone] = useState("");
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const reset = () => {
    setStage("contact"); setName(""); setPhone(""); setCode("");
    setError(null); setBusy(false);
  };
  const close = () => { closeLogin(); reset(); };

  const sendCode = async () => {
    setBusy(true); setError(null);
    try {
      const r = await fetch("/api/otp/send", {
        method: "POST", headers: { "content-type": "application/json" },
        body: JSON.stringify({ phone: phoneDigits(phone) }),
      });
      const m = await r.json();
      if (m.ok) setStage("code");
      else setError(m.error === "too_soon" ? "Код уже отправлен — подождите 30 секунд." : "Не удалось отправить код. Попробуйте позже.");
    } catch { setError("Сеть недоступна."); }
    setBusy(false);
  };

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (busy || authMode === "loading") return;
    if (authMode === "demo") {
      setBusy(true); setError(null);
      const err = await continueAsDemo(name);
      if (err) setError(err); else reset();
      setBusy(false);
      return;
    }
    if (stage === "contact") {
      if (!isFullPhone(phone)) { setError("Введите номер телефона полностью."); return; }
      await sendCode();
      return;
    }
    setBusy(true); setError(null);
    // Код проверяется на сервере внутри continueWithPhone (там же get-or-create customer Медузы).
    const err = await continueWithPhone(name, phone, code);
    if (err) setError(err); else reset();
    setBusy(false);
  };

  const input =
    "h-12 w-full rounded-xl border border-slate-200 bg-white px-4 text-sm text-slate-800 outline-none transition placeholder:text-slate-400 focus:border-brand-400 focus:ring-2 focus:ring-brand-100";
  const demo = authMode === "demo";

  return (
    <div className={`fixed inset-0 z-[80] ${isModalOpen ? "" : "pointer-events-none"}`} aria-hidden={!isModalOpen}>
      <div onClick={close} className={`absolute inset-0 bg-slate-900/50 backdrop-blur-sm transition-opacity duration-300 ${isModalOpen ? "opacity-100" : "opacity-0"}`} />
      <div className="absolute inset-0 overflow-y-auto overscroll-contain">
        <div className="flex min-h-full items-center justify-center p-4 [padding-bottom:max(1rem,env(safe-area-inset-bottom))] [padding-top:max(1rem,env(safe-area-inset-top))]">
        <div role="dialog" aria-modal="true" aria-labelledby="auth-title" className={`relative max-h-[calc(100dvh-2rem)] w-full max-w-sm overflow-y-auto rounded-3xl bg-white p-5 shadow-pop transition-all duration-300 sm:p-7 ${isModalOpen ? "scale-100 opacity-100" : "scale-95 opacity-0"}`}>
          <button onClick={close} aria-label="Закрыть" className="absolute right-4 top-4 grid h-9 w-9 place-items-center rounded-xl text-slate-400 hover:bg-slate-100">
            <X className="h-5 w-5" />
          </button>

          <div className="flex justify-center"><Logo /></div>
          <h2 id="auth-title" className="mt-5 text-center text-xl font-bold text-slate-900">
            {authMode === "loading" ? "Подключаем авторизацию" : demo ? "Демо-режим" : "Вход или регистрация"}
          </h2>
          <p className="mt-1 text-center text-sm text-slate-500">
            {authMode === "loading"
              ? "Проверяем доступный способ входа…"
              : demo
                ? "Без SMS и реальных платежей. Для этого браузера будет создан отдельный демо-покупатель."
                : "Введите номер телефона — войдём, если аккаунт есть, или создадим за секунду."}
          </p>

          <form onSubmit={submit} className="mt-5 space-y-3">
            {demo ? (
              <input autoFocus value={name} onChange={(e) => setName(e.target.value)} placeholder="Имя (необязательно)" autoComplete="name" className={input} />
            ) : authMode === "loading" ? (
              <div className="h-12 animate-pulse rounded-xl bg-slate-100" aria-hidden />
            ) : stage === "contact" ? (
              <>
                <input autoFocus value={name} onChange={(e) => setName(e.target.value)} placeholder="Имя (необязательно)" className={input} />
                <input
                  value={phone}
                  onChange={(e) => setPhone(formatPhone(e.target.value))}
                  placeholder="+7 (___) ___-__-__"
                  inputMode="tel"
                  autoComplete="tel"
                  className={input}
                />
              </>
            ) : (
              <>
                <p className="text-sm text-slate-500">Мы отправили SMS с кодом на {formatPhone(phone)}</p>
                <input autoFocus value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, "").slice(0, 6))} inputMode="numeric" autoComplete="one-time-code" maxLength={6} placeholder="6 цифр из SMS" className={input} />
                <div className="flex items-center justify-between text-sm">
                  <button type="button" onClick={() => { setStage("contact"); setCode(""); setError(null); }} className="font-medium text-brand-600">Изменить номер</button>
                  <button type="button" onClick={sendCode} disabled={busy} className="font-medium text-brand-600 disabled:opacity-50">Отправить ещё раз</button>
                </div>
              </>
            )}
            {error && <p className="text-sm font-medium text-accent-600">{error}</p>}
            <button type="submit" disabled={busy || authMode === "loading" || (!demo && ((stage === "contact" && !isFullPhone(phone)) || (stage === "code" && code.length !== 6)))} className="h-12 w-full rounded-xl bg-brand-600 font-semibold text-white transition hover:bg-brand-700 disabled:opacity-60">
              {busy ? "Подождите…" : demo ? "Продолжить в демо" : stage === "contact" ? "Получить код" : "Подтвердить"}
            </button>
          </form>

          <div className="mt-4 flex items-center justify-center gap-2 rounded-xl bg-brand-50 px-3 py-2.5 text-center text-sm text-brand-800">
            <Gift className="h-4 w-4 shrink-0" /> Новым — 1 500 приветственных баллов
          </div>
          <p className="mt-3 text-center text-xs text-slate-400">
            Продолжая, вы соглашаетесь с условиями и{" "}
            <Link
              href="/privacy"
              onClick={close}
              className="font-medium text-brand-700 underline underline-offset-2"
            >
              политикой конфиденциальности
            </Link>
            .
          </p>
        </div>
        </div>
      </div>
    </div>
  );
}
