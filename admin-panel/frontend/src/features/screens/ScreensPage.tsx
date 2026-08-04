// Screens — управление экранами в аптеках (ТЗ §3.3). Две вкладки:
//   • «Экраны в аптеках» — счётчик онлайн-касс + 12 независимых рекламных слотов.
//     Заполненные слоты образуют один упорядоченный плейлист на всех кассах;
//     кассы подхватывают его поллингом /api/posm/playlists/active и шлют heartbeat.
//   • «Баннеры приложения» — управление баннерами главного экрана (BannersPanel).

import { useRef, useState } from 'react'
import { Button, PageHeader, SectionCard, Tabs, useToast } from '@/ui'
import { IconGrid, IconList, IconPlus, IconScreens, IconUpload } from '@/ui/icons'
import { useBroadcast, useConnectedScreens, useUploadBroadcastSlot } from '@/lib/queries/screens'
import { describeError } from '@/lib/describeError'
import { resolveEpharmMediaUrl } from '@/lib/media'
import type { ActiveSlideDto } from '@/lib/api-types'
import { useT } from '@/i18n'
import { BannersPanel, type BannerEditing } from './BannersPanel'

const BROADCAST_SLOT_COUNT = 12
type BroadcastViewMode = 'grid' | 'list'

export default function ScreensPage() {
  const t = useT()
  const [tab, setTab] = useState<'screens' | 'banners'>('screens')
  const [bannerEditing, setBannerEditing] = useState<BannerEditing>(null)

  return (
    <div className="flex flex-col gap-4">
      <PageHeader title={t('page.screens.title')} subtitle={t('page.screens.subtitle')} />

      <Tabs
        items={[
          { value: 'screens', label: t('scr.tabScreens') },
          { value: 'banners', label: t('scr.tabBanners') },
        ]}
        value={tab}
        onChange={setTab}
        trailing={
          tab === 'banners' ? (
            <Button
              variant="primary"
              leading={<IconPlus size={14} />}
              onClick={() => setBannerEditing('new')}
              data-testid="banners-create"
            >
              {t('bn.add')}
            </Button>
          ) : null
        }
      />

      {tab === 'screens' ? (
        <div className="flex flex-col gap-4">
          <ConnectedRegistersCard />
          <BroadcastCard />
        </div>
      ) : (
        <BannersPanel editing={bannerEditing} setEditing={setBannerEditing} />
      )}
    </div>
  )
}

// ─── Подключённые кассы (онлайн) ─────────────────────────────────────────────
function ConnectedRegistersCard() {
  const t = useT()
  const { data, isLoading, isError } = useConnectedScreens()
  const total = data?.total ?? 0
  const devices = data?.devices ?? []

  return (
    <div className="card flex flex-col gap-3 p-5" data-testid="connected-registers">
      <div className="flex items-center justify-between">
        <div>
          <div className="text-[12px] font-bold uppercase tracking-[0.06em] text-ink-500">
            {t('scr.connectedLabel')}
          </div>
          <div className="num mt-1 text-[28px] font-extrabold text-ink-900">
            {/* Ошибка ≠ «0 подключено»: при сбое запроса показываем «—», иначе
                фармацевт читал бы ложный ноль как «касс нет». */}
            {isError && !data ? '—' : isLoading && !data ? '…' : total}
          </div>
        </div>
        <span className="chip chip-green">{t('scr.connectedLive')}</span>
      </div>
      {devices.length > 0 && (
        <ul className="hairline flex flex-col divide-y divide-ink-100 border-t pt-1">
          {devices.map((d) => (
            <li
              key={`${d.pharmacyId ?? 'no-pharmacy'}:${d.deviceId}`}
              data-testid={`connected-${d.deviceId}`}
              className="flex items-center justify-between gap-3 py-1.5 text-[12px]"
            >
              <span className="num shrink-0 font-bold text-ink-900">{d.deviceId}</span>
              {/* Аптека: название + адрес (резолв с бэка); если аптека не найдена — id/«без аптеки» */}
              <span className="min-w-0 flex-1 truncate text-right">
                {d.pharmacyName ? (
                  <>
                    <span className="font-bold text-ink-700">{d.pharmacyName}</span>
                    {d.pharmacyAddress && (
                      <span className="text-ink-500"> · {d.pharmacyAddress}</span>
                    )}
                  </>
                ) : (
                  <span className="text-ink-500">{d.pharmacyId ?? t('scr.connectedNoPharm')}</span>
                )}
              </span>
              <span className="num shrink-0 text-ink-400">
                {d.lastSeen.slice(0, 19).replace('T', ' ')}
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

function fmtDuration(sec: number): string {
  const m = Math.floor(sec / 60)
  const s = sec % 60
  return m > 0 ? `${m}м ${s}с` : `${s}с`
}

function toBroadcastSlots(slides: ActiveSlideDto[]): Array<ActiveSlideDto | null> {
  const slots: Array<ActiveSlideDto | null> = Array.from(
    { length: BROADCAST_SLOT_COUNT },
    () => null,
  )
  slides.forEach((slide, index) => {
    // Fallback на index позволяет без простоя пережить rolling deploy со старым backend,
    // который ещё не отдавал position.
    const position = Number.isInteger(slide.position) ? slide.position : index
    if (position >= 0 && position < BROADCAST_SLOT_COUNT && slots[position] === null) {
      slots[position] = slide
    }
  })
  return slots
}

// ─── Эфир: 12 независимых слотов → один циклический плейлист POSM ───────────
function BroadcastCard() {
  const t = useT()
  const toast = useToast()
  const broadcastQ = useBroadcast()
  const upload = useUploadBroadcastSlot()
  const fileRef = useRef<HTMLInputElement>(null)
  const [viewMode, setViewMode] = useState<BroadcastViewMode>('grid')
  const [selectedSlot, setSelectedSlot] = useState<number | null>(null)
  const slots = toBroadcastSlots(broadcastQ.data?.slides ?? [])
  const loadedCount = slots.filter(Boolean).length
  const cycleDuration = slots.reduce((sum, slide) => sum + (slide?.durationSec ?? 0), 0)
  const pendingSlot = upload.isPending ? (upload.variables?.slot ?? selectedSlot) : null

  const openPicker = (slot: number) => {
    setSelectedSlot(slot)
    if (fileRef.current) {
      // Разрешаем повторно выбрать тот же файл для того же или другого слота.
      fileRef.current.value = ''
      fileRef.current.click()
    }
  }

  const onPick = (file: File | null) => {
    const slot = selectedSlot
    if (!file || slot === null) return
    upload.mutate(
      { slot, file },
      {
        onSuccess: () => {
          toast.push(t('scr.broadcastSlotUpdated', { slot }))
          setSelectedSlot(null)
        },
        onError: (e) => toast.push(describeError(e)),
      },
    )
    if (fileRef.current) fileRef.current.value = ''
  }

  return (
    <SectionCard
      title={t('scr.broadcastTitle')}
      subtitle={t('scr.broadcastSub')}
      padded={false}
      action={<BroadcastViewToggle mode={viewMode} onChange={setViewMode} />}
    >
      <div data-testid="broadcast-card">
        <div className="hairline flex flex-wrap items-center justify-between gap-2 border-b px-5 py-3">
          <div className="flex flex-wrap items-center gap-2 text-[12px]">
            <span className="font-bold text-ink-700">
              {t('scr.broadcastFilled', { count: loadedCount, total: BROADCAST_SLOT_COUNT })}
            </span>
            <span className="text-ink-300">·</span>
            <span className="text-ink-500">
              {t('scr.broadcastCycle', { duration: fmtDuration(cycleDuration) })}
            </span>
          </div>
          <span className="text-[11px] text-ink-400">{t('scr.broadcastOrder')}</span>
        </div>

        {broadcastQ.isError && (
          <div className="mx-5 mt-4 rounded-lg border border-accent-danger/20 bg-surface-danger px-3 py-2 text-[12px] font-semibold text-surface-danger-strong">
            {t('scr.broadcastLoadError')}
          </div>
        )}

        <input
          ref={fileRef}
          type="file"
          accept="video/mp4,video/webm,video/*"
          className="hidden"
          data-testid="broadcast-file-input"
          onChange={(e) => onPick(e.target.files?.[0] ?? null)}
        />

        <div className="p-5">
          {viewMode === 'grid' ? (
            <div
              className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-3 2xl:grid-cols-4"
              data-testid="broadcast-grid"
            >
              {slots.map((slide, index) => (
                <BroadcastSlotCard
                  key={index}
                  slot={index + 1}
                  slide={slide}
                  pending={pendingSlot === index + 1}
                  disabled={upload.isPending}
                  onPick={openPicker}
                />
              ))}
            </div>
          ) : (
            <div
              className="hairline divide-y divide-ink-100 overflow-hidden rounded-lg border"
              data-testid="broadcast-list"
            >
              {slots.map((slide, index) => (
                <BroadcastSlotRow
                  key={index}
                  slot={index + 1}
                  slide={slide}
                  pending={pendingSlot === index + 1}
                  disabled={upload.isPending}
                  onPick={openPicker}
                />
              ))}
            </div>
          )}

          <div className="mt-4 text-center text-[11px] leading-snug text-ink-400">
            {t('scr.broadcastHint')}
          </div>
        </div>
      </div>
    </SectionCard>
  )
}

interface BroadcastSlotProps {
  slot: number
  slide: ActiveSlideDto | null
  pending: boolean
  disabled: boolean
  onPick: (slot: number) => void
}

function BroadcastSlotCard({ slot, slide, pending, disabled, onPick }: BroadcastSlotProps) {
  const t = useT()
  return (
    <article
      className="hairline flex min-w-0 flex-col overflow-hidden rounded-lg border bg-white"
      data-testid={`broadcast-slot-${slot}`}
    >
      <div className="flex h-9 items-center justify-between border-b border-ink-100 px-3">
        <span className="num text-[11px] font-extrabold uppercase text-ink-600">
          {t('scr.broadcastSlot', { slot })}
        </span>
        <span className={slide ? 'chip chip-green' : 'text-[10px] font-semibold text-ink-400'}>
          {slide ? t('scr.broadcastReady') : t('scr.broadcastEmpty')}
        </span>
      </div>

      <BroadcastPreview slide={slide} slot={slot} />

      <div className="flex min-h-[92px] flex-col gap-3 p-3">
        <div className="min-w-0 flex-1">
          <div className="truncate text-[12px] font-bold text-ink-800">
            {slide?.title ?? t('scr.broadcastEmptyTitle')}
          </div>
          <div className="mt-0.5 text-[11px] text-ink-400">
            {slide ? fmtDuration(slide.durationSec) : t('scr.broadcastEmptyBody')}
          </div>
        </div>
        <Button
          variant={slide ? 'outline' : 'primary'}
          size="sm"
          leading={<IconUpload size={13} />}
          className="w-full justify-center"
          disabled={disabled}
          onClick={() => onPick(slot)}
          data-testid={`broadcast-slot-${slot}-upload`}
        >
          {pending
            ? t('scr.broadcastUploading')
            : slide
              ? t('scr.broadcastReplace')
              : t('scr.broadcastUpload')}
        </Button>
      </div>
    </article>
  )
}

function BroadcastSlotRow({ slot, slide, pending, disabled, onPick }: BroadcastSlotProps) {
  const t = useT()
  return (
    <div
      className="flex flex-col gap-3 bg-white p-3 sm:flex-row sm:items-center"
      data-testid={`broadcast-slot-${slot}`}
    >
      <div className="w-full flex-none overflow-hidden rounded-md bg-paper-input sm:w-40">
        <BroadcastPreview slide={slide} slot={slot} compact />
      </div>
      <div className="min-w-0 flex-1">
        <div className="num text-[10px] font-extrabold uppercase text-ink-500">
          {t('scr.broadcastSlot', { slot })}
        </div>
        <div className="mt-0.5 truncate text-[13px] font-bold text-ink-800">
          {slide?.title ?? t('scr.broadcastEmptyTitle')}
        </div>
        <div className="mt-0.5 text-[11px] text-ink-400">
          {slide ? fmtDuration(slide.durationSec) : t('scr.broadcastEmptyBody')}
        </div>
      </div>
      <Button
        variant={slide ? 'outline' : 'primary'}
        size="sm"
        leading={<IconUpload size={13} />}
        className="w-full justify-center sm:w-auto"
        disabled={disabled}
        onClick={() => onPick(slot)}
        data-testid={`broadcast-slot-${slot}-upload`}
      >
        {pending
          ? t('scr.broadcastUploading')
          : slide
            ? t('scr.broadcastReplace')
            : t('scr.broadcastUpload')}
      </Button>
    </div>
  )
}

function BroadcastPreview({
  slide,
  slot,
  compact = false,
}: {
  slide: ActiveSlideDto | null
  slot: number
  compact?: boolean
}) {
  const t = useT()
  return (
    <div className="relative aspect-video w-full overflow-hidden bg-ink-950">
      {slide ? (
        <video
          src={resolveEpharmMediaUrl(slide.url)}
          className="h-full w-full object-contain"
          controls={!compact}
          muted
          playsInline
          preload="metadata"
          data-testid={`broadcast-video-${slot}`}
        />
      ) : (
        <div className="flex h-full flex-col items-center justify-center gap-1.5 bg-paper-input text-ink-300">
          <IconScreens size={compact ? 18 : 24} />
          {!compact && (
            <span className="text-[10px] font-bold text-ink-400">{t('scr.broadcastEmpty')}</span>
          )}
        </div>
      )}
    </div>
  )
}

function BroadcastViewToggle({
  mode,
  onChange,
}: {
  mode: BroadcastViewMode
  onChange: (mode: BroadcastViewMode) => void
}) {
  const t = useT()
  const items = [
    { value: 'grid' as const, label: t('scr.broadcastViewGrid'), icon: <IconGrid size={16} /> },
    { value: 'list' as const, label: t('scr.broadcastViewList'), icon: <IconList size={16} /> },
  ]
  return (
    <div
      role="group"
      aria-label={t('scr.broadcastViewMode')}
      className="hairline inline-flex items-center gap-0.5 rounded-lg border bg-paper-input p-0.5"
    >
      {items.map((item) => {
        const active = item.value === mode
        return (
          <button
            key={item.value}
            type="button"
            aria-pressed={active}
            title={item.label}
            onClick={() => onChange(item.value)}
            data-testid={`broadcast-view-${item.value}`}
            className={
              active
                ? 'flex h-8 items-center gap-1.5 rounded-md bg-white px-2.5 text-[12px] font-bold text-ink-900 shadow-sm'
                : 'flex h-8 items-center gap-1.5 rounded-md px-2.5 text-[12px] font-bold text-ink-500 hover:text-ink-700'
            }
          >
            {item.icon}
            <span className="hidden sm:inline">{item.label}</span>
          </button>
        )
      })}
    </div>
  )
}
