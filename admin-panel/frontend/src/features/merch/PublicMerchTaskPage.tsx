import { useCallback, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { Button } from '@/ui'
import { Logo } from '@/layout/Logo'
import MerchTaskDetail from './MerchTaskDetail'
import { usePlacementRead } from './usePlacementRead'
import { taskCapabilityToken } from './taskNavigation'
import type { MerchTask, TaskAttempt, TaskEvidence, TaskGateway } from './tasks-api'

interface TaskSession {
  dispatchId: string
  branchId: string
  expiresAt: string
  readOnly: boolean
  rewardAmountKzt: number
  identityRequired: boolean
}
class CapabilityError extends Error {
  status: number
  constructor(status: number) {
    super(
      status === 401 || status === 403 || status === 404
        ? 'Ссылка недействительна или больше недоступна. Отсканируйте QR-код заново.'
        : status === 429
          ? 'Слишком много запросов. Подождите и повторите действие.'
          : 'Сервис заданий временно недоступен. Повторите позже.',
    )
    this.status = status
  }
}
function capabilityGateway(token: string) {
  const base = '/api/public/merch/tasks'
  const request = async <T,>(path: string, init: RequestInit = {}, image = false): Promise<T> => {
    if (!path.startsWith(base)) throw new CapabilityError(403)
    const form = init.body instanceof FormData
    const response = await fetch(path, {
      ...init,
      credentials: 'omit',
      cache: 'no-store',
      referrerPolicy: 'no-referrer',
      headers: {
        'X-Merch-Task-Token': token,
        ...(init.body && !form ? { 'Content-Type': 'application/json' } : {}),
      },
    })
    if (!response.ok) throw new CapabilityError(response.status)
    if (image) return (await response.blob()) as T
    return response.json() as Promise<T>
  }
  const gateway: TaskGateway = {
    realm: 'capability',
    comments: (itemId, offset, signal) =>
      request(`${base}/items/${encodeURIComponent(itemId)}/comments?limit=20&offset=${offset}`, {
        signal,
      }),
    get: (_id, signal) => request<MerchTask>(base, { signal }),
    start: (itemId) =>
      request<TaskAttempt>(`${base}/items/${encodeURIComponent(itemId)}/attempts`, {
        method: 'POST',
      }),
    upload: (itemId, uploadId, file, capture) => {
      const body = new FormData()
      body.append('file', file)
      if (capture)
        body.append('capture', new Blob([JSON.stringify(capture)], { type: 'application/json' }))
      return request<TaskEvidence>(
        `${base}/items/${encodeURIComponent(itemId)}/evidence?clientUploadId=${encodeURIComponent(uploadId)}`,
        { method: 'POST', body },
      )
    },
    saveStock: (itemId, body) =>
      request(`${base}/items/${encodeURIComponent(itemId)}/stock`, {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    reference: (itemId, signal) =>
      request<Blob>(`${base}/items/${encodeURIComponent(itemId)}/reference`, { signal }, true),
    instruction: (itemId, signal) =>
      request<Blob>(`${base}/items/${encodeURIComponent(itemId)}/instruction`, { signal }, true),
    submit: (itemId, expectedAttempt, completedChecklistIds, comment) =>
      request<TaskAttempt>(`${base}/items/${encodeURIComponent(itemId)}/submit`, {
        method: 'POST',
        body: JSON.stringify({ expectedAttempt, completedChecklistIds, comment }),
      }),
    evidence: (id, signal) =>
      request<Blob>(`${base}/evidence/${encodeURIComponent(id)}`, { signal }, true),
  }
  return {
    session: (signal: AbortSignal) => request<TaskSession>(`${base}/session`, { signal }),
    gateway,
  }
}
export default function PublicMerchTaskPage() {
  const [token] = useState(taskCapabilityToken)
  return (
    <div className="min-h-screen bg-paper">
      <header className="flex items-center gap-3 border-b border-paper-line bg-white px-5 py-4">
        <Logo size={28} />
        <span className="font-extrabold">ePharm · Задание аптеки</span>
      </header>
      <main className="mx-auto max-w-5xl px-4 py-6 sm:p-8">
        {token ? (
          <CapabilityTask token={token} />
        ) : (
          <div className="card mx-auto max-w-xl space-y-3 p-5 sm:p-8">
            <p className="text-xs font-bold uppercase tracking-wider text-brand-green-700">
              Доступ по QR-коду
            </p>
            <h1 className="text-xl font-bold">Откройте задание по QR-коду</h1>
            <p className="mt-2 text-[13px] text-ink-500">
              Ссылка не сохраняется в браузере. После перезагрузки отсканируйте QR-код на кассе
              заново.
            </p>
          </div>
        )}
      </main>
    </div>
  )
}
function CapabilityTask({ token }: { token: string }) {
  const client = useMemo(() => capabilityGateway(token), [token])
  const load = useCallback((signal: AbortSignal) => client.session(signal), [client])
  const session = usePlacementRead(load)
  if (session.pending)
    return (
      <p role="status" className="card p-5 text-sm text-ink-500 sm:p-8">
        Проверяем ссылку задания…
      </p>
    )
  if (session.error)
    return (
      <div role="alert" className="card p-6">
        <h1 className="mb-2 text-xl font-bold">Не удалось открыть задание</h1>
        <p>{session.error instanceof Error ? session.error.message : 'Ссылка недоступна.'}</p>
        <Button className="mt-3" variant="outline" onClick={session.reload}>
          Повторить проверку
        </Button>
      </div>
    )
  if (!session.data) return null
  if (session.data.identityRequired || session.data.rewardAmountKzt > 0)
    return (
      <div className="card mx-auto max-w-xl space-y-4 p-5 sm:p-8">
        <p className="text-xs font-bold uppercase tracking-wider text-brand-green-700">
          Личный вход
        </p>
        <h1 className="text-xl font-bold">
          {session.data.rewardAmountKzt > 0
            ? 'Задание с вознаграждением'
            : 'Подтвердите исполнителя'}
        </h1>
        <p className="my-4 text-sm leading-6 text-ink-500">
          Войдите в свой личный кабинет, чтобы подтвердить исполнителя. Стандарт-Н продолжает
          работать.
        </p>
        <Link
          to={`/learn/merch/tasks/${encodeURIComponent(session.data.dispatchId)}`}
          className="btn btn-md btn-primary w-full sm:w-auto"
        >
          Войти и открыть задание
        </Link>
      </div>
    )
  return (
    <div className="space-y-4">
      <div className="card space-y-3 p-5 text-sm sm:p-6">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h1 className="text-xl font-bold">Фотоотчёт аптеки</h1>
          <span className="rounded-full bg-paper-input px-3 py-1 text-xs font-semibold">
            {session.data.readOnly ? 'Только просмотр' : 'Без вознаграждения'}
          </span>
        </div>
        <p>Задание без вознаграждения. Личный вход для его выполнения не требуется.</p>
        <p className="mt-1 text-ink-500">
          Фотоотчёт проверит ответственный сотрудник.
          {session.data.readOnly ? ' Сейчас доступен только просмотр.' : ''}
        </p>
        {!session.data.readOnly && (
          <details className="border-t border-paper-line pt-3">
            <summary className="cursor-pointer py-1 font-semibold">Как выполнить задание</summary>
            <ol aria-label="Как выполнить задание" className="grid gap-3 pt-4 sm:grid-cols-3">
              <li>
                <strong className="block">1. Откройте пункт</strong>
                <span className="text-ink-500">Прочитайте условия и начните попытку.</span>
              </li>
              <li>
                <strong className="block">2. Подготовьте отчёт</strong>
                <span className="text-ink-500">
                  Загрузите доказательства и заполните обязательные поля.
                </span>
              </li>
              <li>
                <strong className="block">3. Отправьте на проверку</strong>
                <span className="text-ink-500">
                  Загрузка фото сама по себе не отправляет отчёт.
                </span>
              </li>
            </ol>
          </details>
        )}
      </div>
      <MerchTaskDetail
        id={session.data.dispatchId}
        gateway={client.gateway}
        canExecute={!session.data.readOnly}
        canReview={false}
      />
    </div>
  )
}
