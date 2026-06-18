// Screens — управление экранами в аптеках (ТЗ §3.3). Две вкладки:
//   • «Экраны в аптеках» — максимально просто: счётчик онлайн-касс + ОДИН общий ролик
//     («эфир») с кнопкой «Загрузить/Заменить ролик». Один ролик играет на всех кассах;
//     кассы подхватывают его поллингом /api/posm/playlists/active и шлют heartbeat.
//   • «Баннеры приложения» — управление баннерами главного экрана (BannersPanel).
//
// ДОП: вместо плейлистов/библиотеки/назначений — один атомарный «эфир»
//      (POST /api/admin/screens/broadcast). Сложный плейлист-CRUD скрыт из UI.

import { useRef, useState } from 'react'
import { Button, Empty, PageHeader, SectionCard, Tabs, useToast } from '@/ui'
import { IconPlus, IconScreens, IconUpload } from '@/ui/icons'
import { useBroadcast, useConnectedScreens, useUploadBroadcast } from '@/lib/queries/screens'
import { describeError } from '@/lib/describeError'
import { useT } from '@/i18n'
import { BannersPanel, type BannerEditing } from './BannersPanel'

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
  const { data, isLoading } = useConnectedScreens()
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
            {isLoading && !data ? '…' : total}
          </div>
        </div>
        <span className="chip chip-green">{t('scr.connectedLive')}</span>
      </div>
      {devices.length > 0 && (
        <ul className="hairline flex flex-col divide-y divide-ink-100 border-t pt-1">
          {devices.map((d) => (
            <li
              key={d.deviceId}
              data-testid={`connected-${d.deviceId}`}
              className="flex items-center justify-between gap-3 py-1.5 text-[12px]"
            >
              <span className="num font-bold text-ink-900">{d.deviceId}</span>
              <span className="text-ink-500">{d.pharmacyId ?? t('scr.connectedNoPharm')}</span>
              <span className="num text-ink-400">{d.lastSeen.slice(0, 19).replace('T', ' ')}</span>
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

// ─── Эфир: один общий ролик на все кассы (максимально просто) ────────────────
function BroadcastCard() {
  const t = useT()
  const toast = useToast()
  const broadcastQ = useBroadcast()
  const upload = useUploadBroadcast()
  const fileRef = useRef<HTMLInputElement>(null)
  const current = broadcastQ.data?.slides?.[0] ?? null

  const onPick = (file: File | null) => {
    if (!file) return
    upload.mutate(
      { file },
      {
        onSuccess: () => toast.push(t('scr.broadcastUploaded')),
        onError: (e) => toast.push(describeError(e)),
      },
    )
    if (fileRef.current) fileRef.current.value = ''
  }

  return (
    <SectionCard title={t('scr.broadcastTitle')} subtitle={t('scr.broadcastSub')} padded={false}>
      <div className="flex flex-col items-center gap-4 px-5 py-6" data-testid="broadcast-card">
        {current ? (
          <>
            <video
              src={current.url}
              className="max-h-[280px] w-auto rounded-xl border border-ink-100 bg-black"
              controls
              muted
              preload="metadata"
              data-testid="broadcast-video"
            />
            <div className="text-[12px] font-semibold text-ink-500">
              {current.title} · {fmtDuration(current.durationSec)}
            </div>
          </>
        ) : (
          <Empty
            title={t('scr.broadcastEmpty')}
            body={t('scr.broadcastEmptyBody')}
            icon={<IconScreens size={26} />}
          />
        )}

        <input
          ref={fileRef}
          type="file"
          accept="video/*"
          className="hidden"
          data-testid="broadcast-file-input"
          onChange={(e) => onPick(e.target.files?.[0] ?? null)}
        />
        <Button
          variant="primary"
          leading={<IconUpload size={14} />}
          disabled={upload.isPending}
          onClick={() => fileRef.current?.click()}
          data-testid="broadcast-upload"
        >
          {upload.isPending
            ? t('scr.broadcastUploading')
            : current
              ? t('scr.broadcastReplace')
              : t('scr.broadcastUpload')}
        </Button>
        <div className="max-w-[420px] text-center text-[11px] leading-snug text-ink-400">
          {t('scr.broadcastHint')}
        </div>
      </div>
    </SectionCard>
  )
}
