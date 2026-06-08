// Screens — управление indoor-DOOH экранами в аптеках (ТЗ §3.3).
// Загрузка медиа (видео/картинки) в MinIO, сборка плейлистов, активация ротации.
// Назначение плейлистов на конкретные аптеки + расписание + рендер на 2-м мониторе
// = Этап 5 (POSM). Расписание остаётся Empty до этого этапа.

import { useEffect, useRef, useState } from 'react'
import { Button, Empty, Field, Input, Modal, PageHeader, SectionCard, Select, useToast } from '@/ui'
import { IconCalendar, IconPlus, IconScreens, IconStack, IconTrash, IconUpload } from '@/ui/icons'
import type { CreatePlaylistRequest, PharmacyDto, PlaylistDto, SlideDto } from '@/lib/api-types'
import {
  useAssignSlide,
  useCreatePlaylist,
  useDeletePlaylist,
  useDeleteSlide,
  usePlaylists,
  useSlides,
  useUpdatePlaylist,
  useUploadSlide,
} from '@/lib/queries/screens'
import { usePharmacies } from '@/lib/queries/pharmacies'
import { describeError } from '@/lib/describeError'
import { formatNum } from '@/mocks/fixtures'
import { useT } from '@/i18n'

export default function ScreensPage() {
  const t = useT()
  const [uploadOpen, setUploadOpen] = useState(false)
  const [createOpen, setCreateOpen] = useState(false)

  const playlistsQ = usePlaylists()
  const slidesQ = useSlides()
  const pharmaciesQ = usePharmacies()
  const playlists = playlistsQ.data ?? []
  const slides = slidesQ.data ?? []
  const pharmacies = pharmaciesQ.data ?? []

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title={t('page.screens.title')}
        subtitle={t('page.screens.subtitle')}
        actions={
          <>
            <Button
              variant="outline"
              leading={<IconPlus size={14} />}
              onClick={() => setCreateOpen(true)}
              data-testid="screens-new-playlist"
            >
              {t('scr.newPlaylist')}
            </Button>
            <Button
              variant="primary"
              leading={<IconUpload size={14} />}
              onClick={() => setUploadOpen(true)}
              data-testid="screens-upload-slide"
            >
              {t('scr.uploadSlide')}
            </Button>
          </>
        }
      />

      <UploadSlideModal open={uploadOpen} onClose={() => setUploadOpen(false)} />
      <CreatePlaylistModal open={createOpen} onClose={() => setCreateOpen(false)} />

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
            action={
              <Button leading={<IconPlus size={14} />} onClick={() => setCreateOpen(true)}>
                {t('scr.newPlaylist')}
              </Button>
            }
          />
        ) : (
          <table className="w-full text-[13px]" data-testid="playlists-table">
            <thead className="hairline border-b">
              <tr className="text-left text-[11px] font-bold uppercase tracking-[0.06em] text-ink-500">
                <th className="px-5 py-2.5">{t('scr.thPlaylist')}</th>
                <th className="px-3 py-2.5 text-right">{t('scr.thSlides')}</th>
                <th className="px-3 py-2.5 text-right">{t('scr.thDur')}</th>
                <th className="px-3 py-2.5 text-right">{t('scr.thPharm')}</th>
                <th className="px-3 py-2.5">{t('scr.thTarget')}</th>
                <th className="px-3 py-2.5">{t('scr.thStatus')}</th>
                <th className="px-5 py-2.5" />
              </tr>
            </thead>
            <tbody className="divide-y divide-ink-100">
              {playlists.map((p) => (
                <PlaylistRow key={p.id} p={p} pharmacies={pharmacies} />
              ))}
            </tbody>
          </table>
        )}
      </SectionCard>

      <div className="grid grid-cols-2 gap-4">
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

        <SectionCard title={t('scr.schedTitle')} subtitle={t('scr.schedSub')} padded={false}>
          <Empty
            title={t('scr.schedEmpty')}
            body={t('scr.schedEmptyBody')}
            icon={<IconCalendar size={26} />}
          />
        </SectionCard>
      </div>
    </div>
  )
}

function fmtDuration(sec: number): string {
  const m = Math.floor(sec / 60)
  const s = sec % 60
  return m > 0 ? `${m}м ${s}с` : `${s}с`
}

function PlaylistRow({ p, pharmacies }: { p: PlaylistDto; pharmacies: PharmacyDto[] }) {
  const t = useT()
  const toast = useToast()
  const update = useUpdatePlaylist()
  const del = useDeletePlaylist()
  const pending = update.isPending || del.isPending

  const toggleStatus = () => {
    const next = p.status === 'active' ? 'draft' : 'active'
    update.mutate(
      { id: p.id, patch: { status: next } },
      { onError: () => toast.push(t('scr.statusErr')) },
    )
  }

  // Назначение: «Все экраны» (null) или конкретная аптека. Sentinel '__all__' — чтобы пустое
  // значение не конфликтовало с placeholder-логикой Select. setTarget=true применяет выбор.
  const ALL = '__all__'
  const targetOptions = [
    { value: ALL, label: t('scr.targetAll') },
    ...pharmacies.map((ph) => ({ value: ph.id, label: `${ph.name} · ${ph.city}` })),
  ]
  const onTarget = (value: string) => {
    const target = value === ALL ? null : value
    update.mutate(
      { id: p.id, patch: { setTarget: true, targetPharmacyId: target } },
      {
        onSuccess: () => toast.push(target ? t('scr.targetSetOne') : t('scr.targetSetAll')),
        onError: (e) => toast.push(describeError(e)),
      },
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
      <td className="num px-3 py-2.5 text-right">{formatNum(p.pharmacies)}</td>
      <td className="px-3 py-2.5" data-testid={`playlist-target-${p.id}`}>
        <Select
          value={p.pharmacyId ?? ALL}
          onChange={onTarget}
          options={targetOptions}
          className="!w-[190px]"
        />
      </td>
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

function CreatePlaylistModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const t = useT()
  const toast = useToast()
  const create = useCreatePlaylist()
  const [name, setName] = useState('')
  const [err, setErr] = useState<string | null>(null)

  useEffect(() => {
    if (open) {
      setName('')
      setErr(null)
    }
  }, [open])

  const submit = () => {
    if (!name.trim()) return
    const req: CreatePlaylistRequest = { name: name.trim() }
    create.mutate(req, {
      onSuccess: () => {
        toast.push(t('scr.createdPlaylist'))
        onClose()
      },
      onError: (e) => setErr(describeError(e)),
    })
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={t('scr.plModalTitle')}
      subtitle={t('scr.plModalSub')}
      width={460}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button variant="primary" disabled={!name.trim() || create.isPending} onClick={submit}>
            {create.isPending ? t('scr.creating') : t('scr.create')}
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
        <Field label={t('scr.fldName')}>
          <Input
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder={t('scr.plNamePh')}
            autoFocus
          />
        </Field>
      </div>
    </Modal>
  )
}
