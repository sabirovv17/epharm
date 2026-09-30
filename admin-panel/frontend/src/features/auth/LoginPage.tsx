// LoginPage — экран входа админа.
// Centered card на paper canvas, вне AppShell (без Sidebar / Topbar).
// Async submit — реально дёргает backend через store.login() → POST /api/admin/auth/login.

import { useState, type FormEvent, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { Button, Field, Input, IconLMS, IconPharmacy, IconShield } from '@/ui'
import { Logo } from '@/layout/Logo'
import { useUiStore } from '@/app/store'
import { defaultPathForRole } from '@/app/accessPolicy'

export default function LoginPage() {
  const navigate = useNavigate()
  const login = useUiStore((s) => s.login)

  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  const onSubmit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    setError(null)

    if (!email.trim() || !password) {
      setError('Заполните email и пароль')
      return
    }
    if (!email.includes('@')) {
      setError('Неверный формат email')
      return
    }

    setSubmitting(true)
    try {
      const result = await login(email, password)
      if (result.ok) {
        const user = useUiStore.getState().authedUser
        navigate(user ? defaultPathForRole(user.role) : '/rules', { replace: true })
      } else if (result.code === 'INVALID_CREDENTIALS') {
        setError('Неверный email или пароль')
      } else if (result.code === 'NETWORK') {
        setError('Сервер недоступен — проверьте соединение или backend')
      } else {
        setError('Не удалось войти — попробуйте ещё раз')
      }
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="grid min-h-screen grid-cols-[minmax(420px,0.9fr)_minmax(560px,1.1fr)] bg-paper">
      <section className="sidebar-bg flex min-h-screen flex-col justify-between px-12 py-10 text-white">
        <div className="flex items-center gap-3">
          <span className="flex h-11 w-11 items-center justify-center rounded-lg bg-white/10"><Logo size={28} /></span>
          <div>
            <div className="text-[22px] font-extrabold tracking-[-0.03em]">ePharm</div>
            <div className="text-[10px] font-bold uppercase tracking-[0.12em] text-brand-green-200/70">Фармацевтическая операционная</div>
          </div>
        </div>

        <div className="max-w-[520px]">
          <div className="text-[11px] font-extrabold uppercase tracking-[0.14em] text-brand-green-300">Единая рабочая среда</div>
          <h1 className="mt-4 text-[38px] font-extrabold leading-[1.14] tracking-[-0.035em]">Обучение и управление аптечной сетью</h1>
          <p className="mt-4 max-w-[480px] text-[15px] leading-7 text-white/68">Назначайте обучение, контролируйте прогресс фармацевтов и работайте с данными сети в одном защищённом пространстве.</p>
          <div className="mt-9 grid grid-cols-3 gap-3">
            <LoginFeature icon={<IconLMS size={19} />} label="Обучение" />
            <LoginFeature icon={<IconPharmacy size={19} />} label="Аптечная сеть" />
            <LoginFeature icon={<IconShield size={19} />} label="Безопасность" />
          </div>
        </div>

        <div className="text-[11px] font-semibold text-white/40">ePharm Console · защищённый доступ</div>
      </section>

      <section className="flex min-h-screen items-center justify-center px-12 py-12">
        <div className="w-full max-w-[420px]">
          <div className="mb-7">
            <div className="text-[11px] font-extrabold uppercase tracking-[0.12em] text-brand-green-700">Административная панель</div>
            <div className="mt-2 text-[28px] font-extrabold tracking-[-0.025em] text-ink-900">Вход в ePharm</div>
            <div className="mt-2 text-[13px] font-medium text-ink-500">Вход для HQ Inkar и категорийной команды</div>
          </div>

          <form
            onSubmit={onSubmit}
            className="card flex flex-col gap-4 rounded-2xl p-7"
            aria-label="Форма входа"
            noValidate
          >
          <Field label="Email">
            <Input
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="name@inkar.kz"
              autoComplete="username"
              autoFocus
              disabled={submitting}
            />
          </Field>

          <Field label="Пароль">
            <Input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="••••••••"
              autoComplete="current-password"
              disabled={submitting}
            />
          </Field>

          {error && (
            <div
              role="alert"
              className="rounded-md bg-surface-danger px-3 py-2 text-[13px] font-semibold text-surface-danger-strong"
            >
              {error}
            </div>
          )}

          <Button type="submit" size="lg" disabled={submitting} className="w-full justify-center">
            {submitting ? 'Входим…' : 'Войти'}
          </Button>
          </form>
          <p className="mt-5 text-center text-[11px] leading-5 text-ink-400">Доступ предоставляется администратором системы. Все действия фиксируются в журнале безопасности.</p>
        </div>
      </section>
    </div>
  )
}

function LoginFeature({ icon, label }: { icon: ReactNode; label: string }) {
  return (
    <div className="rounded-xl border border-white/10 bg-white/[0.055] px-3 py-3">
      <div className="text-brand-green-300">{icon}</div>
      <div className="mt-2 text-[11px] font-bold text-white/75">{label}</div>
    </div>
  )
}
