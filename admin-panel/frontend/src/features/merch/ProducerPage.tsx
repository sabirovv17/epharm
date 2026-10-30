import { useCallback, useMemo, useState } from 'react'
import { Navigate } from 'react-router-dom'
import { Button } from '@/ui'
import { Logo } from '@/layout/Logo'
import PlacementWorkspace from './PlacementWorkspace'
import { placementGateway } from './placements-api'
import { producerClient, producerError, type ProducerProfile } from './producer-client'
import { useProducerSession } from './useProducerSession'
import { usePlacementRead } from './usePlacementRead'
import ProducerCompanyProfile from './ProducerCompanyProfile'
import ProducerCommercialWorkspace from './ProducerCommercialWorkspace'
import { commercialGateway } from './producer-commercial-api'
import TaskInstructionPanel from './TaskInstructionPanel'
import { producerInstructionApi } from './task-instructions-api'

const gateway = placementGateway('producer', <T,>(path: string, init?: RequestInit) =>
  producerClient.request<T>(path, init),
)
const commercial = commercialGateway(
  'producer',
  <T,>(path: string, init?: RequestInit) => producerClient.request<T>(path, init),
  (path, signal) => producerClient.download(path, signal),
)
export default function ProducerPage() {
  const { session, epoch } = useProducerSession()
  if (!session) return <Navigate to="/producer/login" replace />
  return <VerifiedProducerWorkspace key={epoch} />
}
function VerifiedProducerWorkspace() {
  const [profileOpen, setProfileOpen] = useState(false)
  const [section, setSection] = useState('placements')
  const [visited, setVisited] = useState<string[]>(['placements'])
  const instructionApi = useMemo(() => producerInstructionApi(producerClient), [])
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
        <div className="flex min-w-0 max-w-full flex-wrap items-center gap-4">
          <span className="min-w-0 max-w-full break-words text-sm text-ink-500">
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
      <main className="mx-auto min-w-0 max-w-[1440px] p-4 sm:p-6">
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
          <>
            <nav
              aria-label="Разделы кабинета производителя"
              className="card mb-5 flex flex-wrap gap-2 p-3"
            >
              {[
                ['placements', 'Мои размещения'],
                ['commercial', 'Бюджет, период и команда'],
                ['instructions', 'Инструктажи по размещениям'],
              ].map(([id, label]) => (
                <Button
                  key={id}
                  id={`producer-section-${id}`}
                  variant={section === id ? 'primary' : 'ghost'}
                  aria-expanded={section === id}
                  aria-controls={`producer-panel-${id}`}
                  className="h-auto min-h-[38px] max-w-full whitespace-normal py-2 text-left"
                  onClick={() => {
                    setSection(id)
                    setVisited((values) => (values.includes(id) ? values : [...values, id]))
                  }}
                >
                  {label}
                </Button>
              ))}
            </nav>
            <div
              id="producer-panel-commercial"
              role="region"
              aria-labelledby="producer-section-commercial"
              hidden={section !== 'commercial'}
            >
              {visited.includes('commercial') && (
                <ProducerCommercialWorkspace
                  gateway={commercial}
                  placements={gateway}
                  ownProducerId={me.data.id}
                />
              )}
            </div>
            <div
              id="producer-panel-instructions"
              role="region"
              aria-labelledby="producer-section-instructions"
              hidden={section !== 'instructions'}
            >
              {visited.includes('instructions') && <TaskInstructionPanel api={instructionApi} />}
            </div>
            <div
              id="producer-panel-placements"
              role="region"
              aria-labelledby="producer-section-placements"
              hidden={section !== 'placements'}
            >
              <PlacementWorkspace
                gateway={gateway}
                customerName={me.data.name ?? 'Компания не указана'}
              />
            </div>
          </>
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
