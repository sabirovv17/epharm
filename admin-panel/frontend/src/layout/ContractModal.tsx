// ContractModal — детали активного контракта. Открывается из Sidebar bottom widget.
//
// Guard: если у пользователя нет активного контракта (getUserContract → null) —
// модалка не рендерится вообще. Это важно потому что AppShell всегда монтирует ContractModal,
// и без guard'а пустой CONTRACT (approvedBy='', budgetTotal=0) ломал рендер на USERS[''].name.

import { Avatar, Button, Metric, Modal } from '@/ui'
import { IconExternal } from '@/ui/icons'
import { getUserContract, USERS, formatKzt, type User } from '@/mocks/fixtures'

interface ContractModalProps {
  open: boolean
  onClose: () => void
  user: User | null
}

export function ContractModal({ open, onClose, user }: ContractModalProps) {
  const contract = user ? getUserContract(user) : null

  // Нет контракта у user'а → модалка просто не рендерится. AppShell всё равно её монтирует,
  // но return null отсюда не приведёт к ошибкам в children.
  if (!contract) return null

  const used =
    contract.budgetTotal > 0 ? Math.round((contract.budgetUsed / contract.budgetTotal) * 100) : 0
  const reviewer: User | undefined = USERS[contract.approvedBy]

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={`Контракт · ${contract.brand}`}
      subtitle={contract.period}
      width={620}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Закрыть
          </Button>
          <Button leading={<IconExternal size={14} />}>Открыть полный документ</Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <div className="grid grid-cols-3 gap-3">
          <Metric label="Бюджет всего" value={formatKzt(contract.budgetTotal)} accent="ink" />
          <Metric
            label="Использовано"
            value={formatKzt(contract.budgetUsed)}
            accent="green"
            meta={`${used}% от лимита`}
          />
          <Metric
            label="Осталось"
            value={formatKzt(contract.budgetTotal - contract.budgetUsed)}
            accent="blue"
          />
        </div>
        <div className="card-soft p-4">
          <div className="mb-2 text-[12px] font-bold uppercase tracking-[0.06em] text-ink-500">
            Бренды в контракте
          </div>
          <div className="flex flex-wrap gap-2">
            {contract.brands.map((b) => (
              <span key={b} className="chip chip-green">
                {b}
              </span>
            ))}
          </div>
        </div>
        <div className="card-soft grid grid-cols-2 gap-3 p-4 text-[13px]">
          <div>
            <div className="text-[11px] font-bold uppercase text-ink-400">Подписан</div>
            <div className="mt-0.5 font-extrabold text-ink-900">{contract.signedAt || '—'}</div>
          </div>
          <div>
            <div className="text-[11px] font-bold uppercase text-ink-400">
              Утверждено категорийным менеджером
            </div>
            {reviewer ? (
              <div className="mt-0.5 flex items-center gap-2">
                <Avatar name={reviewer.name} size={20} />
                <span className="font-extrabold text-ink-900">{reviewer.name}</span>
              </div>
            ) : (
              <div className="mt-0.5 text-ink-500">—</div>
            )}
          </div>
          <div>
            <div className="text-[11px] font-bold uppercase text-ink-400">Условия</div>
            <div className="mt-0.5 text-ink-700">
              Бонус фармацевту с каждой принятой рекомендации; cap 1500 ₸ / упаковка.
            </div>
          </div>
          <div>
            <div className="text-[11px] font-bold uppercase text-ink-400">Распределение</div>
            <div className="mt-0.5 text-ink-700">
              312 аптек, пилот + развёрнутая выборка. Контрольная группа исключена.
            </div>
          </div>
        </div>
      </div>
    </Modal>
  )
}
