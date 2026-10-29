import { useCallback, useState } from 'react'
import { Navigate } from 'react-router-dom'
import { Button } from '@/ui'
import { Logo } from '@/layout/Logo'
import PlacementWorkspace from './PlacementWorkspace'
import { placementGateway } from './placements-api'
import { producerClient, producerError, type ProducerProfile } from './producer-client'
import { useProducerSession } from './useProducerSession'
import { usePlacementRead } from './usePlacementRead'
import ProducerCompanyProfile from './ProducerCompanyProfile'

const gateway = placementGateway('producer', <T,>(path: string, init?: RequestInit) =>
  producerClient.request<T>(path, init),
)
export default function ProducerPage() {
  const { session, epoch } = useProducerSession()
  if (!session) return <Navigate to="/producer/login" replace />
  return <VerifiedProducerWorkspace key={epoch} />
}
function VerifiedProducerWorkspace() {
  const [profileOpen, setProfileOpen] = useState(false)
  const load = useCallback(
    (signal: AbortSignal) =>
      producerClient.request<ProducerProfile>('/api/producer/auth/me', { signal }),
    [],
  )
  const me = usePlacementRead(load)
  return (
    <div className="min-h-screen bg-paper">
      <header className="flex flex-wrap items-center justify-between gap-3 border-b border-paper-line bg-white px-5 py-4">
        <div className="flex items-center gap-3">
          <Logo size={28} />
          <span className="font-extrabold">ePharm · Производитель</span>
        </div>
        <div className="flex items-center gap-4">
          <span className="text-sm text-ink-500">
            {me.data ? (me.data.name ?? 'Компания не указана') : ''}
          </span>
          {me.data && (
            <Button variant="ghost" onClick={() => setProfileOpen(true)}>
              Профиль компании
            </Button>
          )}
          <Button
            variant="outline"
            onClick={() => {
              void producerClient.logout()
            }}
          >
            Выйти
          </Button>
        </div>
      </header>
      <main className="mx-auto max-w-[1440px] p-4 sm:p-6">
        {me.pending && <p role="status">Проверяем сессию производителя…</p>}
        {!!me.error && (
          <div role="alert" className="card p-6">
            <p>{producerError(me.error)}</p>
            <Button className="mt-3" variant="outline" onClick={me.reload}>
              Повторить проверку
            </Button>
          </div>
        )}
        {me.data?.profileComplete === false && (
          <div role="status" className="card mb-4 p-4">
            <p className="text-sm">
              Название компании не заполнено. Можно готовить черновики; перед отправкой на
              подтверждение заполните профиль.
            </p>
            <Button className="mt-2" variant="outline" onClick={() => setProfileOpen(true)}>
              Заполнить профиль компании
            </Button>
          </div>
        )}
        {me.data && (
          <PlacementWorkspace
            gateway={gateway}
            customerName={me.data.name ?? 'Компания не указана'}
          />
        )}
        {profileOpen && me.data && (
          <ProducerCompanyProfile
            onClose={() => setProfileOpen(false)}
            onSaved={() => {
              setProfileOpen(false)
              me.reload()
            }}
          />
        )}
      </main>
    </div>
  )
}
