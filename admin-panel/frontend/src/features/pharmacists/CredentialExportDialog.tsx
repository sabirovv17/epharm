import { useEffect, useRef, useState } from 'react'
import { Button, Field, Input, Modal } from '@/ui'
import { saveReport } from '@/features/reports/ledgerReports'
import { credentialExportApi } from './credential-export-api'

export default function CredentialExportDialog({
  people,
  onClose,
}: {
  people: { id: string; name: string }[]
  onClose: () => void
}) {
  const [purpose, setPurpose] = useState('')
  const [password, setPassword] = useState('')
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const request = useRef<AbortController | null>(null)
  useEffect(() => () => request.current?.abort(), [])
  const close = () => {
    request.current?.abort()
    setPassword('')
    onClose()
  }
  return (
    <Modal
      open
      onClose={close}
      title="Учётные данные ЛК"
      subtitle="Отдельная выгрузка для уполномоченного HQ"
      width={580}
    >
      <form
        className="space-y-4"
        onSubmit={async (event) => {
          event.preventDefault()
          if (
            pending ||
            people.length < 1 ||
            people.length > 25 ||
            purpose.trim().length < 3 ||
            !password
          )
            return
          const controller = new AbortController()
          request.current?.abort()
          request.current = controller
          const confirmation = password
          setPassword('')
          setPending(true)
          setError('')
          try {
            const blob = await credentialExportApi.download(
              people.map((person) => person.id),
              purpose.trim(),
              confirmation,
              controller.signal,
            )
            if (!controller.signal.aborted) {
              saveReport(blob, 'pharmacist-credentials.xlsx')
              onClose()
            }
          } catch (failure) {
            if (!controller.signal.aborted)
              setError(failure instanceof Error ? failure.message : 'Выгрузка не выполнена.')
          } finally {
            if (!controller.signal.aborted) setPending(false)
          }
        }}
      >
        <p className="text-sm text-ink-600">
          Выбрано: {people.length}. Файл содержит действующие пароли и предназначен для защищённого
          хранения. Старые пароли без сохранённой копии отмечаются как недоступные.
        </p>
        <ul className="max-h-32 overflow-auto rounded-lg bg-paper-input p-3 text-sm">
          {people.map((person) => (
            <li key={person.id}>{person.name}</li>
          ))}
        </ul>
        <Field label="Цель выгрузки">
          <Input
            aria-label="Цель выгрузки"
            value={purpose}
            onChange={(event) => setPurpose(event.target.value)}
            minLength={3}
            maxLength={200}
            required
            disabled={pending}
          />
        </Field>
        <Field label="Текущий пароль администратора">
          <Input
            aria-label="Текущий пароль администратора"
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            maxLength={256}
            required
            disabled={pending}
          />
        </Field>
        <p className="text-xs text-ink-500">
          До 25 фармацевтов за раз, до 4 запросов в час. ИИН, телефон и логин на их основе доступны
          при отдельном праве на персональные данные.
        </p>
        {error && (
          <p role="alert" className="text-sm text-accent-danger">
            {error}
          </p>
        )}
        <div className="flex justify-end gap-2">
          <Button type="button" variant="outline" onClick={close}>
            Отмена
          </Button>
          <Button
            type="submit"
            disabled={
              pending ||
              people.length < 1 ||
              people.length > 25 ||
              purpose.trim().length < 3 ||
              !password
            }
          >
            {pending ? 'Проверяем и формируем Excel…' : 'Подтвердить и скачать'}
          </Button>
        </div>
      </form>
    </Modal>
  )
}
