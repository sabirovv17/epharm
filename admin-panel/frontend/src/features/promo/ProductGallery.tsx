// ProductGallery — просмотр галереи фото товара кампании (read-only).
// Источник: фото из Medusa (`images`) + обложка (override/снимок). Главное фото +
// миниатюры с переключением + лайтбокс (клик по главному → увеличение в модалке).
// Текущая обложка (effective) помечается бейджем.

import { useState } from 'react'
import { Modal } from '@/ui'
import { IconLayers } from '@/ui/icons'
import { useT } from '@/i18n'

export function ProductGallery({
  images,
  effective,
}: {
  images: string[]
  effective: string | null
}) {
  const t = useT()
  // Стартовый кадр — текущая обложка (если есть в списке), иначе первый.
  const [active, setActive] = useState(() => {
    const i = effective ? images.indexOf(effective) : 0
    return i >= 0 ? i : 0
  })
  const [zoom, setZoom] = useState(false)

  if (images.length === 0) {
    return (
      <div
        className="hairline flex items-center gap-2 rounded-lg border border-dashed bg-paper-hover px-3 py-3 text-[12px] font-semibold text-ink-400"
        data-testid="promo-gallery-empty"
      >
        <IconLayers size={16} />
        {t('pm.galleryEmpty')}
      </div>
    )
  }

  const main = images[Math.min(active, images.length - 1)]

  return (
    <div data-testid="promo-gallery">
      {/* Главное фото — клик открывает лайтбокс. */}
      <button
        type="button"
        onClick={() => setZoom(true)}
        aria-label={t('pm.galleryZoom')}
        data-testid="promo-gallery-main"
        className="hairline block w-full overflow-hidden rounded-xl border bg-paper-card"
      >
        <img src={main} alt="" className="mx-auto max-h-[260px] w-auto object-contain" />
      </button>

      {/* Миниатюры (если фото больше одного). */}
      {images.length > 1 && (
        <div className="mt-2 flex flex-wrap gap-2">
          {images.map((src, i) => (
            <button
              key={src}
              type="button"
              onClick={() => setActive(i)}
              data-testid={`promo-gallery-thumb-${i}`}
              aria-label={`${t('pm.galleryTitle')} ${i + 1}`}
              className={`relative h-14 w-14 flex-none overflow-hidden rounded-lg border-2 bg-paper-card transition ${
                i === active ? 'border-brand-green-600' : 'border-ink-100 hover:border-ink-300'
              }`}
            >
              <img src={src} alt="" className="h-full w-full object-cover" />
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
        <img
          src={main}
          alt=""
          className="mx-auto max-h-[70vh] w-auto object-contain"
          data-testid="promo-gallery-zoom-img"
        />
      </Modal>
    </div>
  )
}
