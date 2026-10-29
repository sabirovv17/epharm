import { useCallback, useEffect, useState } from 'react'
import { Button, Field, Input } from '@/ui'
import { formatKzt } from '@/mocks/fixtures'
import { usePlacementRead } from './usePlacementRead'
interface ExecutorContext {
  dispatchId: string
  taskTitle: string
  pharmacyName: string
  amountKzt: number
  dueAt: string
  challengeExpiresAt: string
  phoneMasked: string
  bindingId: string | null
  currency: string
}
interface SmsChallenge {
  otpChallengeId: string
  phoneMasked: string
  expiresAt: string
  retryAfterSeconds: number
}
export default function NativeTaskExecutor({
  id,
  token,
  request,
  onVerified,
}: {
  id: string
  token: string | null
  request: <T>(path: string, init?: RequestInit) => Promise<T>
  onVerified: () => void
}) {
  if (!token)
    return (
      <div className="card p-5">
        <h2 className="text-lg font-bold">Подтвердите исполнителя задания</h2>
        <p className="mt-2 text-sm text-ink-500">
          Откройте исходный QR-код на кассе заново. Ссылка подтверждения не сохраняется в браузере.
        </p>
      </div>
    )
  return <ExecutorVerification id={id} token={token} request={request} onVerified={onVerified} />
}
function ExecutorVerification({
  id,
  token,
  request,
  onVerified,
}: {
  id: string
  token: string
  request: <T>(path: string, init?: RequestInit) => Promise<T>
  onVerified: () => void
}) {
  const base = `/api/mobile/merch/tasks/${encodeURIComponent(id)}/executor`
  const load = useCallback(
    (signal: AbortSignal) =>
      request<ExecutorContext>(base, { signal, headers: { 'X-Merch-Task-Token': token } }),
    [request, base, token],
  )
  const context = usePlacementRead(load)
  const [challenge, setChallenge] = useState<SmsChallenge | null>(null)
  const [code, setCode] = useState('')
  const [retryAt, setRetryAt] = useState(0)
  const [now, setNow] = useState(Date.now)
  const [pending, setPending] = useState(false)
  const [failure, setFailure] = useState('')
  useEffect(() => {
    if (context.data?.dispatchId === id && context.data.bindingId) onVerified()
  }, [context.data, id, onVerified])
  useEffect(() => {
    if (!challenge) return
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [challenge])
  const send = async () => {
    if (pending || now < retryAt) return
    setPending(true)
    setFailure('')
    setCode('')
    try {
      const next = await request<SmsChallenge>(`${base}/sms/request`, {
        method: 'POST',
        headers: { 'X-Merch-Task-Token': token },
      })
      setChallenge(next)
      setNow(Date.now())
      setRetryAt(Date.now() + next.retryAfterSeconds * 1000)
    } catch (error) {
      setFailure(error instanceof Error ? error.message : 'Не удалось отправить код.')
    } finally {
      setPending(false)
    }
  }
  const verify = async () => {
    if (!challenge || pending || !/^\d{4,8}$/.test(code)) return
    setPending(true)
    setFailure('')
    try {
      const result = await request<{ bindingId: string; dispatchId: string }>(
        `${base}/sms/verify`,
        {
          method: 'POST',
          headers: { 'X-Merch-Task-Token': token },
          body: JSON.stringify({ otpChallengeId: challenge.otpChallengeId, code }),
        },
      )
      if (!result.bindingId || result.dispatchId !== id)
        throw new Error('Сервис не подтвердил привязку этого задания. Откройте QR-код заново.')
      setCode('')
      onVerified()
    } catch (error) {
      setFailure(error instanceof Error ? error.message : 'Не удалось подтвердить код.')
      setCode('')
    } finally {
      setPending(false)
    }
  }
  if (context.pending) return <p role="status">Проверяем исполнителя задания…</p>
  if (context.error || !context.data || context.data.dispatchId !== id)
    return (
      <div role="alert" className="card p-5">
        <p>
          {context.error instanceof Error
            ? context.error.message
            : 'Не удалось подтвердить контекст задания. Откройте QR-код заново.'}
        </p>
        <Button className="mt-3" variant="outline" onClick={context.reload}>
          Повторить проверку
        </Button>
      </div>
    )
  return (
    <section className="card flex max-w-2xl flex-col gap-4 p-5">
      <h2 className="text-xl font-bold">Подтвердите исполнителя</h2>
      <p className="text-sm">
        {context.data.taskTitle} · {context.data.pharmacyName}
      </p>
      <p className="text-sm text-ink-500">
        Предусмотрено: {formatKzt(context.data.amountKzt)}. Код отправляется на телефон вашего
        личного аккаунта: {context.data.phoneMasked}. Подтверждение связывает задание с вами;
        начисление — после проверки выполнения.
      </p>
      {!challenge ? (
        <Button disabled={pending} onClick={send}>
          {pending ? 'Отправляем…' : 'Получить код подтверждения'}
        </Button>
      ) : (
        <>
          <Field label="Код из SMS">
            <Input
              aria-label="SMS-код исполнителя"
              inputMode="numeric"
              autoComplete="one-time-code"
              maxLength={8}
              value={code}
              disabled={pending}
              onChange={(event) => setCode(event.target.value.replace(/\D/g, ''))}
            />
          </Field>
          <Button disabled={pending || !/^\d{4,8}$/.test(code)} onClick={verify}>
            {pending ? 'Проверяем…' : 'Подтвердить исполнителя'}
          </Button>
          <Button variant="ghost" disabled={pending || now < retryAt} onClick={send}>
            {now < retryAt
              ? `Повторить через ${Math.ceil((retryAt - now) / 1000)} с`
              : 'Отправить код ещё раз'}
          </Button>
        </>
      )}
      {failure && (
        <p role="alert" className="text-sm text-accent-danger">
          {failure}
        </p>
      )}
    </section>
  )
}
