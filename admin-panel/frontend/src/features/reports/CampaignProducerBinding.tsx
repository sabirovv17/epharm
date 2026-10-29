import { useCallback, useState } from 'react'
import { usePlacementRead } from '@/features/merch/usePlacementRead'
import { Button, Field, Input, Modal, Select } from '@/ui'
import { describeError } from '@/lib/describeError'
import {
  producerReportApi,
  type CampaignProducerBinding as Binding,
  type ReportProducer,
} from './producerReports'

const validDate = (date: string) =>
  /^\d{4}-\d{2}-\d{2}$/.test(date) &&
  date.slice(0, 4) !== '0000' &&
  Number.isFinite(Date.parse(`${date}T00:00:00Z`)) &&
  new Date(`${date}T00:00:00Z`).toISOString().slice(0, 10) === date
export default function CampaignProducerBinding({
  campaignId,
  producers,
  onChanged,
}: {
  campaignId: string
  producers: ReportProducer[]
  onChanged: () => void
}) {
  const load = useCallback(
    (signal: AbortSignal) => producerReportApi.binding(campaignId, signal),
    [campaignId],
  )
  const read = usePlacementRead(load)
  const [mode, setMode] = useState<'bind' | Binding | null>(null)
  const [producerId, setProducerId] = useState('')
  const [wholeHistory, setWholeHistory] = useState(false)
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [reference, setReference] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  const [pending, setPending] = useState(false)
  const [failure, setFailure] = useState('')
  const [denied, setDenied] = useState(false)
  const eligible = producers.filter((producer) => producer.active && producer.profileComplete)
  const valid =
    confirmed &&
    reference.trim().length > 0 &&
    (mode !== 'bind' ||
      (eligible.some((producer) => producer.id === producerId) &&
        (wholeHistory || (validDate(from) && validDate(to) && from <= to))))
  const open = (next: typeof mode) => {
    setMode(next)
    setProducerId('')
    setWholeHistory(false)
    setFrom('')
    setTo('')
    setReference('')
    setConfirmed(false)
  }
  const reload = () => {
    setFailure('')
    setDenied(false)
    setMode(null)
    read.reload()
  }
  const save = async () => {
    if (!read.data || pending || !mode || !valid || failure) return
    setPending(true)
    try {
      if (mode === 'bind')
        await producerReportApi.bind(campaignId, {
          expectedRevision: read.data.revision,
          producerId,
          wholeHistory,
          from: wholeHistory ? null : from,
          to: wholeHistory ? null : to,
          reference: reference.trim(),
        })
      else
        await producerReportApi.retract(campaignId, {
          expectedRevision: read.data.revision,
          bindingId: mode.id,
          reference: reference.trim(),
        })
      setMode(null)
      setReference('')
      read.reload()
      onChanged()
    } catch (error) {
      setMode(null)
      setReference('')
      setFailure(describeError(error))
      const status = (error as { response?: { status?: number } })?.response?.status
      setDenied(status === 401 || status === 403)
    } finally {
      setPending(false)
    }
  }
  return (
    <div className="rounded-lg border border-paper-line p-4">
      <h3 className="font-bold">Подтверждённые связи выбранной кампании</h3>
      <p className="mt-1 text-[12px] text-ink-500">
        Связи добавляются и отзываются с сохранением истории. Они определяют состав отчёта, не
        меняют кассовые правила, бонусы или права.
      </p>
      {read.pending && <p role="status">Загружаем историю связей…</p>}
      {(!!read.error || !!failure) && (
        <div role="alert" className="mt-3 text-[13px] text-accent-danger">
          <p>{failure || describeError(read.error)} Изменение не повторяется автоматически.</p>
          <Button variant="outline" className="mt-2" onClick={reload}>
            Обновить историю связей
          </Button>
        </div>
      )}
      {read.data && !denied && (
        <>
          <p className="my-2 text-[12px] text-ink-500">Версия: {read.data.revision}</p>
          {read.data.bindings.map((binding) => (
            <div
              key={binding.id}
              className="flex items-center justify-between gap-3 border-t border-paper-line py-3 text-[13px]"
            >
              <div>
                <p className="font-semibold">{binding.producerName ?? 'Компания не указана'}</p>
                <p className="text-ink-500">
                  {binding.wholeHistory
                    ? 'Вся история кампании'
                    : `${binding.from} — ${binding.to}, включительно`}
                  {binding.retractionRevision !== null ? ' · Отозвана' : ' · Действует'}
                </p>
              </div>
              {binding.retractionRevision === null && (
                <Button
                  size="sm"
                  variant="outline"
                  disabled={pending || !!failure}
                  onClick={() => open(binding)}
                >
                  Отозвать связь
                </Button>
              )}
            </div>
          ))}
          {!read.data.bindings.length && (
            <p className="my-2 text-[13px] text-ink-500">Подтверждённых связей пока нет.</p>
          )}
          <Button
            variant="outline"
            disabled={pending || !!failure || !eligible.length}
            onClick={() => open('bind')}
          >
            Подтвердить связь с производителем
          </Button>
        </>
      )}
      {mode && read.data && (
        <Modal
          open
          title={mode === 'bind' ? 'Подтвердить связь кампании' : 'Отозвать связь кампании'}
          onClose={() => {
            if (!pending) open(null)
          }}
          footer={
            <>
              <Button variant="ghost" disabled={pending} onClick={() => open(null)}>
                Отмена
              </Button>
              <Button disabled={pending || !valid || !!failure} onClick={save}>
                {pending ? 'Сохраняем…' : 'Подтвердить изменение связи'}
              </Button>
            </>
          }
        >
          <div className="flex flex-col gap-4">
            {mode === 'bind' ? (
              <>
                <Field label="Проверенный производитель">
                  <Select
                    ariaLabel="Производитель связи кампании"
                    value={producerId}
                    onChange={setProducerId}
                    disabled={pending}
                    options={eligible.map((producer) => ({
                      value: producer.id,
                      label: producer.name ?? 'Компания не указана',
                    }))}
                  />
                </Field>
                <label className="flex gap-2 text-[13px]">
                  <input
                    type="checkbox"
                    checked={wholeHistory}
                    disabled={pending}
                    onChange={(event) => {
                      setWholeHistory(event.target.checked)
                      setFrom('')
                      setTo('')
                    }}
                  />
                  Явно подтвердить всю историю кампании
                </label>
                {!wholeHistory && (
                  <div className="grid grid-cols-2 gap-3">
                    <Field label="Начало подтверждённого периода">
                      <Input
                        aria-label="Начало связи кампании"
                        type="date"
                        value={from}
                        disabled={pending}
                        onChange={(event) => setFrom(event.target.value)}
                      />
                    </Field>
                    <Field label="Окончание подтверждённого периода">
                      <Input
                        aria-label="Окончание связи кампании"
                        type="date"
                        value={to}
                        disabled={pending}
                        onChange={(event) => setTo(event.target.value)}
                      />
                    </Field>
                  </div>
                )}
              </>
            ) : (
              <p className="text-[13px]">
                Будет отозвана связь с компанией «{mode.producerName ?? 'Компания не указана'}».
                Предыдущая запись останется в истории; повторный отчёт перестанет учитывать её
                период.
              </p>
            )}
            <Field
              label="Основание подтверждения или отзыва"
              hint="Внутренняя ссылка или номер проверенного документа; до 1000 символов."
            >
              <Input
                aria-label="Основание связи кампании"
                maxLength={1000}
                value={reference}
                disabled={pending}
                onChange={(event) => setReference(event.target.value)}
              />
            </Field>
            <label className="flex gap-2 text-[13px]">
              <input
                type="checkbox"
                checked={confirmed}
                disabled={pending}
                onChange={(event) => setConfirmed(event.target.checked)}
              />
              Я проверил производителя и период. Совпадение бренда или названия не является
              подтверждением.
            </label>
          </div>
        </Modal>
      )}
    </div>
  )
}
