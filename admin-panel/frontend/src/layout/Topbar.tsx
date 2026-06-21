// Topbar — admin §7.2
// Белый surface, sticky top:0, h=64. Breadcrumb (HQ › Section) + fake-search (⌘K) + period + role dropdown.
// Bell / History временно убраны — пока нет системы уведомлений.

import { useEffect, useRef, useState } from 'react'
import { Avatar } from '@/ui'
import {
  IconChevDown,
  IconChevRight,
  IconCommand,
  IconLogout,
  IconSearch,
  IconUsers,
} from '@/ui/icons'
import { roleLabel, type User } from '@/mocks/fixtures'
import { useT } from '@/i18n'
import { PeriodPicker } from './PeriodPicker'

interface TopbarProps {
  sectionLabel: string
  role: User
  /** Демо-смена роли. undefined (прод) → пункт меню скрыт. */
  onRoleSwitch?: () => void
  onLogout: () => void
  onMenu: () => void
  onCommand: () => void
}

export function Topbar({
  sectionLabel,
  role,
  onRoleSwitch,
  onLogout,
  onMenu,
  onCommand,
}: TopbarProps) {
  const t = useT()
  return (
    <header className="topbar-bg sticky top-0 z-30 flex h-16 flex-none items-center gap-4 px-6">
      {/* Left: hamburger (mobile-only) + breadcrumb */}
      <div className="flex min-w-0 items-center gap-2">
        <button
          onClick={onMenu}
          className="flex h-9 w-9 items-center justify-center rounded-lg text-ink-500 hover:bg-ink-100 md:hidden"
        >
          <IconCommand size={18} />
        </button>
        <div className="flex items-center gap-1.5 text-[13px] text-ink-500">
          <span>HQ</span>
          <IconChevRight size={12} />
          <span className="font-bold text-ink-900">{sectionLabel}</span>
        </div>
      </div>

      {/* Center: fake-search input → opens command palette */}
      <div className="mx-auto hidden max-w-[480px] flex-1 md:block">
        <button
          onClick={onCommand}
          className="flex h-9 w-full items-center gap-2 rounded-lg bg-paper-input px-3 text-[13px] font-semibold text-ink-500 transition hover:bg-ink-100"
        >
          <IconSearch size={15} />
          <span className="flex-1 text-left">{t('topbar.search')}</span>
          <span className="kbd">⌘</span>
          <span className="kbd">K</span>
        </button>
      </div>

      {/* Right: period · role dropdown */}
      <div className="ml-auto flex items-center gap-1.5">
        <PeriodPicker />
        <span className="mx-1 h-5 w-px bg-ink-200" />
        <RolePill role={role} onRoleSwitch={onRoleSwitch} onLogout={onLogout} />
      </div>
    </header>
  )
}

// ─────────────────────────────────────────────────────────────────────────
// Role pill + dropdown menu (Сменить роль / Выйти)
// ─────────────────────────────────────────────────────────────────────────

interface RolePillProps {
  role: User
  onRoleSwitch?: () => void
  onLogout: () => void
}

function RolePill({ role, onRoleSwitch, onLogout }: RolePillProps) {
  const t = useT()
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)

  // Click outside closes dropdown
  useEffect(() => {
    if (!open) return
    const onClick = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false)
    }
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false)
    window.addEventListener('mousedown', onClick)
    window.addEventListener('keydown', onKey)
    return () => {
      window.removeEventListener('mousedown', onClick)
      window.removeEventListener('keydown', onKey)
    }
  }, [open])

  return (
    <div ref={ref} className="relative">
      <button
        onClick={() => setOpen((v) => !v)}
        className="flex h-10 items-center gap-2.5 rounded-xl pl-1 pr-3 transition hover:bg-ink-100"
        aria-haspopup="menu"
        aria-expanded={open}
      >
        <Avatar name={role.name} size={32} />
        <div className="hidden text-left sm:block">
          <div className="text-[13px] font-extrabold leading-tight text-ink-900">{role.name}</div>
          <div className="text-[11px] font-semibold leading-tight text-ink-500">
            {roleLabel(role.role)} · {role.company}
          </div>
        </div>
        <IconChevDown size={14} className="hidden text-ink-400 sm:block" />
      </button>

      {open && (
        <div
          role="menu"
          className="card slide-in absolute right-0 top-full z-50 mt-1.5 w-56 overflow-hidden p-1.5 shadow-elevated"
        >
          {onRoleSwitch && (
            <button
              role="menuitem"
              onClick={() => {
                setOpen(false)
                onRoleSwitch()
              }}
              className="flex w-full items-center gap-2.5 rounded-md px-2.5 py-2 text-left text-[13px] font-semibold text-ink-700 hover:bg-paper-hover"
            >
              <span className="text-ink-500">
                <IconUsers size={16} />
              </span>
              <span>{t('topbar.changeRole')}</span>
            </button>
          )}
          <button
            role="menuitem"
            onClick={() => {
              setOpen(false)
              onLogout()
            }}
            className="flex w-full items-center gap-2.5 rounded-md px-2.5 py-2 text-left text-[13px] font-semibold text-accent-danger hover:bg-surface-danger/30"
          >
            <span>
              <IconLogout size={16} />
            </span>
            <span>{t('topbar.logout')}</span>
          </button>
        </div>
      )}
    </div>
  )
}
