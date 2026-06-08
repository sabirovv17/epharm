// RoleSwitcher — модалка переключения роли. Только для демо — в проде роли назначаются админом.
import { Avatar, Button, Modal } from '@/ui'
import { IconCheck } from '@/ui/icons'
import { roleLabel, USERS, type User } from '@/mocks/fixtures'

interface RoleSwitcherProps {
  open: boolean
  onClose: () => void
  current: User
  onPick: (user: User) => void
}

export function RoleSwitcher({ open, onClose, current, onPick }: RoleSwitcherProps) {
  return (
    <Modal
      open={open}
      onClose={onClose}
      title="Сменить роль"
      subtitle="Только для демо — в проде роли назначаются админом"
      width={460}
      footer={
        <Button variant="ghost" onClick={onClose}>
          Закрыть
        </Button>
      }
    >
      <div className="flex flex-col gap-2">
        {Object.values(USERS).map((u) => {
          const isActive = current.id === u.id
          return (
            <button
              key={u.id}
              onClick={() => {
                onPick(u)
                onClose()
              }}
              className={`flex items-center gap-3 rounded-xl border p-3 text-left transition ${
                isActive
                  ? 'border-brand-green-600 bg-brand-green-50'
                  : 'border-ink-200 hover:bg-paper-hover'
              }`}
            >
              <Avatar name={u.name} size={40} />
              <div className="flex-1">
                <div className="text-sm font-extrabold text-ink-900">{u.name}</div>
                <div className="text-[12px] font-semibold text-ink-500">
                  {roleLabel(u.role)} · {u.company}
                </div>
              </div>
              {isActive && (
                <span className="text-brand-green-600">
                  <IconCheck size={18} />
                </span>
              )}
            </button>
          )
        })}
      </div>
    </Modal>
  )
}
