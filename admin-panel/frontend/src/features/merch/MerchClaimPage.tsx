import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { ArrowRight, CheckCircle2, LockKeyhole, ShieldCheck } from 'lucide-react'
import { merchClaimChallenge, trustedMerchRedirect } from './claimNavigation'

type Tokens = { accessToken: string; refreshToken: string }
type ClaimContext = {
  dispatchId: string
  taskTitle: string
  pharmacyName: string
  amountKzt: number
  currency: 'KZT'
  challengeExpiresAt: string
  phoneMasked: string
}
type OtpChallenge = { otpChallengeId: string; phoneMasked: string; expiresAt: string; retryAfterSeconds: number }
type Grant = { grant: string; expiresAt: string; redirectUrl: string }
const TOKEN_KEY = 'epharm.learner.tokens'
const BASE = '/api/mobile/merch/executor-grants'
const REQUEST_ERROR = 'Не удалось выполнить запрос. Повторите попытку.'

function validTokens(value: unknown): value is Tokens {
  const candidate = value as Partial<Tokens> | null
  return typeof candidate?.accessToken === 'string' && candidate.accessToken.length > 0 &&
    typeof candidate?.refreshToken === 'string' && candidate.refreshToken.length > 0
}

function storedTokens(): Tokens | null {
  try {
    const value = JSON.parse(sessionStorage.getItem(TOKEN_KEY) || 'null')
    return validTokens(value) ? value : null
  } catch { return null }
}

class ClaimError extends Error {
  readonly status: number
  constructor(message: string, status: number) { super(message); this.status = status }
}

function responseError(code: unknown, status: number): string {
  // Never reflect upstream messages: a malformed error can contain a capability or a return URL.
  const messages: Record<string, string> = {
    INVALID_CREDENTIALS: 'Неверный ИИН или пароль.',
    USER_NOT_ACTIVE: 'Учётная запись не активна. Обратитесь к ответственному сотруднику.',
    PHARMACIST_BLOCKED: 'Учётная запись заблокирована. Обратитесь к ответственному сотруднику.',
    FORBIDDEN: 'Задание недоступно этой учётной записи или относится к другой аптеке.',
    CONFLICT: 'Исполнитель этого задания уже подтверждён. Вернитесь к заданию в CRM.',
    VALIDATION_FAILED: 'Ссылка или данные подтверждения недействительны. Отсканируйте QR-код заново.',
    OTP_INVALID: 'Неверный код подтверждения. Проверьте SMS и повторите попытку.',
    OTP_EXPIRED: 'Код подтверждения истёк. Запросите новый код.',
    OTP_TOO_MANY_ATTEMPTS: 'Слишком много попыток подтверждения. Подождите и запросите новый код.',
    OTP_RESEND_TOO_SOON: 'Новый код пока нельзя отправить. Подождите минуту.',
    SMS_SEND_FAILED: 'Не удалось отправить SMS. Повторите попытку позже.',
    OTP_PROVIDER_UNAVAILABLE: 'Сервис подтверждения временно недоступен. Повторите попытку позже.',
  }
  if (typeof code === 'string' && Object.hasOwn(messages, code)) return messages[code]
  if (status === 401) return 'Войдите в учётную запись фармацевта заново.'
  if (status === 403) return messages.FORBIDDEN
  if (status === 429) return 'Слишком много запросов. Подождите и повторите попытку.'
  return REQUEST_ERROR
}

function failureMessage(failure: unknown): string {
  return failure instanceof ClaimError ? failure.message : REQUEST_ERROR
}

async function post<T>(path: string, body: unknown, tokens?: Tokens): Promise<T> {
  const response = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...(tokens ? { Authorization: `Bearer ${tokens.accessToken}` } : {}) },
    body: JSON.stringify(body),
    cache: 'no-store',
    credentials: 'omit',
  })
  const result = await response.json().catch(() => null)
  if (!response.ok) throw new ClaimError(responseError(result?.code, response.status), response.status)
  return result as T
}

export default function MerchClaimPage() {
  const [challenge] = useState(merchClaimChallenge)
  const [tokens, setTokens] = useState<Tokens | null>(storedTokens)
  const [context, setContext] = useState<ClaimContext | null>(null)
  const [otp, setOtp] = useState<OtpChallenge | null>(null)
  const [retryAt, setRetryAt] = useState(0)
  const [now, setNow] = useState(Date.now)
  const [iin, setIin] = useState('')
  const [password, setPassword] = useState('')
  const [code, setCode] = useState('')
  const [busy, setBusy] = useState(false)
  const [loading, setLoading] = useState(Boolean(tokens && challenge))
  const [contextAttempt, setContextAttempt] = useState(0)
  const [error, setError] = useState('')
  const [handoff, setHandoff] = useState<{ url: string; expiresAt: string } | null>(null)

  useEffect(() => {
    const hadClass = document.body.classList.contains('learner-portal')
    document.body.classList.add('learner-portal')
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => {
      window.clearInterval(timer)
      if (!hadClass) document.body.classList.remove('learner-portal')
    }
  }, [])

  const saveTokens = useCallback((next: Tokens | null) => {
    if (next) sessionStorage.setItem(TOKEN_KEY, JSON.stringify(next))
    else sessionStorage.removeItem(TOKEN_KEY)
    setTokens(next)
  }, [])

  const authorized = useCallback(async <T,>(path: string, body: unknown, active: Tokens): Promise<T> => {
    try { return await post<T>(path, body, active) }
    catch (failure) {
      if (!(failure instanceof ClaimError) || failure.status !== 401) throw failure
      try {
        const renewed = await post<{ tokens: Tokens }>('/api/mobile/auth/refresh', { refreshToken: active.refreshToken })
        if (!validTokens(renewed?.tokens)) throw new ClaimError('Войдите в учётную запись фармацевта заново.', 401)
        saveTokens(renewed.tokens)
        return await post<T>(path, body, renewed.tokens)
      } catch (refreshFailure) {
        if (refreshFailure instanceof ClaimError && refreshFailure.status === 401) saveTokens(null)
        throw refreshFailure
      }
    }
  }, [saveTokens])

  useEffect(() => {
    if (!tokens || !challenge) return
    let cancelled = false
    const timer = window.setTimeout(() => {
      authorized<ClaimContext>(`${BASE}/context`, { crmChallenge: challenge }, tokens)
        .then((value) => {
          if (!value || value.currency !== 'KZT' || !Number.isSafeInteger(value.amountKzt) || value.amountKzt < 0 ||
              !value.taskTitle || !value.pharmacyName || !value.phoneMasked || !Number.isFinite(Date.parse(value.challengeExpiresAt))) {
            throw new ClaimError('Получены неполные данные задания. Отсканируйте QR-код заново.', 0)
          }
          if (!cancelled) setContext(value)
        })
        .catch((failure) => { if (!cancelled) setError(failureMessage(failure)) })
        .finally(() => { if (!cancelled) setLoading(false) })
    }, 0)
    return () => { cancelled = true; window.clearTimeout(timer) }
  }, [authorized, tokens, challenge, contextAttempt])

  async function perform(action: () => Promise<void>) {
    setBusy(true)
    setError('')
    try { await action() }
    catch (failure) { setError(failureMessage(failure)) }
    finally { setBusy(false) }
  }

  async function login(event: FormEvent) {
    event.preventDefault()
    await perform(async () => {
      const result = await post<{ tokens: Tokens }>('/api/mobile/auth/login', { iin, password })
      if (!validTokens(result?.tokens)) throw new ClaimError(REQUEST_ERROR, 0)
      setLoading(true)
      saveTokens(result.tokens)
      setPassword('')
    })
  }

  async function requestCode() {
    if (!tokens || !challenge) return
    await perform(async () => {
      const result = await authorized<OtpChallenge>(`${BASE}/otp/request`, { crmChallenge: challenge }, tokens)
      if (!result?.otpChallengeId || !result.phoneMasked || !Number.isFinite(Date.parse(result.expiresAt)) ||
          !Number.isFinite(result.retryAfterSeconds) || result.retryAfterSeconds < 0 || result.retryAfterSeconds > 3600) {
        throw new ClaimError(REQUEST_ERROR, 0)
      }
      setOtp(result)
      setCode('')
      const requestedAt = Date.now()
      setNow(requestedAt)
      setRetryAt(requestedAt + result.retryAfterSeconds * 1000)
    })
  }

  async function confirm(event: FormEvent) {
    event.preventDefault()
    if (!tokens || !challenge || !otp) return
    await perform(async () => {
      const result = await authorized<Grant>(BASE, { crmChallenge: challenge, otpChallengeId: otp.otpChallengeId, code }, tokens)
      try {
        if (!result || !Number.isFinite(Date.parse(result.expiresAt))) throw new Error()
        setHandoff({ url: trustedMerchRedirect(result.redirectUrl, result.grant), expiresAt: result.expiresAt })
      } catch {
        throw new ClaimError('Не удалось безопасно вернуться к заданию. Отсканируйте QR-код заново.', 0)
      }
      setCode('')
    })
  }

  const expired = context !== null && !(Date.parse(context.challengeExpiresAt) > now)
  const codeExpired = otp !== null && !(Date.parse(otp.expiresAt) > now)
  const handoffExpired = handoff !== null && !(Date.parse(handoff.expiresAt) > now)
  const retrySeconds = Math.max(0, Math.ceil((retryAt - now) / 1000))

  return (
    <main className="flex min-h-screen items-center justify-center bg-paper px-4 py-8">
      <section className="card w-full max-w-[460px] rounded-2xl p-6 sm:p-8" aria-label="Подтверждение исполнителя задания">
        <div className="mb-6 flex items-center gap-3 text-brand-green-700"><ShieldCheck size={26} /><span className="text-lg font-extrabold">ePharm</span></div>
        <h1 className="text-2xl font-extrabold text-ink-900">Подтвердите исполнителя</h1>
        <p className="mt-2 text-sm leading-6 text-ink-500">Задание выполняется в CRM. Здесь мы безопасно связываем его с вашим личным балансом ePharm.</p>

        {!challenge ? <p role="alert" className="mt-6 text-sm text-red-700">В ссылке нет действительного кода задания. Отсканируйте QR-код на кассе заново.</p> : handoff ? (
          <div className="mt-6 space-y-4">
            <CheckCircle2 className="text-brand-green-700" size={32} />
            <h2 className="text-lg font-bold">Исполнитель подтверждён</h2>
            <p className="text-sm leading-6 text-ink-500">Вернитесь к заданию, чтобы начать выполнение. Вознаграждение появится после проверки ответственного сотрудника.</p>
            {handoffExpired ? <p role="alert">Срок перехода истёк. Отсканируйте QR-код заново.</p> : <a className="btn btn-lg btn-primary flex w-full items-center justify-center gap-2" href={handoff.url} rel="noreferrer">Вернуться к заданию <ArrowRight size={18} /></a>}
          </div>
        ) : !tokens ? (
          <form className="mt-6 space-y-4" onSubmit={login}>
            <h2 className="font-bold">Войдите в свою учётную запись</h2>
            <label className="block text-sm font-semibold">ИИН<input className="inp mt-2 w-full" inputMode="numeric" autoComplete="username" maxLength={12} value={iin} onChange={(event) => setIin(event.target.value.replace(/\D/g, ''))} required disabled={busy} /></label>
            <label className="block text-sm font-semibold">Пароль<input className="inp mt-2 w-full" type="password" autoComplete="current-password" value={password} onChange={(event) => setPassword(event.target.value)} required disabled={busy} /></label>
            <button className="btn btn-lg btn-primary w-full justify-center" disabled={busy || iin.length !== 12 || !password}>{busy ? 'Входим…' : 'Войти'}</button>
            <p className="text-xs leading-5 text-ink-500">Нужна именно учётная запись фармацевта, не администратора. Если доступ ещё не настроен, обратитесь к ответственному сотруднику.</p>
          </form>
        ) : loading ? <p role="status" className="mt-6 text-sm">Проверяем задание и вашу аптеку…</p> : context ? (
          <div className="mt-6 space-y-4">
            <div className="rounded-xl border border-ink-100 p-4"><h2 className="font-bold">{context.taskTitle}</h2><p className="mt-1 text-sm text-ink-500">{context.pharmacyName}</p><p className="mt-3 font-bold">{new Intl.NumberFormat('ru-KZ').format(context.amountKzt)} ₸ после проверки</p></div>
            <p className="text-sm leading-6 text-ink-500">Код подтверждения придёт на номер вашей учётной записи: <strong>{otp?.phoneMasked || context.phoneMasked}</strong>. Изменить получателя в этом окне нельзя.</p>
            {expired ? <p role="alert">Ссылка задания устарела. Отсканируйте QR-код на кассе заново.</p> : <>
              {otp && !codeExpired && <form onSubmit={confirm} className="space-y-3"><label className="block text-sm font-semibold">Код из SMS<input className="inp mt-2 w-full text-lg tracking-widest" inputMode="numeric" autoComplete="one-time-code" maxLength={8} value={code} onChange={(event) => setCode(event.target.value.replace(/\D/g, ''))} required disabled={busy} /></label><button className="btn btn-lg btn-primary w-full justify-center" disabled={busy || code.length < 4}>{busy ? 'Подтверждаем…' : 'Подтвердить и продолжить'}</button></form>}
              {codeExpired && <p role="alert" className="text-sm">Код истёк. Запросите новый код.</p>}
              <button type="button" className="btn btn-lg btn-outline w-full justify-center" onClick={() => void requestCode()} disabled={busy || retrySeconds > 0}>{retrySeconds > 0 ? `Новый код через ${retrySeconds} с` : otp ? 'Отправить код повторно' : 'Получить код из SMS'}</button>
            </>}
            <p className="flex gap-2 text-xs leading-5 text-ink-500"><LockKeyhole className="shrink-0" size={15} />Сканирование QR-кода и отправка отчёта сами по себе не начисляют деньги. Требуются выполнение задания и проверка руководителя.</p>
          </div>
        ) : null}
        {error && <p role="alert" className="mt-4 text-sm leading-6 text-red-700">{error}</p>}
        {tokens && challenge && !handoff && <div className="mt-5 space-y-3">
          {!loading && !context && <button type="button" className="btn btn-lg btn-outline w-full justify-center" onClick={() => { setLoading(true); setError(''); setContextAttempt((attempt) => attempt + 1) }}>Повторить проверку</button>}
          <button type="button" className="text-sm font-semibold text-ink-500 underline" disabled={busy} onClick={() => { saveTokens(null); setContext(null); setOtp(null); setLoading(false); setError('') }}>Войти под другой учётной записью</button>
        </div>}
      </section>
    </main>
  )
}
