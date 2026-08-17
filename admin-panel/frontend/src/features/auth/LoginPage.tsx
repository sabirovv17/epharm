// LoginPage — экран входа админа.
// Centered card на paper canvas, вне AppShell (без Sidebar / Topbar).
// Async submit — реально дёргает backend через store.login() → POST /api/admin/auth/login.

import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { Button, Field, Input } from '@/ui'
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
    <div className="flex min-h-screen items-center justify-center bg-paper px-6 py-12">
      <div className="w-full max-w-[420px]">
        {/* Header */}
        <div className="mb-8 flex flex-col items-center gap-3">
          <div className="flex h-14 w-14 items-center justify-center rounded-2xl bg-ink-900 shadow-elevated">
            <Logo size={32} />
          </div>
          <div className="text-center">
            <div className="text-[24px] font-extrabold leading-tight text-ink-900">Console</div>
            <div className="mt-1 text-[13px] font-semibold text-ink-500">
              Вход для HQ Inkar и категорийной команды
            </div>
          </div>
        </div>

        {/* Form */}
        <form
          onSubmit={onSubmit}
          className="card flex flex-col gap-4 p-6 shadow-card"
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
      </div>
    </div>
  )
}
