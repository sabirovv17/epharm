// Banners — управление баннерами главного экрана мобильного приложения (п.5).
// UX 1:1 с разделом Screens: список карточек (превью + заголовок + статус),
// кнопка «Создать», модалка-форма (картинка через загрузку в MinIO + заголовок +
// подзаголовок + детальный текст + статус + позиция), редактирование, архив/удаление.
//
// Картинка грузится отдельным запросом (POST /image → imageUrl) — как слайды в
// ScreensPage. Тап по баннеру в приложении ведёт на детальный экран (detailMd).

import { useEffect, useRef, useState } from 'react'
import { Button, Empty, Field, Input, Modal, PageHeader, SectionCard, Select, useToast } from '@/ui'
import { IconLayers, IconTrash, IconUpload } from '@/ui/icons'
import type { BannerDto, BannerStatus, CreateBannerRequest } from '@/lib/api-types'
import {
  useBanners,
  useCreateBanner,
  useDeleteBanner,
  useUpdateBanner,
  useUploadBannerImage,
} from '@/lib/queries/banners'
import { describeError } from '@/lib/describeError'
import { useT } from '@/i18n'

export default function BannersPage() {
  const t = useT()
  // editing === null → форма закрыта; 'new' → создание; BannerDto → редактирование.
  const [editing, setEditing] = useState<BannerDto | 'new' | null>(null)

  const bannersQ = useBanners()
  const banners = bannersQ.data ?? []

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title={t('page.banners.title')}
        subtitle={t('page.banners.subtitle')}
        actions={
          <Button
            variant="primary"
            leading={<IconUpload size={14} />}
            onClick={() => setEditing('new')}
            data-testid="banners-create"
          >
            {t('bn.create')}
          </Button>
        }
      />

      <BannerFormModal
        open={editing !== null}
        banner={editing === 'new' ? null : editing}
        onClose={() => setEditing(null)}
      />

      <SectionCard title={t('bn.listTitle')} subtitle={t('bn.listSub')} padded={false}>
        {bannersQ.isLoading && banners.length === 0 ? (
          <Empty
            title={t('bn.loading')}
            body={t('common.connecting')}
            icon={<IconLayers size={26} />}
          />
        ) : bannersQ.isError && banners.length === 0 ? (
          <Empty
            title={t('bn.errList')}
            body={describeError(bannersQ.error)}
            icon={<IconLayers size={26} />}
            action={<Button onClick={() => bannersQ.refetch()}>{t('common.retry')}</Button>}
          />
        ) : banners.length === 0 ? (
          <Empty
            title={t('bn.empty')}
            body={t('bn.emptyBody')}
            icon={<IconLayers size={26} />}
            action={
              <Button leading={<IconUpload size={14} />} onClick={() => setEditing('new')}>
                {t('bn.create')}
              </Button>
            }
          />
        ) : (
          <ul className="flex flex-col" data-testid="banners-list">
            {banners.map((b) => (
              <BannerRow key={b.id} b={b} onEdit={() => setEditing(b)} />
            ))}
          </ul>
        )}
      </SectionCard>
    </div>
  )
}

// ─── Строка баннера ──────────────────────────────────────────────────────────
function BannerRow({ b, onEdit }: { b: BannerDto; onEdit: () => void }) {
  const t = useT()
  const toast = useToast()
  const update = useUpdateBanner()
  const del = useDeleteBanner()
  const pending = update.isPending || del.isPending

  // Тоггл статуса: active ↔ draft (архив выставляется отдельной кнопкой).
  const toggleStatus = () => {
    const next: BannerStatus = b.status === 'active' ? 'draft' : 'active'
    update.mutate(
      { id: b.id, patch: { status: next } },
      { onError: () => toast.push(t('bn.statusErr')) },
    )
  }

  const archive = () => {
    update.mutate(
      { id: b.id, patch: { status: 'archived' } },
      { onError: () => toast.push(t('bn.statusErr')) },
    )
  }

  const handleDelete = () => {
    if (!confirm(t('bn.confirmDelete', { title: b.title }))) return
    del.mutate(b.id, {
      onSuccess: () => toast.push(t('bn.deleted')),
      onError: (e) => toast.push(describeError(e)),
    })
  }

  const chipCls =
    b.status === 'active' ? 'chip-green' : b.status === 'draft' ? 'chip-ink' : 'chip-ink'
  const statusLabel =
    b.status === 'active'
      ? t('bn.status.active')
      : b.status === 'draft'
        ? t('bn.status.draft')
        : t('bn.status.archived')

  return (
    <li
      data-testid={`banner-${b.id}`}
      className="hairline flex items-center gap-3 border-b px-4 py-2.5 last:border-b-0"
    >
      {/* Превью картинки — публичный MinIO-URL. */}
      <div className="h-12 w-20 flex-none overflow-hidden rounded-md bg-ink-100">
        <img src={b.imageUrl} alt={b.title} className="h-full w-full object-cover" />
      </div>
      <div className="min-w-0 flex-1">
        <div className="truncate text-[13px] font-bold text-ink-900">{b.title}</div>
        <div className="truncate text-[11px] text-ink-500">{b.subtitle || t('bn.noSubtitle')}</div>
      </div>
      <span className="num flex-none text-[11px] text-ink-400">
        {t('bn.position')} {b.position}
      </span>
      <span className={`chip flex-none ${chipCls}`}>{statusLabel}</span>
      <div className="flex flex-none items-center gap-2">
        <Button variant="ghost" size="sm" disabled={pending} onClick={onEdit}>
          {t('bn.edit')}
        </Button>
        {b.status !== 'archived' ? (
          <Button variant="ghost" size="sm" disabled={pending} onClick={archive}>
            {t('bn.archive')}
          </Button>
        ) : (
          <Button variant="ghost" size="sm" disabled={pending} onClick={toggleStatus}>
            {t('bn.activate')}
          </Button>
        )}
        <button
          type="button"
          onClick={handleDelete}
          disabled={pending}
          aria-label={t('bn.delete')}
          data-testid={`banner-delete-${b.id}`}
          className="text-ink-400 transition-colors hover:text-accent-danger disabled:opacity-40"
        >
          <IconTrash size={15} />
        </button>
      </div>
    </li>
  )
}

// ─── Модалка создания/редактирования ─────────────────────────────────────────

interface FormState {
  title: string
  subtitle: string
  imageUrl: string
  detailMd: string
  status: BannerStatus
  position: string
}

function initialForm(banner: BannerDto | null): FormState {
  return {
    title: banner?.title ?? '',
    subtitle: banner?.subtitle ?? '',
    imageUrl: banner?.imageUrl ?? '',
    detailMd: banner?.detailMd ?? '',
    status: banner?.status ?? 'draft',
    position: banner ? String(banner.position) : '0',
  }
}

function BannerFormModal({
  open,
  banner,
  onClose,
}: {
  open: boolean
  banner: BannerDto | null
  onClose: () => void
}) {
  const t = useT()
  const toast = useToast()
  const create = useCreateBanner()
  const update = useUpdateBanner()
  const uploadImage = useUploadBannerImage()
  const fileRef = useRef<HTMLInputElement>(null)
  const [form, setForm] = useState<FormState>(() => initialForm(banner))
  const [err, setErr] = useState<string | null>(null)

  const isEdit = banner !== null

  // Сброс формы при каждом открытии (или смене редактируемого баннера).
  useEffect(() => {
    if (open) {
      setForm(initialForm(banner))
      setErr(null)
      if (fileRef.current) fileRef.current.value = ''
    }
  }, [open, banner])

  // Загрузка картинки сразу при выборе файла → imageUrl (как слайды в Screens).
  const onPickFile = (file: File | null) => {
    if (!file) return
    setErr(null)
    uploadImage.mutate(file, {
      onSuccess: (res) => setForm((f) => ({ ...f, imageUrl: res.imageUrl })),
      onError: () => setErr(t('bn.imageUploadErr')),
    })
  }

  const valid = form.title.trim().length > 0 && form.imageUrl.trim().length > 0
  const busy = create.isPending || update.isPending || uploadImage.isPending

  const submit = () => {
    if (!valid) {
      if (!form.imageUrl.trim()) setErr(t('bn.imageRequired'))
      return
    }
    const position = Math.max(0, Math.trunc(Number(form.position) || 0))
    const payload: CreateBannerRequest = {
      title: form.title.trim(),
      // Пустая строка очищает подзаголовок/детальный текст (backend трактует "" как очистку).
      subtitle: form.subtitle.trim(),
      imageUrl: form.imageUrl.trim(),
      detailMd: form.detailMd.trim(),
      status: form.status,
      position,
    }

    if (isEdit && banner) {
      update.mutate(
        { id: banner.id, patch: payload },
        {
          onSuccess: () => {
            toast.push(t('bn.savedToast'))
            onClose()
          },
          onError: (e) => setErr(describeError(e)),
        },
      )
    } else {
      create.mutate(payload, {
        onSuccess: () => {
          toast.push(t('bn.createdToast'))
          onClose()
        },
        onError: (e) => setErr(describeError(e)),
      })
    }
  }

  const statusOptions = [
    { value: 'draft', label: t('bn.status.draft') },
    { value: 'active', label: t('bn.status.active') },
    { value: 'archived', label: t('bn.status.archived') },
  ]

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={isEdit ? t('bn.modalEditTitle') : t('bn.modalCreateTitle')}
      subtitle={t('bn.modalSub')}
      width={560}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button variant="primary" disabled={!valid || busy} onClick={submit}>
            {isEdit
              ? update.isPending
                ? t('bn.saving')
                : t('bn.save')
              : create.isPending
                ? t('bn.creating')
                : t('bn.createCta')}
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

        <Field label={t('bn.fldImage')} hint={t('bn.imageHint')}>
          <div className="flex items-center gap-3">
            {form.imageUrl ? (
              <div className="h-16 w-28 flex-none overflow-hidden rounded-md bg-ink-100">
                <img
                  src={form.imageUrl}
                  alt={t('bn.fldImage')}
                  className="h-full w-full object-cover"
                  data-testid="banner-image-preview"
                />
              </div>
            ) : (
              <div className="flex h-16 w-28 flex-none items-center justify-center rounded-md bg-ink-100 text-ink-400">
                <IconLayers size={20} />
              </div>
            )}
            <input
              ref={fileRef}
              type="file"
              accept="image/*"
              data-testid="banner-file-input"
              onChange={(e) => onPickFile(e.target.files?.[0] ?? null)}
              className="block w-full text-[13px] text-ink-700 file:mr-3 file:rounded-lg file:border-0 file:bg-brand-green-600 file:px-3 file:py-2 file:text-[12px] file:font-bold file:text-white hover:file:bg-brand-green-700"
            />
          </div>
          {uploadImage.isPending && (
            <div className="mt-1 text-[12px] text-ink-400" data-testid="banner-uploading">
              {t('bn.uploading')}
            </div>
          )}
        </Field>

        <Field label={t('bn.fldTitle')}>
          <Input
            value={form.title}
            onChange={(e) => setForm({ ...form, title: e.target.value })}
            placeholder={t('bn.titlePh')}
          />
        </Field>

        <Field label={t('bn.fldSubtitle')} optional>
          <Input
            value={form.subtitle}
            onChange={(e) => setForm({ ...form, subtitle: e.target.value })}
            placeholder={t('bn.subtitlePh')}
          />
        </Field>

        <Field label={t('bn.fldDetail')} hint={t('bn.detailHint')} optional>
          <textarea
            className="inp"
            rows={4}
            value={form.detailMd}
            onChange={(e) => setForm({ ...form, detailMd: e.target.value })}
          />
        </Field>

        <div className="grid grid-cols-2 gap-3">
          <Field label={t('bn.fldStatus')}>
            <Select
              value={form.status}
              onChange={(v) => setForm({ ...form, status: v as BannerStatus })}
              options={statusOptions}
            />
          </Field>
          <Field label={t('bn.fldPosition')} hint={t('bn.positionHint')}>
            <Input
              type="number"
              min={0}
              value={form.position}
              onChange={(e) => setForm({ ...form, position: e.target.value })}
            />
          </Field>
        </div>
      </div>
    </Modal>
  )
}
