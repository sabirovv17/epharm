// 404 — неизвестный путь внутри админки. Раньше молча редиректило на /rules
// (пользователь не понимал, что URL неверный). Теперь — явная страница с возвратом.

import { Link } from 'react-router-dom'
import { Button } from '@/ui'
import { useT } from '@/i18n'
import { useUiStore } from './store'
import { defaultPathForRole } from './accessPolicy'

export default function NotFoundPage() {
  const t = useT()
  const user = useUiStore((state) => state.authedUser)
  const homePath = user ? defaultPathForRole(user.role) : '/login'
  return (
    <div
      className="flex flex-col items-center justify-center py-24 text-center"
      data-testid="not-found"
    >
      <div className="text-[64px] font-extrabold leading-none text-ink-200">404</div>
      <h1 className="mt-4 text-lg font-extrabold text-ink-900">{t('nf.title')}</h1>
      <p className="mt-1 max-w-sm text-[13px] text-ink-500">{t('nf.body')}</p>
      <Link to={homePath} className="mt-6">
        <Button variant="primary">{t('nf.cta')}</Button>
      </Link>
    </div>
  )
}
