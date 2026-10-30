import { useEffect, useRef, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { isAxiosError } from 'axios'
import { api } from '@/lib/api'
import { useUiStore } from '@/app/store'
import { useT } from '@/i18n'
import { Button, Field, Input } from '@/ui'

export function PasswordChangeForm({
  userId,
  sessionEpoch,
}: {
  userId: string
  sessionEpoch: number
}) {
  const t = useT()
  const navigate = useNavigate()
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [confirmation, setConfirmation] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [pending, setPending] = useState(false)
  const request = useRef<AbortController | null>(null)
  useEffect(() => () => request.current?.abort(), [])
  const sameSession = () => {
    const state = useUiStore.getState()
    return state.authedUser?.id === userId && state.querySessionEpoch === sessionEpoch
  }
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (request.current || !sameSession()) return
    setError(null)
    if (
      !current ||
      next.length < 12 ||
      next.length > 72 ||
      new TextEncoder().encode(next).length > 72
    ) {
      setError(t('account.passwordRules'))
      return
    }
    if (next !== confirmation) {
      setError(t('account.passwordMismatch'))
      return
    }
    if (next === current) {
      setError(t('account.passwordSame'))
      return
    }
    const controller = new AbortController()
    request.current = controller
    setPending(true)
    try {
      await api.post(
        '/api/admin/auth/password',
        { currentPassword: current, newPassword: next },
        { signal: controller.signal },
      )
      if (!sameSession() || controller.signal.aborted) return
      setCurrent('')
      setNext('')
      setConfirmation('')
      // RequireAuth may replace the redirect state while clearing the session.
      // Keep only this non-sensitive notice in memory until the next login attempt.
      useUiStore.setState({ passwordChangedNotice: true })
      void useUiStore.getState().logout()
      navigate('/login', { replace: true })
    } catch (cause) {
      if (!sameSession() || controller.signal.aborted) return
      const status = isAxiosError(cause) ? cause.response?.status : undefined
      setError(
        status === 429
          ? t('account.passwordRateLimited')
          : status === 400
            ? t('account.passwordRejected')
            : t('account.passwordError'),
      )
    } finally {
      if (!controller.signal.aborted && sameSession()) {
        setCurrent('')
        setNext('')
        setConfirmation('')
        setPending(false)
      }
      request.current = null
    }
  }
  return (
    <form
      onSubmit={submit}
      aria-label={t('account.changePassword')}
      className="flex w-full flex-col gap-4"
      noValidate
    >
      <p className="text-sm text-ink-500">{t('account.passwordNotice')}</p>
      <Field label={t('account.currentPassword')}>
        <Input
          aria-label={t('account.currentPassword')}
          type="password"
          autoComplete="current-password"
          value={current}
          onChange={(e) => setCurrent(e.target.value)}
          disabled={pending}
        />
      </Field>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <Field label={t('account.newPassword')} hint={t('account.passwordRules')}>
          <Input
            aria-label={t('account.newPassword')}
            type="password"
            autoComplete="new-password"
            value={next}
            onChange={(e) => setNext(e.target.value)}
            disabled={pending}
          />
        </Field>
        <Field label={t('account.confirmPassword')}>
          <Input
            aria-label={t('account.confirmPassword')}
            type="password"
            autoComplete="new-password"
            value={confirmation}
            onChange={(e) => setConfirmation(e.target.value)}
            disabled={pending}
          />
        </Field>
      </div>
      {error && (
        <p role="alert" className="text-sm text-surface-danger-strong">
          {error}
        </p>
      )}
      <Button type="submit" disabled={pending} className="self-start">
        {pending ? t('account.passwordSaving') : t('account.changePassword')}
      </Button>
    </form>
  )
}
