import { useState } from 'react'
import { Button, Field, Input, Modal } from '@/ui'
import { describeError } from '@/lib/describeError'
import { producerReportApi } from './producerReports'

export default function CreateReportProducer({ onCreated }: { onCreated: (id: string) => void }) {
  const [open, setOpen] = useState(false)
  return (
    <>
      <Button variant="outline" onClick={() => setOpen(true)}>
        Добавить производителя для акций
      </Button>
      {open && (
        <CreateProducerDialog
          onClose={() => setOpen(false)}
          onCreated={(id) => {
            setOpen(false)
            onCreated(id)
          }}
        />
      )}
    </>
  )
}
function CreateProducerDialog({
  onCreated,
  onClose,
}: {
  onCreated: (id: string) => void
  onClose: () => void
}) {
  const [requestId] = useState(() => crypto.randomUUID())
  const [name, setName] = useState('')
  const [reference, setReference] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  const [submitted, setSubmitted] = useState(false)
  const [pending, setPending] = useState(false)
  const [failure, setFailure] = useState('')
  const hasControl = Array.from(name + reference).some((char) => {
    const code = char.charCodeAt(0)
    return code < 32 || (code >= 127 && code <= 159)
  })
  const valid = name.trim().length > 0 && reference.trim().length > 0 && !hasControl && confirmed
  const save = async () => {
    if (!valid || pending) return
    setPending(true)
    setSubmitted(true)
    setFailure('')
    try {
      const result = await producerReportApi.createProducer({
        requestId,
        name: name.trim(),
        reference: reference.trim(),
      })
      onCreated(result.id)
    } catch (error) {
      setFailure(describeError(error))
    } finally {
      setPending(false)
    }
  }
  return (
    <Modal
      open
      title="Добавить производителя для акций"
      onClose={() => {
        if (!pending) onClose()
      }}
      footer={
        <>
          <Button variant="ghost" disabled={pending} onClick={onClose}>
            Отмена
          </Button>
          <Button disabled={!valid || pending} onClick={save}>
            {pending
              ? 'Сохраняем…'
              : failure
                ? 'Повторить тот же запрос'
                : 'Добавить производителя'}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <p className="text-[13px] text-ink-500">
          Это организация для подтверждённых связей акций и отчётов. Телефон, вход по SMS и
          пользовательский аккаунт не создаются. Кампания не связывается автоматически.
        </p>
        <Field label="Проверенное название компании">
          <Input
            aria-label="Название производителя для акций"
            value={name}
            maxLength={255}
            disabled={pending || submitted}
            onChange={(event) => setName(event.target.value)}
          />
        </Field>
        <Field
          label="Основание проверки компании"
          hint="Внутренний документ или номер заявки, без паролей и персональных данных."
        >
          <Input
            aria-label="Основание нового производителя"
            value={reference}
            maxLength={1000}
            disabled={pending || submitted}
            onChange={(event) => setReference(event.target.value)}
          />
        </Field>
        <label className="flex gap-2 text-[13px]">
          <input
            type="checkbox"
            checked={confirmed}
            disabled={pending || submitted}
            onChange={(event) => setConfirmed(event.target.checked)}
          />
          Я проверил организацию и основание; похожее название бренда не является подтверждением.
        </label>
        {failure && (
          <p role="alert" className="text-[13px] text-accent-danger">
            {failure} Повтор использует тот же запрос и данные. Перед созданием другой записи
            проверьте реестр: предыдущий запрос мог завершиться на сервере.
          </p>
        )}
      </div>
    </Modal>
  )
}
