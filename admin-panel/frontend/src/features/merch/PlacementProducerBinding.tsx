import { useState } from 'react'
import { api } from '@/lib/api'
import { Button, Field, Input, Select } from '@/ui'
import { describeError } from '@/lib/describeError'
import type { Placement, ProducerOption } from './placements-api'

export default function PlacementProducerBinding({
  placement,
  producers,
  onChanged,
}: {
  placement: Placement
  producers: ProducerOption[]
  onChanged: () => void
}) {
  const [open, setOpen] = useState(false)
  const [producerId, setProducerId] = useState('')
  const [reason, setReason] = useState('')
  const [password, setPassword] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  const [pending, setPending] = useState(false)
  const [failure, setFailure] = useState('')
  if (
    placement.canBindProducer !== true ||
    placement.producerBindingStatus !== 'unresolved' ||
    placement.producerId !== null
  )
    return null
  const options = producers.filter(
    (producer) => producer.active && producer.profileComplete !== false && !!producer.name,
  )
  const selected = options.find((producer) => producer.id === producerId)
  const save = async () => {
    if (pending || failure || !confirmed || !selected || reason.trim().length < 3 || !password)
      return
    setPending(true)
    try {
      await api.post(
        `/api/admin/merch/placements/${encodeURIComponent(placement.id)}/producer-binding`,
        {
          expectedRevision: placement.revision,
          producerId,
          reason: reason.trim(),
          currentPassword: password,
        },
      )
      setOpen(false)
      setReason('')
      onChanged()
    } catch (error) {
      setFailure(describeError(error))
    } finally {
      setPassword('')
      setPending(false)
    }
  }
  return (
    <div className="rounded-lg border border-paper-line p-3">
      {!open ? (
        <Button type="button" variant="outline" onClick={() => setOpen(true)}>
          Подтвердить производителя медиаплана
        </Button>
      ) : (
        <div className="flex flex-col gap-3">
          <p className="text-[13px]">
            Связь устанавливается только для этого исторического медиаплана без владельца. Поиск по
            похожему названию не подтверждает компанию. Уже установленную связь изменить нельзя.
          </p>
          <Field label="Подтверждённая компания">
            <Select
              ariaLabel="Компания для связи медиаплана"
              value={producerId}
              disabled={pending || !!failure}
              onChange={setProducerId}
              options={[
                { value: '', label: 'Выберите проверенную компанию' },
                ...options.map((producer) => ({ value: producer.id, label: producer.name! })),
              ]}
            />
          </Field>
          <Field
            label="Внутреннее основание"
            hint="Сохраняется только во внутреннем аудите, не в комментариях производителя."
          >
            <Input
              aria-label="Внутреннее основание связи компании"
              value={reason}
              maxLength={1000}
              disabled={pending || !!failure}
              onChange={(event) => setReason(event.target.value)}
            />
          </Field>
          <label className="flex gap-2 text-[13px]">
            <input
              type="checkbox"
              checked={confirmed}
              disabled={pending || !!failure}
              onChange={(event) => setConfirmed(event.target.checked)}
            />
            Я проверил связь с выбранной компанией: {selected?.name ?? 'компания не выбрана'}.
          </label>
          <Field label="Ваш текущий пароль">
            <Input
              aria-label="Пароль подтверждения связи медиаплана"
              type="password"
              autoComplete="current-password"
              maxLength={128}
              value={password}
              disabled={pending || !!failure}
              onChange={(event) => setPassword(event.target.value)}
            />
          </Field>
          <div className="flex gap-2">
            <Button
              type="button"
              disabled={
                pending ||
                !!failure ||
                !selected ||
                !confirmed ||
                reason.trim().length < 3 ||
                !password
              }
              onClick={save}
            >
              Подтвердить связь
            </Button>
            <Button
              type="button"
              variant="ghost"
              disabled={pending}
              onClick={() => {
                setOpen(false)
                setPassword('')
                setReason('')
                setConfirmed(false)
                setProducerId('')
                setFailure('')
              }}
            >
              Отмена
            </Button>
          </div>
          {failure && (
            <div role="alert" className="text-[13px] text-accent-danger">
              <p>{failure} Действие не повторяется автоматически.</p>
              <Button type="button" className="mt-2" variant="outline" onClick={onChanged}>
                Загрузить актуальную версию
              </Button>
            </div>
          )}
        </div>
      )}
    </div>
  )
}
