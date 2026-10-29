import { useEffect, useState } from 'react'
import { Link, Navigate, useNavigate } from 'react-router-dom'
import { Button, Field, Input } from '@/ui'
import { Logo } from '@/layout/Logo'
import { producerClient, producerError, type ProducerChallenge } from './producer-client'
import { useProducerSession } from './useProducerSession'

export default function ProducerLoginPage() {
  const navigate = useNavigate()
  const { session } = useProducerSession()
  const [phone, setPhone] = useState('')
  const [code, setCode] = useState('')
  const [challenge, setChallenge] = useState<ProducerChallenge | null>(null)
  const [retryAt, setRetryAt] = useState(0)
  const [now, setNow] = useState(Date.now)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  useEffect(() => {
    if (!challenge) return
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [challenge])
  const request = async () => {
    if (pending || now < retryAt) return
    setPending(true)
    setError('')
    setCode('')
    try {
      const next = await producerClient.requestCode(phone)
      setChallenge(next)
      setNow(Date.now())
      setRetryAt(Date.now() + next.retryAfterSeconds * 1000)
    } catch (reason) {
      setError(producerError(reason))
    } finally {
      setPending(false)
    }
  }
  const verify = async () => {
    if (!challenge || pending) return
    setPending(true)
    setError('')
    try {
      await producerClient.verify(challenge.challengeId, code)
      setCode('')
      navigate('/producer', { replace: true })
    } catch (reason) {
      setError(producerError(reason))
      setCode('')
    } finally {
      setPending(false)
    }
  }
  if (session) return <Navigate to="/producer" replace />
  return (
    <div className="min-h-screen bg-paper p-4 sm:p-8">
      <div className="mx-auto grid min-h-[640px] max-w-[1080px] overflow-hidden rounded-3xl border border-paper-line bg-white shadow-card md:grid-cols-[0.9fr_1.1fr]">
        <aside className="sidebar-bg flex flex-col justify-between gap-8 p-8 text-white sm:p-12">
          <div className="flex items-center gap-3">
            <Logo size={32} />
            <span className="text-2xl font-extrabold">ePharm</span>
          </div>
          <div>
            <p className="text-xs font-bold uppercase tracking-[0.16em] text-white/60">
              Мерчендайзинг
            </p>
            <h1 className="mt-4 text-3xl font-extrabold leading-tight">Кабинет производителя</h1>
            <p className="mt-5 text-sm leading-7 text-white/75">
              Выбирайте места в аптеках, готовьте медиапланы и следите за подтверждением размещений.
            </p>
          </div>
          <p className="text-xs leading-6 text-white/55">
            Вход только для подключённых производителей. Доступ к админке и личному кабинету
            фармацевта выдаётся отдельно.
          </p>
        </aside>
        <main className="flex flex-col justify-center p-8 sm:p-12">
          <p className="text-xs font-bold uppercase tracking-[0.14em] text-brand-green-700">
            Личный кабинет
          </p>
          <h2 className="mt-3 text-3xl font-extrabold">
            {challenge ? 'Введите код из SMS' : 'Вход по телефону'}
          </h2>
          <p className="mb-7 mt-3 text-sm leading-6 text-ink-500">
            {challenge
              ? `${challenge.message}. ${challenge.phoneMasked}`
              : 'Используйте номер, который привязан к вашему производителю. Самостоятельная регистрация не открывает доступ.'}
          </p>
          <form
            className="flex flex-col gap-4"
            onSubmit={(event) => {
              event.preventDefault()
              if (challenge) void verify()
              else void request()
            }}
          >
            <Field label="Телефон">
              <Input
                aria-label="Телефон производителя"
                type="tel"
                autoComplete="tel"
                placeholder="+7 …"
                value={phone}
                disabled={pending || !!challenge}
                onChange={(event) => setPhone(event.target.value)}
              />
            </Field>
            {challenge && (
              <Field label="SMS-код">
                <Input
                  aria-label="SMS-код производителя"
                  inputMode="numeric"
                  autoComplete="one-time-code"
                  value={code}
                  maxLength={8}
                  onChange={(event) => setCode(event.target.value.replace(/\D/g, ''))}
                  disabled={pending}
                />
                <p className="mt-2 text-xs text-ink-500">
                  Код действует {Math.round(challenge.ttlSeconds / 60)} мин.
                </p>
              </Field>
            )}
            {error && (
              <p
                role="alert"
                className="rounded-lg bg-surface-danger p-3 text-sm text-surface-danger-strong"
              >
                {error}
              </p>
            )}
            <Button
              type="submit"
              disabled={
                pending ||
                (!challenge ? phone.replace(/\D/g, '').length < 10 : !/^\d{4,8}$/.test(code))
              }
            >
              {pending ? 'Подождите…' : challenge ? 'Войти' : 'Получить SMS-код'}
            </Button>
            {challenge && (
              <div className="flex flex-wrap justify-between gap-2">
                <Button
                  type="button"
                  variant="ghost"
                  disabled={pending || now < retryAt}
                  onClick={request}
                >
                  {now < retryAt
                    ? `Повторить через ${Math.ceil((retryAt - now) / 1000)} с`
                    : 'Отправить ещё раз'}
                </Button>
                <Button
                  type="button"
                  variant="ghost"
                  disabled={pending}
                  onClick={() => {
                    setChallenge(null)
                    setCode('')
                    setError('')
                    setRetryAt(0)
                  }}
                >
                  Другой номер
                </Button>
              </div>
            )}
          </form>
          <p className="mt-7 text-xs leading-6 text-ink-500">
            SMS-коды не сохраняются. Резервирование места не означает оплату.
          </p>
          <Link className="mt-5 text-xs font-semibold text-brand-green-700" to="/learn">
            Кабинет фармацевта
          </Link>
        </main>
      </div>
    </div>
  )
}
