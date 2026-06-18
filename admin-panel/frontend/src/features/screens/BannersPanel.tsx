// BannersPanel — управление баннерами главного экрана приложения. Живёт внутри
// раздела «Управление экранами» (вкладка «Баннеры приложения»).
//
// Максимально простой UX (требование продукта):
//   • кнопка «Добавить баннер» (в шапке вкладок, ScreensPage) открывает форму;
//   • форма — всего 4 поля: картинка + заголовок + подзаголовок + детальный текст;
//   • статус и порядок задаются прямо в списке — переключатель «Показывать»
//     (active↔draft) и стрелки ↑↓. Позиция новому баннеру назначается автоматически
//     (в конец), поэтому в форме её нет.
//
// Картинка грузится отдельным запросом (POST /image → imageUrl), как слайды в Screens.
// Тап по баннеру в приложении ведёт на детальный экран (detailMd).

import { useEffect, useRef, useState } from 'react'
import { Button, Empty, Field, Input, Modal, SectionCard, Toggle, useToast } from '@/ui'
import { IconArrowDown, IconArrowUp, IconEdit, IconLayers, IconPlus, IconTrash } from '@/ui/icons'
import type { BannerDto, CreateBannerRequest } from '@/lib/api-types'
import {
  useBanners,
  useCreateBanner,
  useDeleteBanner,
  useReorderBanner,
  useUpdateBanner,
  useUploadBannerImage,
} from '@/lib/queries/banners'
import { describeError } from '@/lib/describeError'
import { useT } from '@/i18n'

// editing === null → форма закрыта; 'new' → создание; BannerDto → редактирование.
export type BannerEditing = BannerDto | 'new' | null

export function BannersPanel({
  editing,
  setEditing,
}: {
  editing: BannerEditing
  setEditing: (e: BannerEditing) => void
}) {
  const t = useT()
  const toast = useToast()
  const reorder = useReorderBanner()

  const bannersQ = useBanners()
  const banners = bannersQ.data ?? []

  // Сдвиг баннера в списке: меняем местами с соседом и нормализуем позиции
  // (position = индекс) — патчим только тех, у кого позиция реально изменилась.
  const move = (index: number, dir: -1 | 1) => {
    const target = index + dir
    if (target < 0 || target >= banners.length) return
    const next = [...banners]
    ;[next[index], next[target]] = [next[target], next[index]]
    const updates: { id: string; position: number }[] = []
    next.forEach((b, idx) => {
      if (b.position !== idx) updates.push({ id: b.id, position: idx })
    })
    if (updates.length === 0) return
    reorder.mutate(updates, { onError: () => toast.push(t('bn.reorderErr')) })
  }

  return (
    <>
      <BannerFormModal
        open={editing !== null}
        banner={editing === 'new' ? null : editing}
        nextPosition={banners.length}
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
              <Button
                leading={<IconPlus size={14} />}
                onClick={() => setEditing('new')}
                data-testid="banners-create-empty"
              >
                {t('bn.add')}
              </Button>
            }
          />
        ) : (
          <ul className="flex flex-col" data-testid="banners-list">
            {banners.map((b, i) => (
              <BannerRow
                key={b.id}
                b={b}
                canUp={i > 0}
                canDown={i < banners.length - 1}
                reordering={reorder.isPending}
                onEdit={() => setEditing(b)}
                onMoveUp={() => move(i, -1)}
                onMoveDown={() => move(i, 1)}
              />
            ))}
          </ul>
        )}
      </SectionCard>
    </>
  )
}

// ─── Строка баннера ──────────────────────────────────────────────────────────
function BannerRow({
  b,
  canUp,
  canDown,
  reordering,
  onEdit,
  onMoveUp,
  onMoveDown,
}: {
  b: BannerDto
  canUp: boolean
  canDown: boolean
  reordering: boolean
  onEdit: () => void
  onMoveUp: () => void
  onMoveDown: () => void
}) {
  const t = useT()
  const toast = useToast()
  const update = useUpdateBanner()
  const del = useDeleteBanner()
  const pending = update.isPending || del.isPending

  // Переключатель «Показывать»: active ↔ draft (скрытый = черновик, не в ленте).
  const toggleShown = (on: boolean) => {
    update.mutate(
      { id: b.id, patch: { status: on ? 'active' : 'draft' } },
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

  const shown = b.status === 'active'

  return (
    <li
      data-testid={`banner-${b.id}`}
      className="hairline flex items-center gap-3 border-b px-4 py-2.5 last:border-b-0"
    >
      {/* Переупорядочивание стрелками ↑↓. */}
      <div className="flex flex-none flex-col text-ink-300">
        <button
          type="button"
          onClick={onMoveUp}
          disabled={!canUp || reordering}
          aria-label={t('bn.moveUp')}
          data-testid={`banner-up-${b.id}`}
          className="leading-none transition-colors hover:text-ink-700 disabled:opacity-30"
        >
          <IconArrowUp size={14} />
        </button>
        <button
          type="button"
          onClick={onMoveDown}
          disabled={!canDown || reordering}
          aria-label={t('bn.moveDown')}
          data-testid={`banner-down-${b.id}`}
          className="leading-none transition-colors hover:text-ink-700 disabled:opacity-30"
        >
          <IconArrowDown size={14} />
        </button>
      </div>

      {/* Превью картинки — публичный MinIO-URL. */}
      <div className="h-12 w-20 flex-none overflow-hidden rounded-md bg-ink-100">
        {b.imageUrl ? (
          <img src={b.imageUrl} alt={b.title} className="h-full w-full object-cover" />
        ) : (
          <div className="flex h-full w-full items-center justify-center text-ink-400">
            <IconLayers size={18} />
          </div>
        )}
      </div>

      <div className="min-w-0 flex-1">
        <div className="truncate text-[13px] font-bold text-ink-900">{b.title}</div>
        <div className="truncate text-[11px] text-ink-500">{b.subtitle || t('bn.noSubtitle')}</div>
      </div>

      {/* Показывать / Скрыт. */}
      <div className="flex flex-none items-center gap-2">
        <span
          className={`text-[11px] font-semibold ${shown ? 'text-brand-green-700' : 'text-ink-400'}`}
        >
          {shown ? t('bn.shown') : t('bn.hidden')}
        </span>
        <Toggle on={shown} onChange={toggleShown} disabled={pending} />
      </div>

      {/* Редактировать + удалить. */}
      <Button
        variant="ghost"
        size="sm"
        disabled={pending}
        onClick={onEdit}
        leading={<IconEdit size={13} />}
        data-testid={`banner-edit-${b.id}`}
      >
        {t('bn.edit')}
      </Button>
      <button
        type="button"
        onClick={handleDelete}
        disabled={pending}
        aria-label={t('bn.delete')}
        data-testid={`banner-delete-${b.id}`}
        className="flex-none text-ink-400 transition-colors hover:text-accent-danger disabled:opacity-40"
      >
        <IconTrash size={15} />
      </button>
    </li>
  )
}

// ─── Модалка создания/редактирования (4 поля) ─────────────────────────────────

interface FormState {
  title: string
  subtitle: string
  imageUrl: string
  detailMd: string
}

function initialForm(banner: BannerDto | null): FormState {
  return {
    title: banner?.title ?? '',
    subtitle: banner?.subtitle ?? '',
    imageUrl: banner?.imageUrl ?? '',
    detailMd: banner?.detailMd ?? '',
  }
}

function BannerFormModal({
  open,
  banner,
  nextPosition,
  onClose,
}: {
  open: boolean
  banner: BannerDto | null
  nextPosition: number
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
    if (isEdit && banner) {
      // Редактирование — патчим только контент (статус/позиция управляются в списке).
      update.mutate(
        {
          id: banner.id,
          patch: {
            title: form.title.trim(),
            subtitle: form.subtitle.trim(),
            imageUrl: form.imageUrl.trim(),
            detailMd: form.detailMd.trim(),
          },
        },
        {
          onSuccess: () => {
            toast.push(t('bn.savedToast'))
            onClose()
          },
          onError: (e) => setErr(describeError(e)),
        },
      )
    } else {
      // Создание — новый баннер сразу активен и встаёт в конец ленты.
      const payload: CreateBannerRequest = {
        title: form.title.trim(),
        subtitle: form.subtitle.trim(),
        imageUrl: form.imageUrl.trim(),
        detailMd: form.detailMd.trim(),
        status: 'active',
        position: nextPosition,
      }
      create.mutate(payload, {
        onSuccess: () => {
          toast.push(t('bn.createdToast'))
          onClose()
        },
        onError: (e) => setErr(describeError(e)),
      })
    }
  }

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
      </div>
    </Modal>
  )
}
