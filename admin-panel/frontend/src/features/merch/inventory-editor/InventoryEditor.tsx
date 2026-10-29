import { useEffect, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Button, Field, Input, Modal } from '@/ui'
import { describeError } from '@/lib/describeError'
import { inventoryEditorApi, type InventoryEditorState } from './api'
import InventoryStructureForms from './InventoryStructureForms'
import InventoryFloorPlan from './InventoryFloorPlan'
import InventoryPrices from './InventoryPrices'
import NetworkStructure from './NetworkStructure'

export type EditorWrite = (work: () => Promise<unknown>) => Promise<boolean>
export interface EditorProps {
  data: InventoryEditorState
  write: EditorWrite
  busy: boolean
}
export function NetworkStructureButton({ onChanged }: { onChanged: () => void }) {
  const [open, setOpen] = useState(false)
  return (
    <>
      <Button variant="outline" onClick={() => setOpen(true)}>
        Структура сети
      </Button>
      {open && <NetworkStructure onChanged={onChanged} onClose={() => setOpen(false)} />}
    </>
  )
}
export default function InventoryEditor({
  branchId,
  month,
  onChanged,
  onClose,
}: {
  branchId: string
  month: string
  onChanged: () => void
  onClose: () => void
}) {
  const client = useQueryClient()
  const [tab, setTab] = useState('passport')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  const [blocked, setBlocked] = useState(false)
  const query = useQuery({
    queryKey: ['admin-access', 'merch-inventory-editor', branchId, month],
    queryFn: ({ signal }) => inventoryEditorApi.get(branchId, `${month}-01`, signal),
    retry: false,
  })
  const write: EditorWrite = async (work) => {
    if (busy || blocked || !query.isSuccess || query.isError || query.isFetching) return false
    setBusy(true)
    setError('')
    setMessage('')
    try {
      const result = await work()
      const prices = result as { changedCount?: number; protectedCount?: number }
      setMessage(
        typeof prices?.changedCount === 'number'
          ? `Изменено ячеек: ${prices.changedCount}. Защищено размещениями: ${prices.protectedCount ?? 0}.`
          : 'Изменения сохранены',
      )
      await query.refetch()
      await client.invalidateQueries({ queryKey: ['admin-access', 'merch-inventory'] })
      onChanged()
      return true
    } catch (failure) {
      setError(describeError(failure))
      setBlocked(true)
      return false
    } finally {
      setBusy(false)
    }
  }
  const ready =
    query.isSuccess && query.isFetchedAfterMount && !query.isError && !query.isFetching && !blocked
  return (
    <Modal
      open
      onClose={() => {
        if (!busy) onClose()
      }}
      title="Паспорт и рекламные места"
      subtitle="Архив сохраняет историю. Цены и публикация схемы — отдельные явные действия."
      width={1180}
    >
      {(query.isPending || query.isFetching) && <p role="status">Загружаем актуальные данные…</p>}
      {(error || query.isError) && (
        <div role="alert" className="mb-4 rounded-xl bg-surface-danger p-4">
          <p>{error || describeError(query.error)}</p>
          <Button
            variant="outline"
            onClick={async () => {
              const result = await query.refetch()
              if (!result.isError) {
                setBlocked(false)
                setError('')
              }
            }}
          >
            Обновить данные
          </Button>
        </div>
      )}
      {message && (
        <p role="status" className="mb-4 rounded-xl bg-paper-input p-3">
          {message}
        </p>
      )}
      {ready && (
        <div key={`${branchId}:${query.data.revision}`}>
          <h2 className="font-bold">
            {query.data.branch.name} · {query.data.branch.city}
          </h2>
          <p className="mb-4 text-sm text-ink-500">{query.data.branch.address}</p>
          <div className="mb-5 flex flex-wrap gap-2" role="tablist" aria-label="Разделы паспорта">
            {[
              ['passport', 'Паспорт и фотографии'],
              ['structure', 'Шкафы и полки'],
              ['prices', 'Цены'],
              ['floor', 'Схема помещения'],
            ].map(([id, title]) => (
              <Button
                key={id}
                variant={tab === id ? 'primary' : 'outline'}
                role="tab"
                aria-selected={tab === id}
                onClick={() => setTab(id)}
              >
                {title}
              </Button>
            ))}
          </div>
          {tab === 'passport' && <Passport data={query.data} write={write} busy={busy} />}
          {tab === 'structure' && (
            <InventoryStructureForms data={query.data} write={write} busy={busy} />
          )}
          {tab === 'prices' && (
            <InventoryPrices data={query.data} write={write} busy={busy} month={month} />
          )}
          {tab === 'floor' && <InventoryFloorPlan data={query.data} write={write} busy={busy} />}
        </div>
      )}
    </Modal>
  )
}
function Passport({ data, write, busy }: EditorProps) {
  const [form, setForm] = useState({
    organizationId: data.branch.organizationId,
    code: data.branch.code,
    name: data.branch.name,
    address: data.branch.address,
    city: data.branch.city,
    division: data.branch.division,
    latitude: data.latitude,
    longitude: data.longitude,
    active: data.branch.active,
    traffic: data.traffic || {
      otsPerDay: null,
      vehicleTrafficPerDay: null,
      pedestrianTrafficPerDay: null,
      source: null,
      measuredAt: null,
    },
  })
  const [angle, setAngle] = useState('facade')
  const [shelfId, setShelfId] = useState('')
  const [caption, setCaption] = useState('')
  const [file, setFile] = useState<File | null>(null)
  const [history, setHistory] = useState(false)
  return (
    <div className="space-y-6">
      <form
        onSubmit={(event) => {
          event.preventDefault()
          void write(() => inventoryEditorApi.branch(data.branch.id, data.revision, form))
        }}
        className="space-y-3"
      >
        <div className="grid gap-3 md:grid-cols-2">
          {(['code', 'name', 'address', 'city', 'division'] as const).map((key) => (
            <Field
              key={key}
              label={
                {
                  code: 'Код аптеки',
                  name: 'Название',
                  address: 'Адрес',
                  city: 'Город',
                  division: 'Дивизион (метка)',
                }[key]
              }
            >
              <Input
                aria-label={
                  {
                    code: 'Код аптеки',
                    name: 'Название',
                    address: 'Адрес',
                    city: 'Город',
                    division: 'Дивизион (метка)',
                  }[key]
                }
                value={form[key] ?? ''}
                required={key !== 'division'}
                disabled={!data.canEditStructure || busy}
                onChange={(e) => setForm({ ...form, [key]: e.target.value })}
              />
            </Field>
          ))}
          {(['latitude', 'longitude'] as const).map((key) => (
            <Field key={key} label={key === 'latitude' ? 'Широта' : 'Долгота'}>
              <Input
                aria-label={key === 'latitude' ? 'Широта' : 'Долгота'}
                type="number"
                step="0.000001"
                disabled={!data.canEditStructure || busy}
                value={form[key] ?? ''}
                onChange={(e) =>
                  setForm({ ...form, [key]: e.target.value === '' ? null : Number(e.target.value) })
                }
              />
            </Field>
          ))}
        </div>
        <div className="grid gap-3 md:grid-cols-3">
          {(['otsPerDay', 'vehicleTrafficPerDay', 'pedestrianTrafficPerDay'] as const).map(
            (field) => (
              <Field
                key={field}
                label={
                  {
                    otsPerDay: 'Посещаемость / день',
                    vehicleTrafficPerDay: 'Автотрафик / день',
                    pedestrianTrafficPerDay: 'Пешеходы / день',
                  }[field]
                }
              >
                <Input
                  aria-label={
                    {
                      otsPerDay: 'Посещаемость / день',
                      vehicleTrafficPerDay: 'Автотрафик / день',
                      pedestrianTrafficPerDay: 'Пешеходы / день',
                    }[field]
                  }
                  type="number"
                  min={0}
                  max={1000000000}
                  disabled={!data.canEditStructure || busy}
                  value={form.traffic[field] ?? ''}
                  onChange={(e) =>
                    setForm({
                      ...form,
                      traffic: {
                        ...form.traffic,
                        [field]: e.target.value === '' ? null : Number(e.target.value),
                      },
                    })
                  }
                />
              </Field>
            ),
          )}
          <Field label="Источник трафика">
            <Input
              aria-label="Источник трафика"
              maxLength={255}
              disabled={!data.canEditStructure || busy}
              value={form.traffic.source || ''}
              onChange={(e) =>
                setForm({ ...form, traffic: { ...form.traffic, source: e.target.value || null } })
              }
            />
          </Field>
          <Field label="Дата измерения трафика">
            <Input
              aria-label="Дата измерения трафика"
              type="date"
              disabled={!data.canEditStructure || busy}
              value={form.traffic.measuredAt || ''}
              onChange={(e) =>
                setForm({
                  ...form,
                  traffic: { ...form.traffic, measuredAt: e.target.value || null },
                })
              }
            />
          </Field>
        </div>
        <label className="flex gap-2 text-sm">
          <input
            type="checkbox"
            checked={form.active}
            disabled={!data.canEditStructure || busy}
            onChange={(e) => setForm({ ...form, active: e.target.checked })}
          />
          Аптека активна
        </label>
        <p className="text-xs text-ink-500">
          Метка дивизиона не назначает сотрудников и не выдаёт права. Связь с аптекой ePharm здесь
          не меняется.
        </p>
        {data.canEditStructure && (
          <Button type="submit" disabled={busy}>
            Сохранить паспорт
          </Button>
        )}
      </form>
      <div className="border-t border-ink-100 pt-5">
        <h3 className="font-bold">Фотопаспорт и эталоны полок</h3>
        <p className="mt-1 text-sm text-ink-500">
          Фасад, вход, зал, касса, панорама и фотографии рекламных мест доступны только
          авторизованным сотрудникам.
        </p>
        {data.canEditPassport && (
          <form
            className="my-4 flex flex-wrap items-end gap-3"
            onSubmit={(e) => {
              e.preventDefault()
              if (file)
                void write(() =>
                  inventoryEditorApi.upload(
                    data.branch.id,
                    data.revision,
                    file,
                    angle,
                    caption,
                    angle === 'shelf' ? shelfId : null,
                  ),
                )
            }}
          >
            <Field label="Ракурс">
              <select
                className="inp"
                aria-label="Ракурс"
                value={angle}
                onChange={(e) => setAngle(e.target.value)}
                disabled={busy}
              >
                {[
                  ['facade', 'Фасад'],
                  ['entrance', 'Вход'],
                  ['hall', 'Торговый зал'],
                  ['checkout', 'Кассовая зона'],
                  ['panorama', 'Панорама'],
                  ['shelf', 'Эталон полки'],
                ].map(([id, title]) => (
                  <option key={id} value={id}>
                    {title}
                  </option>
                ))}
              </select>
            </Field>
            {angle === 'shelf' && (
              <Field label="Полка для фотографии">
                <select
                  className="inp"
                  aria-label="Полка для фотографии"
                  value={shelfId}
                  onChange={(e) => setShelfId(e.target.value)}
                  required
                >
                  <option value="">Выберите полку</option>
                  {data.shelves.map(({ shelf }) => (
                    <option key={shelf.id} value={shelf.id}>
                      {shelf.code}
                    </option>
                  ))}
                </select>
              </Field>
            )}
            <Field label="Подпись">
              <Input
                aria-label="Подпись"
                maxLength={300}
                value={caption}
                onChange={(e) => setCaption(e.target.value)}
              />
            </Field>
            <Field label="Файл JPG/PNG">
              <input
                aria-label="Файл JPG/PNG"
                type="file"
                accept="image/jpeg,image/png"
                onChange={(e) => setFile(e.target.files?.[0] ?? null)}
                required
              />
            </Field>
            <Button type="submit" disabled={busy || !file || (angle === 'shelf' && !shelfId)}>
              Добавить фото
            </Button>
          </form>
        )}
        <label className="my-3 flex gap-2 text-sm">
          <input type="checkbox" checked={history} onChange={(e) => setHistory(e.target.checked)} />
          Показывать архив фотографий
        </label>
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {data.photos
            .filter((photo) => history || !photo.archived)
            .map((photo) => (
              <figure key={photo.id} className="rounded-xl border border-ink-100 p-3">
                <PrivatePhoto branch={data.branch.id} id={photo.id} />
                <figcaption className="mt-2 text-sm">
                  {photo.caption || photo.angle}
                  {photo.archived && ' · Архив'}
                </figcaption>
                {data.canEditPassport && !photo.archived && (
                  <Button
                    variant="outline"
                    disabled={busy}
                    onClick={() => {
                      if (
                        window.confirm(
                          'Убрать фото из действующего паспорта? Файл и история сохранятся.',
                        )
                      )
                        void write(() =>
                          inventoryEditorApi.archivePhoto(data.branch.id, data.revision, photo.id),
                        )
                    }}
                  >
                    В архив
                  </Button>
                )}
              </figure>
            ))}
        </div>
        {!data.photos.some((photo) => !photo.archived) && (
          <p className="py-4 text-sm text-ink-500">Фотографии пока не добавлены.</p>
        )}
      </div>
    </div>
  )
}
function PrivatePhoto({ branch, id }: { branch: string; id: string }) {
  const [url, setUrl] = useState('')
  const [error, setError] = useState('')
  useEffect(() => {
    const controller = new AbortController()
    let objectUrl = ''
    inventoryEditorApi
      .photo(branch, id, controller.signal)
      .then((blob) => {
        if (!controller.signal.aborted) {
          objectUrl = URL.createObjectURL(blob)
          setUrl(objectUrl)
        }
      })
      .catch((failure) => {
        if (!controller.signal.aborted) setError(describeError(failure))
      })
    return () => {
      controller.abort()
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
  }, [branch, id])
  return url ? (
    <img
      className="h-40 w-full rounded-lg object-contain"
      src={url}
      alt="Фотография аптеки или рекламного места"
    />
  ) : (
    <p role={error ? 'alert' : 'status'}>{error || 'Загружаем фото…'}</p>
  )
}
