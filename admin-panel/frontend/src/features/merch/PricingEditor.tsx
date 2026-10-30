import { useEffect, useRef, useState } from 'react'
import { Button, Field, Input } from '@/ui'
import { describeError } from '@/lib/describeError'
import { tariffGateway, type Tariff, type TariffGateway, type TariffValues } from './tariffs-api'

const moneyFields = [
  ['facingMonthPrice', 'Фейсинг в месяц, ₸'],
  ['promotionMonthPrice', 'Промоакция в аптеке, ₸'],
  ['trainingEventPrice', 'Одно обучение, ₸'],
  ['motivationPersonPrice', 'Мотивация за участника, ₸'],
] as const

export default function PricingEditor({ gateway = tariffGateway }: { gateway?: TariffGateway }) {
  const [data, setData] = useState<{ items: Tariff[]; canEdit: boolean } | null>(null)
  const [error, setError] = useState('')
  const [generation, setGeneration] = useState(0)
  useEffect(() => {
    const controller = new AbortController()
    gateway
      .load(controller.signal)
      .then((value) => {
        if (!controller.signal.aborted) setData(value)
      })
      .catch((failure) => {
        if (!controller.signal.aborted) setError(describeError(failure))
      })
    return () => controller.abort()
  }, [gateway, generation])
  const reload = () => {
    setData(null)
    setError('')
    setGeneration((old) => old + 1)
  }
  return (
    <section className="card mb-5 space-y-4 p-5" aria-label="Тарифная матрица поставщиков">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="text-lg font-bold">Тарифы поставщиков</h2>
        <Button type="button" variant="outline" onClick={reload}>
          Загрузить актуальные тарифы
        </Button>
      </div>
      <p className="text-sm text-ink-500">
        Изменения применяются к новым расчётам. Уже сохранённые заявки, цены размещений и начисления
        не пересчитываются. Обновление страницы тарифов заменит несохранённый ввод.
      </p>
      {error ? (
        <p role="alert">{error}</p>
      ) : !data ? (
        <p role="status">Загружаем тарифы…</p>
      ) : (
        <>
          {!data.canEdit && (
            <p role="status">
              Просмотр. Для изменения нужны отдельные общесетевые права редактирования и финансовых
              данных.
            </p>
          )}
          {data.items.length === 0 && (
            <p>Тарифов пока нет. Исходные категории не создаются автоматически.</p>
          )}
          <div className="grid gap-4 xl:grid-cols-2">
            {data.items.map((item) => (
              <TariffForm
                key={`${generation}:${item.category}`}
                item={item}
                editable={data.canEdit && item.canEdit}
                gateway={gateway}
                onSaved={(saved) =>
                  setData(
                    (old) =>
                      old && {
                        ...old,
                        items: old.items.map((row) =>
                          row.category === saved.category ? saved : row,
                        ),
                      },
                  )
                }
              />
            ))}
          </div>
        </>
      )}
    </section>
  )
}

function TariffForm({
  item,
  editable,
  gateway,
  onSaved,
}: {
  item: Tariff
  editable: boolean
  gateway: TariffGateway
  onSaved: (value: Tariff) => void
}) {
  const [values, setValues] = useState<TariffValues>({ ...item.values })
  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState('')
  const [saved, setSaved] = useState(false)
  const alive = useRef(true)
  useEffect(() => {
    alive.current = true
    return () => {
      alive.current = false
    }
  }, [])
  const disabled = !editable || busy || !!failure
  const dirty = JSON.stringify(values) !== JSON.stringify(item.values)
  const patch = (value: Partial<TariffValues>) => {
    setSaved(false)
    setValues((old) => ({ ...old, ...value }))
  }
  const save = async (event: React.FormEvent) => {
    event.preventDefault()
    if (disabled || !dirty) return
    setBusy(true)
    try {
      const result = await gateway.save(item.category, item.updatedAt, values)
      if (alive.current) {
        setValues(result.values)
        onSaved(result)
        setSaved(true)
      }
    } catch (error) {
      if (alive.current) setFailure(describeError(error))
    } finally {
      if (alive.current) setBusy(false)
    }
  }
  return (
    <form
      className="space-y-3 rounded-xl border border-paper-line p-4"
      onSubmit={(event) => {
        void save(event)
      }}
      aria-label={`Тариф ${item.category}`}
    >
      <div className="text-xs text-ink-500">
        {item.category} · Обновлён {item.updatedAt}
      </div>
      <Field label="Название категории">
        <Input
          value={values.label}
          disabled={disabled}
          required
          maxLength={200}
          onChange={(event) => patch({ label: event.target.value })}
        />
      </Field>
      <Field label="Коэффициент">
        <Input
          value={values.multiplier}
          disabled={disabled}
          inputMode="decimal"
          required
          pattern="[0-9]+([.][0-9]{1,3})?"
          onChange={(event) => patch({ multiplier: event.target.value })}
        />
      </Field>
      <div className="grid gap-3 sm:grid-cols-2">
        {moneyFields.map(([key, label]) => (
          <Field key={key} label={label}>
            <Input
              value={values[key]}
              disabled={disabled}
              inputMode="numeric"
              required
              pattern="[0-9]{1,19}"
              onChange={(event) => patch({ [key]: event.target.value })}
            />
          </Field>
        ))}
      </div>
      <label className="flex gap-2 text-sm">
        <input
          type="checkbox"
          checked={values.active}
          disabled={disabled}
          onChange={(event) => patch({ active: event.target.checked })}
        />{' '}
        Тариф активен
      </label>
      {failure && (
        <p role="alert">
          {failure}. Ввод сохранён только в этом окне. Загрузите актуальные тарифы перед следующей
          попыткой.
        </p>
      )}
      {saved && <p role="status">Тариф сохранён</p>}
      <Button type="submit" disabled={disabled || !dirty}>
        {busy ? 'Сохраняем…' : 'Сохранить тариф'}
      </Button>
    </form>
  )
}
