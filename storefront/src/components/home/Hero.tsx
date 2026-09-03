"use client";

import { useState, useEffect, type CSSProperties } from "react";
import Link from "next/link";
import Image from "next/image";
import { ArrowRight, ChevronLeft, ChevronRight, Pause, Play } from "lucide-react";
import { cn } from "@/lib/cn";
import { useContent, type Banner } from "@/lib/content/ContentContext";
import { cmsTr } from "@/lib/content/cmsI18n";
import { useLang } from "@/lib/i18n/LanguageContext";
import { CollectionIcon } from "@/components/ui/CollectionIcon";

const deco: { top?: string; bottom?: string; left?: string; right?: string; size: number; op: number; dur: number; delay: number; anim: "a" | "b" }[] = [
  { top: "12%", left: "5%", size: 48, op: 0.5, dur: 7, delay: 0, anim: "a" },
  { top: "66%", left: "13%", size: 32, op: 0.4, dur: 8, delay: 1, anim: "b" },
  { top: "20%", right: "16%", size: 26, op: 0.5, dur: 6.5, delay: 0.5, anim: "a" },
  { bottom: "14%", right: "7%", size: 56, op: 0.38, dur: 9, delay: 1.4, anim: "b" },
];

const quickImagePositions: Record<string, string> = {
  ql1: "62% center",
  ql2: "65% center",
  ql3: "64% center",
  ql4: "64% center",
};

// Светлый ли фон плитки — чтобы выбрать тёмный/белый текст и держать читаемость
// (WCAG) при любом цвете, заданном в админке. Берём верхний-левый стоп `from`,
// где у 135°-градиента светлее всего и где лежит подпись.
function isLightBg(hex: string): boolean {
  const m = /^#?([0-9a-f]{6})$/i.exec((hex || "").trim());
  if (!m) return false;
  const n = parseInt(m[1], 16);
  const r = (n >> 16) & 255, g = (n >> 8) & 255, b = n & 255;
  return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255 > 0.6;
}

function hexToRgba(hex: string, alpha: number): string {
  const m = /^#?([0-9a-f]{6})$/i.exec((hex || "").trim());
  if (!m) return `rgba(0,0,0,${alpha})`;
  const n = parseInt(m[1], 16);
  return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${alpha})`;
}

function quickLinkOverlay(from: string, to: string): string {
  return `linear-gradient(180deg, rgba(0,0,0,.28) 0%, rgba(0,0,0,.05) 56%, rgba(0,0,0,.2) 100%), linear-gradient(135deg, ${hexToRgba(from, .82)} 0%, ${hexToRgba(from, .28)} 52%, ${hexToRgba(to, .48)} 100%)`;
}

export function Hero() {
  const { content } = useContent();
  const banners = content.banners.filter((b) => b.on);
  const n = banners.length;
  const [i, setI] = useState(0);
  const [tick, setTick] = useState(0); // нонс: перезапуск автоплея при ручной навигации
  const [hover, setHover] = useState(false);
  const [focus, setFocus] = useState(false);
  const [userPaused, setUserPaused] = useState(false); // явная пауза кнопкой — для тача (нет hover)
  const paused = hover || focus || userPaused; // WCAG 2.2.2 (Pause, Stop, Hide)

  useEffect(() => {
    if (n <= 1 || paused) return;
    if (typeof navigator !== "undefined" && navigator.userAgent.includes("AptekaSoSkladaAndroid")) return;
    if (typeof window !== "undefined" && window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
    const tm = setInterval(() => setI((p) => (p + 1) % n), 6000);
    return () => clearInterval(tm);
  }, [n, tick, paused]);
  // eslint-disable-next-line react-hooks/set-state-in-effect -- кламп индекса к последнему валидному слайду
  useEffect(() => { if (i >= n && n > 0) setI(n - 1); }, [i, n]);

  const active = n ? Math.min(i, n - 1) : 0;
  const goto = (next: number) => { setI(((next % n) + n) % n); setTick((t) => t + 1); };

  return (
    <section className="mx-auto max-w-7xl px-4 pt-4 sm:px-6 sm:pt-6">
      <div className="grid gap-3 lg:grid-cols-[minmax(0,1fr)_312px] lg:gap-4">
        {/* ── Билборд (рекламная карусель) ── */}
        <div
          className="relative h-[300px] overflow-hidden rounded-3xl bg-slate-100 shadow-card sm:h-[360px] lg:h-[420px]"
          onMouseEnter={() => setHover(true)}
          onMouseLeave={() => setHover(false)}
          onFocusCapture={() => setFocus(true)}
          onBlurCapture={() => setFocus(false)}
        >
          {n ? (
            banners.map((b, idx) => (
              <SlideView
                key={b.id}
                b={b}
                active={idx === active}
                shouldLoad={n <= 3 || idx === active || Math.abs(idx - active) === 1 || Math.abs(idx - active) === n - 1}
              />
            ))
          ) : (
            <div className="absolute inset-0" style={{ background: "linear-gradient(135deg, #16a85c, #0c7d43)" }} />
          )}

          {n > 1 && (
            <>
              <button onClick={() => goto(active - 1)} aria-label="Назад" className="absolute left-3 top-1/2 z-30 hidden h-10 w-10 -translate-y-1/2 place-items-center rounded-full bg-white/85 text-slate-900 shadow-sm backdrop-blur transition hover:bg-white lg:grid">
                <ChevronLeft className="h-5 w-5" />
              </button>
              <button onClick={() => goto(active + 1)} aria-label="Вперёд" className="absolute right-3 top-1/2 z-30 hidden h-10 w-10 -translate-y-1/2 place-items-center rounded-full bg-white/85 text-slate-900 shadow-sm backdrop-blur transition hover:bg-white lg:grid">
                <ChevronRight className="h-5 w-5" />
              </button>
              <div className="absolute inset-x-0 bottom-4 z-30 flex justify-center gap-2">
                {banners.map((b, idx) => (
                  <button key={b.id} onClick={() => goto(idx)} aria-label={`Слайд ${idx + 1} из ${n}`} aria-current={idx === active ? "true" : undefined} className={cn("h-2 rounded-full bg-white shadow transition-all", idx === active ? "w-7" : "w-2 bg-white/55")} />
                ))}
              </div>
              <button
                onClick={() => setUserPaused((p) => !p)}
                aria-label={userPaused ? "Воспроизвести слайды" : "Приостановить слайды"}
                aria-pressed={userPaused}
                className="absolute bottom-3 left-3 z-30 grid h-8 w-8 place-items-center rounded-full bg-white/85 text-slate-900 shadow-sm backdrop-blur transition hover:bg-white"
              >
                {userPaused ? <Play className="h-4 w-4" /> : <Pause className="h-4 w-4" />}
              </button>
            </>
          )}
        </div>

        {/* ── Панель быстрых переходов (2×2) ── */}
        <QuickNav />
      </div>
    </section>
  );
}

function QuickNav() {
  const { content } = useContent();
  const { lang } = useLang();
  const tiles = content.quickLinks.filter((q) => q.on);
  if (!tiles.length) return null;
  return (
    <nav aria-label="Быстрые разделы" className="grid grid-cols-2 gap-3 lg:h-[420px] lg:grid-rows-2">
      {tiles.map((q) => {
        const dark = isLightBg(q.from);
        const image = q.img || "";
        const imagePosition = quickImagePositions[q.id] || "center";
        return (
          <Link
            key={q.id}
            href={q.href || "/catalog"}
            className="group relative flex min-h-[118px] items-start overflow-hidden rounded-2xl p-4 shadow-sm transition-all duration-300 hover:-translate-y-0.5 hover:shadow-card sm:p-5"
            style={{ background: `linear-gradient(135deg, ${q.from}, ${q.to})` }}
          >
            {image && (
              <Image
                src={image}
                alt=""
                fill
                sizes="(max-width: 1023px) 50vw, 156px"
                className="object-cover transition duration-500 group-hover:scale-105"
                style={{ objectPosition: imagePosition }}
              />
            )}
            {image && <span className="absolute inset-0" style={{ background: quickLinkOverlay(q.from, q.to) }} />}
            <span className={cn("relative z-10 max-w-[85%] text-balance text-[15px] font-extrabold uppercase leading-tight tracking-tight drop-shadow-sm sm:text-base", image ? "text-white" : dark ? "text-slate-900" : "text-white")}>
              {cmsTr(lang, q.id, "label", q.label)}
            </span>
            {image ? (
              <span className="absolute bottom-3 right-3 z-10 grid h-8 w-8 place-items-center rounded-full bg-white/85 text-slate-900 shadow-sm transition group-hover:translate-x-0.5">
                <ArrowRight className="h-4 w-4" />
              </span>
            ) : (
              <CollectionIcon
                name={q.icon}
                className={cn("pointer-events-none absolute -bottom-3 -right-2 h-20 w-20 transition-transform duration-300 group-hover:scale-110 sm:h-24 sm:w-24", dark ? "text-slate-900/15" : "text-white/25")}
              />
            )}
          </Link>
        );
      })}
    </nav>
  );
}

function SlideView({ b, active, shouldLoad }: { b: Banner; active: boolean; shouldLoad: boolean }) {
  const { lang } = useLang();
  const eyebrow = cmsTr(lang, b.id, "eyebrow", b.eyebrow);
  const title = cmsTr(lang, b.id, "title", b.title);
  const sub = cmsTr(lang, b.id, "sub", b.sub);
  const cta = cmsTr(lang, b.id, "cta", b.cta);
  const [vidFailed, setVidFailed] = useState(false);
  const showVideo = Boolean(b.video) && shouldLoad && !vidFailed;

  return (
    <div
      className={cn("absolute inset-0 transition-opacity duration-700 ease-[cubic-bezier(.22,1,.36,1)]", active ? "opacity-100" : "pointer-events-none opacity-0")}
      inert={!active}
    >
      {/* База: фото > бренд-градиент — служит фолбэком, если видео не загрузится. */}
      <div
        className="absolute inset-0"
        style={{
          background: b.img && shouldLoad
            ? `linear-gradient(110deg, rgba(6,18,12,.5), rgba(6,18,12,.22)), url(${JSON.stringify(b.img)}) center/cover no-repeat`
            : `linear-gradient(115deg, ${b.from}, ${b.to})`,
        }}
      />
      {/* Видео поверх базы; при ошибке загрузки снимаем — остаётся фото/градиент. */}
      {showVideo && (
        <>
          <video
            src={b.video}
            poster={b.img || undefined}
            autoPlay
            muted
            loop
            playsInline
            onError={() => setVidFailed(true)}
            className="absolute inset-0 h-full w-full object-cover"
          />
          <div className="absolute inset-0 bg-gradient-to-r from-black/55 via-black/30 to-black/15" />
        </>
      )}

      <div className="absolute -left-20 -top-14 h-72 w-72 rounded-full bg-white/15 blur-3xl" />
      <div className="absolute -bottom-14 right-8 h-80 w-80 rounded-full bg-black/10 blur-3xl" />

      {!b.img && !showVideo && deco.map((d, idx) => (
        <HeroCross
          key={idx}
          className="pharma-cross absolute text-white"
          style={{
            top: d.top, bottom: d.bottom, left: d.left, right: d.right,
            width: d.size, height: d.size, opacity: d.op,
            animationName: d.anim === "a" ? "pharma-float-a" : "pharma-float-b",
            animationDuration: `${d.dur}s`, animationDelay: `${d.delay}s`,
          }}
        />
      ))}

      <div className="relative z-10 flex h-full flex-col justify-center px-6 py-7 sm:px-10 lg:px-14">
        <div className={cn("flex flex-col items-start text-left", b.img || showVideo ? "max-w-sm" : "max-w-md")}>
          {eyebrow && <span className="text-xs font-semibold uppercase tracking-[0.14em] text-white/85 drop-shadow sm:text-sm">{eyebrow}</span>}
          <h2 className="mt-2 whitespace-pre-line font-display text-3xl font-extrabold leading-[0.98] tracking-tight text-balance text-white drop-shadow-[0_2px_14px_rgba(0,0,0,.35)] sm:text-4xl lg:text-5xl">
            {title}
          </h2>
          {sub && <p className="mt-3 max-w-xs text-sm text-white/90 drop-shadow sm:text-base">{sub}</p>}
          {cta && (
            <Link href={b.href || "/catalog"} className="mt-5 inline-flex h-11 items-center gap-2 rounded-xl bg-white px-6 text-xs font-semibold uppercase tracking-wider text-slate-950 transition hover:bg-white/90">
              {cta} <ArrowRight className="h-4 w-4" />
            </Link>
          )}
        </div>
      </div>
      {/* Готовый баннер с вшитым текстом: вся плашка — ссылка на товар. */}
      {b.href && <Link href={b.href} aria-label="Открыть" className="absolute inset-0 z-20" />}
    </div>
  );
}

function HeroCross({ className, style }: { className?: string; style?: CSSProperties }) {
  const d = "M35 0 H65 V35 H100 V65 H65 V100 H35 V65 H0 V35 H35 Z";
  return (
    <svg viewBox="0 0 100 100" className={className} style={style} aria-hidden>
      <path d={d} fill="currentColor" />
    </svg>
  );
}
