import { useEffect, useRef, useState } from 'react'
import { Button } from '@/ui'
import { saveReport } from '@/features/reports/ledgerReports'
import type {
  CommercialGateway,
  CommercialPlan,
  CommercialPlanData,
} from './producer-commercial-api'
import {
  draftBinding,
  draftJSON,
  importDraftJSON,
  recoverCommercialDraft,
  storeCommercialDraft,
} from './commercial-draft'

export default function CommercialDraftRecovery({
  initial,
  draft,
  gateway,
  onRecover,
}: {
  initial: CommercialPlan | null
  draft: CommercialPlanData
  gateway: CommercialGateway
  onRecover: (value: CommercialPlanData) => void
}) {
  const [preview, setPreview] = useState<CommercialPlanData | null>(null)
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)
  const controller = useRef<AbortController | null>(null)
  useEffect(() => () => controller.current?.abort(), [])
  const run = async (work: (signal: AbortSignal) => Promise<void>) => {
    controller.current?.abort()
    const next = new AbortController()
    controller.current = next
    setBusy(true)
    setMessage('')
    try {
      await work(next.signal)
    } catch (error) {
      if (!next.signal.aborted)
        setMessage(error instanceof Error ? error.message : 'Не удалось восстановить черновик.')
    } finally {
      if (!next.signal.aborted) setBusy(false)
    }
  }
  return (
    <details className="rounded-lg border border-paper-line p-3">
      <summary>Сохранение и восстановление локального черновика</summary>
      <p className="my-2 text-xs text-ink-500">
        Снимок в этой сессии доступен только текущему пользователю для уже сохранённого плана. Перед
        сохранением и восстановлением повторно проверяются права, владелец и версия. Выход или смена
        аккаунта удаляет снимки. Новый несохранённый план можно явно выгрузить в JSON. Импорт не
        создаёт бронь, не переносит владельца или права и не сохраняется на сервер автоматически.
      </p>
      {message && <p role="status">{message}</p>}
      <div className="flex flex-wrap gap-2">
        <Button
          type="button"
          variant="outline"
          disabled={busy}
          onClick={() =>
            void run(async () => {
              const plan = JSON.parse(draftJSON(draft)) as CommercialPlanData
              saveReport(
                new Blob([JSON.stringify({ schemaVersion: 1, plan })], {
                  type: 'application/json',
                }),
                'placement-draft.json',
              )
              setMessage('JSON содержит только поля плана; ссылки, пароли и токены не включены.')
            })
          }
        >
          Сохранить JSON черновика
        </Button>
        {initial?.canEdit && (
          <>
            <Button
              type="button"
              variant="outline"
              disabled={busy}
              onClick={() =>
                void run(async (signal) => {
                  const binding = draftBinding(gateway.realm, initial.producerId)
                  if (!binding)
                    throw new Error(
                      'Нет подтверждённой сессии для локального снимка. Используйте JSON-файл.',
                    )
                  await storeCommercialDraft(binding, initial, draft, gateway, signal)
                  if (!signal.aborted)
                    setMessage(
                      'Локальные изменения сохранены в этой сессии для текущей версии плана.',
                    )
                })
              }
            >
              Сохранить изменения в этой сессии
            </Button>
            <Button
              type="button"
              variant="outline"
              disabled={busy}
              onClick={() =>
                void run(async (signal) => {
                  const binding = draftBinding(gateway.realm, initial.producerId)
                  if (!binding) throw new Error('Нет подтверждённой сессии.')
                  const value = await recoverCommercialDraft(
                    binding,
                    initial.id,
                    initial.revision,
                    gateway,
                    signal,
                  )
                  if (!signal.aborted) {
                    onRecover(value)
                    setMessage(
                      'Локальные изменения восстановлены в форме. Проверьте и явно сохраните план на сервере.',
                    )
                  }
                })
              }
            >
              Восстановить локальные изменения
            </Button>
          </>
        )}
      </div>
      <label className="my-3 block text-sm">
        Импорт JSON для проверки
        <input
          aria-label="Импорт JSON черновика"
          type="file"
          accept=".json,application/json"
          disabled={busy}
          onChange={(event) => {
            const file = event.target.files?.[0]
            if (!file) return
            setPreview(null)
            void run(async (signal) => {
              if (file.size > 500_000) throw new Error('JSON превышает 500 КБ.')
              const value = importDraftJSON(await file.text())
              if (!signal.aborted) setPreview(value)
            })
          }}
        />
      </label>
      {preview && (
        <div className="space-y-2 rounded border border-paper-line p-2">
          <p>
            Проверка файла: {preview.title || 'Без названия'} · {preview.startMonth} —{' '}
            {preview.endMonth}. Мест: {preview.shelfSelection?.length ?? 0}; товаров:{' '}
            {preview.productBriefs.length}; бюджетных строк: {preview.items.length}. Владелец и ID
            текущего плана сохраняются.
          </p>
          <Button
            type="button"
            disabled={busy}
            onClick={() => {
              onRecover(preview)
              setPreview(null)
              setMessage(
                'Поля перенесены только в форму. Сервер повторно проверит доступ и значения при явном сохранении.',
              )
            }}
          >
            Перенести проверенные поля в текущий черновик
          </Button>
        </div>
      )}
    </details>
  )
}
