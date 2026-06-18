// ProductGallery — листаемая галерея фото товара кампании.
// Источник: фото из Medusa (`images`) + обложка (override/снимок). Главное фото
// со стрелками ‹/›, клавиатурой (←/→) и свайпом + лента миниатюр + счётчик +
// лайтбокс по клику. Текущая обложка (effective) помечается бейджем.
//
// `images`/`effective` — ИСХОДНЫЕ URL (как в БД). Для тега <img> прогоняем через
// `resolveSrc` (по умолчанию — без изменений; в админке — proxyMedia: http→https-
// прокси против mixed content). Так значение обложки остаётся «чистым» URL Medusa,
// а отображение идёт через прокси.
//
// Битые ссылки (404/CDN-мусор) ловятся через onError и тихо выпадают из галереи —
// никаких браузерных «сломанных» иконок. Если не загрузилось ни одно фото — чистое
// пустое состояние, а не сетка битых превью.
//
// `onPickCover` (опц.) — режим выбора обложки (создание/редактирование кампании):
// на главном фото появляется «Сделать обложкой», текущая обложка помечена.

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Modal } from '@/ui'
import { IconCheck, IconChevLeft, IconChevRight, IconLayers } from '@/ui/icons'
import { useT } from '@/i18n'

export function ProductGallery({
  images,
  effective,
  resolveSrc = (u) => u,
  onPickCover,
}: {
  images: string[]
  effective: string | null
  resolveSrc?: (url: string) => string
  onPickCover?: (url: string) => void
}) {
  const t = useT()

  // Битые URL — исключаем из галереи, чтобы не показывать broken-иконки.
  const [broken, setBroken] = useState<Set<string>>(() => new Set())
  const markBroken = useCallback(
    (src: string) => setBroken((prev) => (prev.has(src) ? prev : new Set(prev).add(src))),
    [],
  )

  // Видимые (ещё не помеченные битыми) фото — в исходном порядке, без дублей.
  const visible = useMemo(() => images.filter((s) => !broken.has(s)), [images, broken])

  // Выбор пользователя храним по src (а не по индексу): выпадение битого фото
  // из visible не сбивает выбор. Сам активный кадр — производный: если выбор
  // пропал из visible, откатываемся к обложке, иначе к первому доступному. Это
  // снимает необходимость в setState-внутри-эффекта (синхронизация = вычисление).
  const [selected, setSelected] = useState<string | null>(null)
  const current =
    (selected && visible.includes(selected) ? selected : null) ??
    (effective && visible.includes(effective) ? effective : null) ??
    visible[0] ??
    null

  const [zoom, setZoom] = useState(false)

  const activeIdx = current ? Math.max(0, visible.indexOf(current)) : 0
  const multi = visible.length > 1

  const go = useCallback(
    (dir: -1 | 1) => {
      if (visible.length < 2) return
      const next = (activeIdx + dir + visible.length) % visible.length
      setSelected(visible[next])
    },
    [activeIdx, visible],
  )

  // Клавиатура ←/→ — и в обычном виде (по фокусу), и в лайтбоксе (глобально).
  useEffect(() => {
    if (!zoom) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'ArrowLeft') go(-1)
      else if (e.key === 'ArrowRight') go(1)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [zoom, go])

  // Авто-прокрутка активной миниатюры в зону видимости ленты.
  const activeThumbRef = useRef<HTMLButtonElement | null>(null)
  useEffect(() => {
    activeThumbRef.current?.scrollIntoView?.({ block: 'nearest', inline: 'nearest' })
  }, [current])

  // Свайп по главному фото (тач/мышь).
  const swipeX = useRef<number | null>(null)
  const onPointerDown = (e: React.PointerEvent) => {
    swipeX.current = e.clientX
  }
  const onPointerUp = (e: React.PointerEvent) => {
    if (swipeX.current === null) return
    const dx = e.clientX - swipeX.current
    swipeX.current = null
    if (Math.abs(dx) > 40) go(dx < 0 ? 1 : -1)
  }

  // Нет фото вовсе или все ссылки оказались битыми → пустое состояние.
  if (visible.length === 0) {
    return (
      <>
        <div
          className="hairline flex items-center gap-2 rounded-lg border border-dashed bg-paper-hover px-3 py-3 text-[12px] font-semibold text-ink-400"
          data-testid="promo-gallery-empty"
        >
          <IconLayers size={16} />
          {images.length > 0 ? t('pm.galleryUnavailable') : t('pm.galleryEmpty')}
        </div>
        {/* Скрытые пробники: дают onError шанс пометить ещё не проверенные
            ссылки битыми, даже когда визуально ничего не отрисовано. */}
        {images.map((src) =>
          broken.has(src) ? null : (
            <img
              key={src}
              src={resolveSrc(src)}
              alt=""
              className="hidden"
              onError={() => markBroken(src)}
            />
          ),
        )}
      </>
    )
  }

  const main = visible[Math.min(activeIdx, visible.length - 1)]

  return (
    <div data-testid="promo-gallery">
      {/* Главное фото: кнопка-зум + наложенные стрелки + счётчик. */}
      <div
        className="hairline relative w-full overflow-hidden rounded-xl border bg-paper-card"
        onPointerDown={onPointerDown}
        onPointerUp={onPointerUp}
        onKeyDown={(e) => {
          if (e.key === 'ArrowLeft') {
            e.preventDefault()
            go(-1)
          } else if (e.key === 'ArrowRight') {
            e.preventDefault()
            go(1)
          }
        }}
      >
        <button
          type="button"
          onClick={() => setZoom(true)}
          aria-label={t('pm.galleryZoom')}
          data-testid="promo-gallery-main"
          className="block w-full cursor-zoom-in"
        >
          <img
            src={resolveSrc(main)}
            alt=""
            className="mx-auto max-h-[260px] w-auto select-none object-contain"
            draggable={false}
            onError={() => markBroken(main)}
          />
        </button>

        {/* Выбор обложки (создание/редактирование): «Сделать обложкой» либо метка
            текущей обложки. Не мешает зуму — отдельная кнопка поверх угла. */}
        {onPickCover &&
          (main === effective ? (
            <span className="pointer-events-none absolute left-2 top-2 inline-flex items-center gap-1 rounded-full bg-brand-green-700/90 px-2.5 py-1 text-[11px] font-bold uppercase tracking-wide text-white">
              <IconCheck size={12} />
              {t('pm.galleryCurrent')}
            </span>
          ) : (
            <button
              type="button"
              onClick={() => onPickCover(main)}
              data-testid="promo-gallery-set-cover"
              className="absolute left-2 top-2 rounded-full bg-paper-card/90 px-2.5 py-1 text-[11px] font-bold text-brand-green-700 shadow-sm backdrop-blur transition hover:bg-paper-card"
            >
              {t('pm.gallerySetCover')}
            </button>
          ))}

        {multi && (
          <>
            <GalleryArrow
              side="left"
              label={t('pm.galleryPrev')}
              onClick={() => go(-1)}
              testid="promo-gallery-prev"
            />
            <GalleryArrow
              side="right"
              label={t('pm.galleryNext')}
              onClick={() => go(1)}
              testid="promo-gallery-next"
            />
            <span
              data-testid="promo-gallery-counter"
              className="pointer-events-none absolute bottom-2 right-2 rounded-full bg-ink-900/70 px-2 py-0.5 text-[11px] font-bold tabular-nums text-white"
            >
              {activeIdx + 1} / {visible.length}
            </span>
          </>
        )}
      </div>

      {/* Лента миниатюр — горизонтально листается, активная подсвечена. */}
      {multi && (
        <div className="mt-2 flex gap-2 overflow-x-auto pb-1">
          {visible.map((src, i) => (
            <button
              key={src}
              ref={src === main ? activeThumbRef : undefined}
              type="button"
              onClick={() => setSelected(src)}
              data-testid={`promo-gallery-thumb-${i}`}
              aria-label={`${t('pm.galleryTitle')} ${i + 1}`}
              aria-current={src === main}
              className={`relative h-14 w-14 flex-none overflow-hidden rounded-lg border-2 bg-paper-card transition ${
                src === main ? 'border-brand-green-600' : 'border-ink-100 hover:border-ink-300'
              }`}
            >
              <img
                src={resolveSrc(src)}
                alt=""
                className="h-full w-full object-cover"
                draggable={false}
                onError={() => markBroken(src)}
              />
              {src === effective && (
                <span className="absolute inset-x-0 bottom-0 bg-brand-green-700/85 text-center text-[8px] font-bold uppercase tracking-wide text-white">
                  {t('pm.galleryCurrent')}
                </span>
              )}
            </button>
          ))}
        </div>
      )}

      <Modal open={zoom} onClose={() => setZoom(false)} title={t('pm.galleryTitle')} width={760}>
        <div className="relative">
          <img
            src={resolveSrc(main)}
            alt=""
            className="mx-auto max-h-[70vh] w-auto object-contain"
            data-testid="promo-gallery-zoom-img"
            onError={() => markBroken(main)}
          />
          {multi && (
            <>
              <GalleryArrow
                side="left"
                label={t('pm.galleryPrev')}
                onClick={() => go(-1)}
                testid="promo-gallery-zoom-prev"
              />
              <GalleryArrow
                side="right"
                label={t('pm.galleryNext')}
                onClick={() => go(1)}
                testid="promo-gallery-zoom-next"
              />
              <span className="pointer-events-none absolute bottom-2 right-2 rounded-full bg-ink-900/70 px-2.5 py-1 text-[12px] font-bold tabular-nums text-white">
                {activeIdx + 1} / {visible.length}
              </span>
            </>
          )}
        </div>
      </Modal>
    </div>
  )
}

/** Кружок-стрелка поверх фото (‹/›). Вынесен, чтобы не плодить разметку. */
function GalleryArrow({
  side,
  label,
  onClick,
  testid,
}: {
  side: 'left' | 'right'
  label: string
  onClick: () => void
  testid: string
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-label={label}
      data-testid={testid}
      className={`absolute top-1/2 grid h-9 w-9 -translate-y-1/2 place-items-center rounded-full border border-ink-900/10 bg-paper-card/90 text-ink-700 shadow-sm backdrop-blur transition hover:bg-paper-card hover:text-ink-900 ${
        side === 'left' ? 'left-2' : 'right-2'
      }`}
    >
      {side === 'left' ? <IconChevLeft size={18} /> : <IconChevRight size={18} />}
    </button>
  )
}
