import { useCallback, useRef, useState } from 'react'
import { Button, Field, Input, Modal, Select } from '@/ui'
import { describeError } from '@/lib/describeError'
import { usePlacementRead } from './usePlacementRead'
import { adminTaskApi } from './tasks-api'

export default function TaskTemplateDialog({
  onClose,
  onCreated,
}: {
  onClose: () => void
  onCreated: (id: string) => void
}) {
  const load = useCallback(
    async (signal: AbortSignal) => ({
      branches: await adminTaskApi.branches(signal),
      templates: await adminTaskApi.templates(signal),
    }),
    [],
  )
  const read = usePlacementRead(load)
  const [branches, setBranches] = useState<string[]>([])
  const [type, setType] = useState('')
  const [mode, setMode] = useState('occupied')
  const [month, setMonth] = useState('')
  const [due, setDue] = useState('')
  const [reward, setReward] = useState('0')
  const [products, setProducts] = useState('')
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const intent = useRef<{ id: string; body: string } | null>(null)
  const financial =
    !!branches.length &&
    branches.every((id) => read.data?.branches.find((b) => b.id === id)?.canConfigureReward)
  const template = read.data?.templates.find((t) => t.id === type)
  return (
    <Modal
      open
      onClose={() => {
        if (!pending) onClose()
      }}
      title="Задание по шаблону"
      subtitle="Один снимок полок и условий на момент назначения. Повтор сетевого запроса не создаёт дубликаты."
      width={800}
    >
      <form
        className="space-y-4"
        onSubmit={async (event) => {
          event.preventDefault()
          if (!template || !branches.length || pending || !!read.error) return
          setPending(true)
          setError('')
          try {
            const names = products
              .split('\n')
              .map((v) => v.trim())
              .filter(Boolean)
            if (names.length > 20 || branches.length > 50 || !/^\d{4}-\d{2}$/.test(month))
              throw new Error('До 50 аптек и 20 товаров; выберите месяц.')
            const payload = {
              branchIds: [...branches].sort(),
              templateType: type,
              shelfMode: mode,
              month: `${month}-01`,
              dueAt: new Date(`${due}+05:00`).toISOString(),
              rewardAmountKzt: financial ? Number(reward) : 0,
              stockProducts: names.map((name, index) => ({ key: String(index), name })),
            }
            const serialized = JSON.stringify(payload)
            if (intent.current?.body !== serialized)
              intent.current = { id: crypto.randomUUID(), body: serialized }
            const result = await adminTaskApi.templated({
              ...payload,
              requestId: intent.current.id,
            })
            onCreated(result.dispatchIds[0])
          } catch (failure) {
            setError(describeError(failure))
          } finally {
            setPending(false)
          }
        }}
      >
        {read.pending && <p role="status">Загружаем шаблоны и разрешённые аптеки…</p>}
        {!!read.error && (
          <div role="alert" className="space-y-3 rounded-xl bg-surface-danger p-4 text-sm">
            <p>{describeError(read.error)}</p>
            <Button
              type="button"
              variant="outline"
              onClick={() => {
                setBranches([])
                setType('')
                read.reload()
              }}
            >
              Повторить загрузку шаблонов и аптек
            </Button>
          </div>
        )}
        {read.data && !read.data.templates.length && (
          <p role="status" className="rounded-xl bg-paper-input p-4 text-sm text-ink-500">
            Доступных шаблонов пока нет.
          </p>
        )}
        {read.data &&
          !read.data.branches.some((branch) => branch.active && branch.createEligible) && (
            <p role="status" className="rounded-xl bg-paper-input p-4 text-sm text-ink-500">
              Нет аптек, в которых вам разрешено создать задание.
            </p>
          )}
        <Field label="Шаблон задания">
          <Select
            ariaLabel="Шаблон задания"
            value={type}
            disabled={pending || read.pending || !!read.error}
            onChange={(value) => {
              setType(value)
              const selected = read.data?.templates.find((t) => t.id === value)
              if (selected) setMode(selected.shelfMode)
            }}
            options={[
              { value: '', label: 'Выберите шаблон' },
              ...(read.data?.templates ?? []).map((t) => ({ value: t.id, label: t.title })),
            ]}
          />
        </Field>
        {template && (
          <p className="rounded-lg bg-paper-input p-3 text-sm">
            {template.instructions} Фото на пункт: {template.requiredPhotos}. Проверки:{' '}
            {template.checklist.join('; ')}.
          </p>
        )}
        <fieldset
          disabled={pending}
          className="max-h-56 space-y-2 overflow-y-auto rounded-lg border border-paper-line p-3"
        >
          <legend className="text-sm font-bold">Аптеки · выбрано {branches.length} из 50</legend>
          {read.data?.branches
            .filter((b) => b.active && b.createEligible)
            .map((b) => (
              <label
                key={b.id}
                className="flex items-start gap-3 rounded-lg p-2 text-sm leading-6 hover:bg-paper-input"
              >
                <input
                  type="checkbox"
                  checked={branches.includes(b.id)}
                  disabled={!branches.includes(b.id) && branches.length >= 50}
                  onChange={(event) =>
                    setBranches((current) =>
                      event.target.checked
                        ? [...current, b.id]
                        : current.filter((id) => id !== b.id),
                    )
                  }
                />
                {b.name} · {b.city} · {b.address}
              </label>
            ))}
        </fieldset>
        <Field label="Режим полок">
          <Select
            ariaLabel="Режим полок"
            value={mode}
            onChange={setMode}
            options={[
              { value: 'occupied', label: 'Занятые подтверждёнными размещениями' },
              { value: 'all', label: 'Все активные полки' },
              { value: 'none', label: 'Без привязки к полкам' },
            ]}
          />
        </Field>
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Месяц размещения">
            <Input
              aria-label="Месяц размещения"
              type="month"
              required
              value={month}
              onChange={(event) => setMonth(event.target.value)}
              disabled={pending}
            />
          </Field>
          <Field label="Срок, время Алматы" hint="Asia/Almaty · UTC+05:00">
            <Input
              aria-label="Срок шаблонного задания"
              type="datetime-local"
              required
              value={due}
              onChange={(event) => setDue(event.target.value)}
              disabled={pending}
            />
          </Field>
        </div>
        <Field label="Товары для подсчёта — по одному в строке" optional>
          <textarea
            aria-label="Товары шаблона"
            className="inp min-h-20 w-full"
            maxLength={4800}
            value={products}
            disabled={pending}
            onChange={(event) => setProducts(event.target.value)}
          />
        </Field>
        <Field label="Вознаграждение за пакет в каждой аптеке, ₸">
          <Input
            aria-label="Вознаграждение шаблонного задания"
            type="number"
            min={0}
            max={1000000}
            value={financial ? reward : '0'}
            disabled={pending || !financial}
            onChange={(event) => setReward(event.target.value)}
          />
        </Field>
        <p className="text-xs text-ink-500">
          Занятость проверяется по подтверждённым строкам выбранного месяца. Не более 500 пунктов
          суммарно; превышение отменяет всё назначение. Названия товаров задаются явно и не
          подменяют каталог.
        </p>
        {error && <p role="alert">{error}</p>}
        <Button
          className="w-full sm:w-auto"
          type="submit"
          disabled={pending || !template || !branches.length || !!read.error}
        >
          {pending ? 'Создаём задания…' : 'Создать задания по шаблону'}
        </Button>
      </form>
    </Modal>
  )
}
