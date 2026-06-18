// Screens — управление indoor-DOOH экранами в аптеках (ТЗ §3.3).
// Загрузка медиа (видео/картинки) в MinIO, сборка плейлистов, активация ротации,
// назначение плейлистов на конкретную аптеку/экран. Рендер на 2-м мониторе = POSM.
//
// T3: страница упрощена — нет ручного создания плейлиста (собираются автоматически),
//     убраны колонка «Аптек» и пустой блок «Расписание».
// T4: виджет «Подключено касс: N» (heartbeat подключённых POSM-плееров, поллинг 30с).
// ДОП.2: убран таргетинг по аптеке — один глобальный видеоряд, все кассы подхватывают
//        активный плейлист автоматически (backend: fallback на глобальный pharmacyId IS NULL).
//        Адресацию под конкретную аптеку добавим позже.

import { useEffect, useRef, useState } from 'react'
import {
  Button,
  Empty,
  Field,
  Input,
  Modal,
  PageHeader,
  SectionCard,
  Select,
  Tabs,
  useToast,
} from '@/ui'
import { IconPlus, IconScreens, IconStack, IconTrash, IconUpload } from '@/ui/icons'
import type { PlaylistDto, SlideDto } from '@/lib/api-types'
import {
  useAssignSlide,
  useConnectedScreens,
  useDeletePlaylist,
  useDeleteSlide,
  usePlaylists,
  useSlides,
  useUpdatePlaylist,
  useUploadSlide,
} from '@/lib/queries/screens'
import { describeError } from '@/lib/describeError'
import { formatNum } from '@/mocks/fixtures'
import { useT } from '@/i18n'
import { BannersPanel, type BannerEditing } from './BannersPanel'

export default function ScreensPage() {
  const t = useT()
  // Две вкладки одного раздела: физические экраны касс и баннеры в приложении.
  const [tab, setTab] = useState<'screens' | 'banners'>('screens')
  const [uploadOpen, setUploadOpen] = useState(false)
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
          tab === 'screens' ? (
            <Button
              variant="primary"
              leading={<IconUpload size={14} />}
              onClick={() => setUploadOpen(true)}
              data-testid="screens-upload-slide"
            >
              {t('scr.uploadSlide')}
            </Button>
          ) : (
            <Button
              variant="primary"
              leading={<IconPlus size={14} />}
              onClick={() => setBannerEditing('new')}
              data-testid="banners-create"
            >
              {t('bn.add')}
            </Button>
          )
        }
      />

      {tab === 'screens' ? (
        <ScreensTab uploadOpen={uploadOpen} setUploadOpen={setUploadOpen} />
      ) : (
        <BannersPanel editing={bannerEditing} setEditing={setBannerEditing} />
      )}
    </div>
  )
}

// ─── Вкладка «Экраны в аптеках» — физические DOOH-экраны (плейлисты + слайды) ──
function ScreensTab({
  uploadOpen,
  setUploadOpen,
}: {
  uploadOpen: boolean
  setUploadOpen: (v: boolean) => void
}) {
  const t = useT()

  const playlistsQ = usePlaylists()
  const slidesQ = useSlides()
  const playlists = playlistsQ.data ?? []
  const slides = slidesQ.data ?? []

  return (
    <div className="flex flex-col gap-4">
      <UploadSlideModal open={uploadOpen} onClose={() => setUploadOpen(false)} />

      <ConnectedRegistersCard />

      <SectionCard title={t('scr.activeTitle')} subtitle={t('scr.activeSub')} padded={false}>
        {playlistsQ.isLoading && playlists.length === 0 ? (
          <Empty
            title={t('scr.loading')}
            body={t('common.connecting')}
            icon={<IconScreens size={26} />}
          />
        ) : playlistsQ.isError && playlists.length === 0 ? (
          <Empty
            title={t('scr.errPlaylists')}
            body={describeError(playlistsQ.error)}
            icon={<IconScreens size={26} />}
            action={<Button onClick={() => playlistsQ.refetch()}>{t('common.retry')}</Button>}
          />
        ) : playlists.length === 0 ? (
          <Empty
            title={t('scr.noPlaylists')}
            body={t('scr.noPlaylistsBody')}
            icon={<IconScreens size={26} />}
          />
        ) : (
          <table className="w-full text-[13px]" data-testid="playlists-table">
            <thead className="hairline border-b">
              <tr className="text-left text-[11px] font-bold uppercase tracking-[0.06em] text-ink-500">
                <th className="px-5 py-2.5">{t('scr.thPlaylist')}</th>
                <th className="px-3 py-2.5 text-right">{t('scr.thSlides')}</th>
                <th className="px-3 py-2.5 text-right">{t('scr.thDur')}</th>
                <th className="px-3 py-2.5">{t('scr.thStatus')}</th>
                <th className="px-5 py-2.5" />
              </tr>
            </thead>
            <tbody className="divide-y divide-ink-100">
              {playlists.map((p) => (
                <PlaylistRow key={p.id} p={p} />
              ))}
            </tbody>
          </table>
        )}
      </SectionCard>

      <SectionCard title={t('scr.libTitle')} subtitle={t('scr.libSub')} padded={false}>
        {slides.length === 0 ? (
          <Empty
            title={t('scr.libEmpty')}
            body={t('scr.libEmptyBody')}
            icon={<IconStack size={26} />}
            action={
              <Button leading={<IconUpload size={14} />} onClick={() => setUploadOpen(true)}>
                {t('scr.uploadSlide')}
              </Button>
            }
          />
        ) : (
          <ul className="flex flex-col" data-testid="slides-list">
            {slides.map((s) => (
              <SlideRow key={s.id} s={s} playlists={playlists} />
            ))}
          </ul>
        )}
      </SectionCard>
    </div>
  )
}

// ─── Подключённые кассы (T4) ─────────────────────────────────────────────────
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

function PlaylistRow({ p }: { p: PlaylistDto }) {
  const t = useT()
  const toast = useToast()
  const update = useUpdatePlaylist()
  const del = useDeletePlaylist()
  const pending = update.isPending || del.isPending

  // Один активный плейлист = текущий видеоряд для ВСЕХ касс (таргетинг по аптеке убран).
  const toggleStatus = () => {
    const next = p.status === 'active' ? 'draft' : 'active'
    update.mutate(
      { id: p.id, patch: { status: next } },
      { onError: () => toast.push(t('scr.statusErr')) },
    )
  }

  const handleDelete = () => {
    if (!confirm(t('scr.confirmDeletePlaylist', { name: p.name }))) return
    del.mutate(p.id, {
      onSuccess: () => toast.push(t('scr.deletedPlaylist')),
      onError: (e) => toast.push(describeError(e)),
    })
  }

  return (
    <tr data-testid={`playlist-${p.id}`}>
      <td className="px-5 py-2.5 font-extrabold text-ink-900">{p.name}</td>
      <td className="num px-3 py-2.5 text-right">{formatNum(p.slidesCount)}</td>
      <td className="num px-3 py-2.5 text-right">{fmtDuration(p.durationSec)}</td>
      <td className="px-3 py-2.5">
        <span className={`chip ${p.status === 'active' ? 'chip-green' : 'chip-ink'}`}>
          {p.status === 'active'
            ? t('scr.playing')
            : p.status === 'draft'
              ? t('scr.draft')
              : t('scr.archived')}
        </span>
      </td>
      <td className="px-5 py-2.5">
        <div className="flex items-center justify-end gap-2">
          {p.status !== 'archived' && (
            <Button variant="ghost" size="sm" disabled={pending} onClick={toggleStatus}>
              {p.status === 'active' ? t('scr.toDraft') : t('scr.activate')}
            </Button>
          )}
          <button
            type="button"
            onClick={handleDelete}
            disabled={pending}
            aria-label={t('scr.deletePlaylist')}
            data-testid={`playlist-delete-${p.id}`}
            className="text-ink-400 transition-colors hover:text-accent-danger disabled:opacity-40"
          >
            <IconTrash size={15} />
          </button>
        </div>
      </td>
    </tr>
  )
}

function SlideRow({ s, playlists }: { s: SlideDto; playlists: PlaylistDto[] }) {
  const t = useT()
  const toast = useToast()
  const assign = useAssignSlide()
  const del = useDeleteSlide()

  const onAssign = (value: string) => {
    assign.mutate(
      { id: s.id, req: { playlistId: value || null, position: s.position } },
      {
        onSuccess: () => toast.push(t('scr.assignedToast')),
        onError: (e) => toast.push(describeError(e)),
      },
    )
  }

  const handleDelete = () => {
    if (!confirm(t('scr.confirmDeleteSlide', { title: s.title }))) return
    del.mutate(s.id, {
      onSuccess: () => toast.push(t('scr.deletedSlide')),
      onError: () => toast.push(t('scr.slideDeleteErr')),
    })
  }

  const options = [
    { value: '', label: t('scr.assignNone') },
    ...playlists.map((p) => ({ value: p.id, label: p.name })),
  ]

  return (
    <li
      data-testid={`slide-${s.id}`}
      className="hairline flex items-center gap-3 border-b px-4 py-2.5 last:border-b-0"
    >
      {/* Превью: видео — мини-плеер, картинка — img. Источник — публичный MinIO-URL. */}
      <div className="h-12 w-16 flex-none overflow-hidden rounded-md bg-ink-100">
        {s.kind === 'video' ? (
          <video src={s.mediaUrl} className="h-full w-full object-cover" muted preload="metadata" />
        ) : (
          <img src={s.mediaUrl} alt={s.title} className="h-full w-full object-cover" />
        )}
      </div>
      <div className="min-w-0 flex-1">
        <div className="truncate text-[13px] font-bold text-ink-900">{s.title}</div>
        <div className="num text-[11px] text-ink-500">
          {s.kind === 'video' ? t('scr.video') : t('scr.photo')} · {fmtDuration(s.durationSec)}
        </div>
      </div>
      <Select
        value={s.playlistId ?? ''}
        onChange={onAssign}
        options={options}
        className="!w-[180px] flex-none"
      />
      <button
        type="button"
        onClick={handleDelete}
        disabled={del.isPending}
        aria-label={t('scr.deleteSlide')}
        data-testid={`slide-delete-${s.id}`}
        className="flex-none text-ink-400 transition-colors hover:text-accent-danger disabled:opacity-40"
      >
        <IconTrash size={15} />
      </button>
    </li>
  )
}

// ─────────────────────────────────────────────────────────────────────────

function UploadSlideModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const t = useT()
  const toast = useToast()
  const upload = useUploadSlide()
  const fileRef = useRef<HTMLInputElement>(null)
  const [file, setFile] = useState<File | null>(null)
  const [title, setTitle] = useState('')
  const [durationSec, setDurationSec] = useState('15')
  const [err, setErr] = useState<string | null>(null)

  useEffect(() => {
    if (open) {
      setFile(null)
      setTitle('')
      setDurationSec('15')
      setErr(null)
      if (fileRef.current) fileRef.current.value = ''
    }
  }, [open])

  const valid = !!file && title.trim().length > 0

  const submit = () => {
    if (!file || !title.trim()) return
    upload.mutate(
      { file, title: title.trim(), durationSec: Number(durationSec) || 15 },
      {
        onSuccess: () => {
          toast.push(t('scr.uploadedToast'))
          onClose()
        },
        onError: (e) => setErr(describeError(e)),
      },
    )
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={t('scr.upModalTitle')}
      subtitle={t('scr.upModalSub')}
      width={520}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button variant="primary" disabled={!valid || upload.isPending} onClick={submit}>
            {upload.isPending ? t('scr.uploading') : t('scr.upload')}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-3">
        {err && (
          <div className="rounded-lg bg-surface-danger px-3 py-2 text-[12px] font-semibold text-accent-danger">
            {err}
          </div>
        )}
        <Field label={t('scr.fldFile')}>
          <input
            ref={fileRef}
            type="file"
            accept="video/*,image/*"
            data-testid="slide-file-input"
            onChange={(e) => {
              const f = e.target.files?.[0] ?? null
              setFile(f)
              if (f && !title) setTitle(f.name.replace(/\.[^.]+$/, ''))
            }}
            className="block w-full text-[13px] text-ink-700 file:mr-3 file:rounded-lg file:border-0 file:bg-brand-green-600 file:px-3 file:py-2 file:text-[12px] file:font-bold file:text-white hover:file:bg-brand-green-700"
          />
        </Field>
        <Field label={t('scr.fldTitle')}>
          <Input
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            placeholder={t('scr.titlePh')}
          />
        </Field>
        <Field label={t('scr.fldDuration')}>
          <Input
            type="number"
            min={1}
            value={durationSec}
            onChange={(e) => setDurationSec(e.target.value)}
          />
        </Field>
      </div>
    </Modal>
  )
}
