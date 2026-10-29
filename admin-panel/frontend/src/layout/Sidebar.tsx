// Sidebar — компактный двухрежимный workspace-nav в духе Cursor.
// Навигация прокручивается отдельно, а бренд, контракт и профиль остаются на месте.

import {
  formatKzt,
  getUserContract,
  roleLabel,
  type Contract,
  type SectionId,
  type User,
} from '@/mocks/fixtures'
import { IconChevLeft, IconChevRight, IconShield } from '@/ui/icons'
import { useT } from '@/i18n'
import { useState } from 'react'
import { isTrainingWorkspaceRole } from '@/app/accessPolicy'
import {
  activeNavigationItem,
  adminNavigationForRole,
  type AdminNavigationItem,
  type AdminNavigationModule,
} from '@/app/adminNavigation'
import type { TrainingTab } from '@/app/trainingNavigation'
import { Logo } from './Logo'

interface SidebarProps {
  active: SectionId
  onSelect: (id: SectionId) => void
  collapsed: boolean
  onToggle: () => void
  onContractOpen: () => void
  user: User
  activeTrainingTab?: TrainingTab
  onSelectTrainingTab?: (tab: TrainingTab) => void
  pathname?: string
  onNavigate?: (path: string) => void
  canViewMerchInventory?: boolean
  canViewMerchPlacements?: boolean
  canViewMerchTasks?: boolean
}

export function Sidebar({
  active,
  onSelect,
  collapsed,
  onToggle,
  onContractOpen,
  user,
  activeTrainingTab = 'overview',
  onSelectTrainingTab,
  pathname = '',
  onNavigate,
  canViewMerchInventory = false,
  canViewMerchPlacements = false,
  canViewMerchTasks = false,
}: SidebarProps) {
  const t = useT()
  const trainingWorkspace = isTrainingWorkspaceRole(user.role)
  const modules = adminNavigationForRole(user.role, {
    inventory: canViewMerchInventory,
    placements: canViewMerchPlacements,
    tasks: canViewMerchTasks,
  })
  const [expanded, setExpanded] = useState<Record<string, boolean>>({})
  const pick = (item: AdminNavigationItem) => {
    if (!item.available) return
    if (onNavigate && item.path) onNavigate(item.path)
    else if (item.trainingTab) onSelectTrainingTab?.(item.trainingTab)
    else if (item.section) onSelect(item.section)
  }
  const renderModule = (module: AdminNavigationModule) => {
    const selected = module.items.some((item) =>
      activeNavigationItem(item, active, activeTrainingTab, pathname),
    )
    const open = expanded[module.id] ?? selected
    const single = module.items.length === 1
    const Icon = module.Icon
    return (
      <div key={module.id} className="px-2 py-0.5">
        <button
          type="button"
          title={t(`module.${module.id}`)}
          aria-expanded={collapsed || single ? undefined : open}
          onClick={() =>
            collapsed || single
              ? pick(module.items.find((item) => item.available) ?? module.items[0])
              : setExpanded((current) => ({ ...current, [module.id]: !open }))
          }
          className={`cursor-sidebar__item sidebar-hover flex min-h-[38px] w-full items-center gap-2.5 rounded-md px-2 text-left ${selected ? 'sidebar-active text-white' : 'text-white/75'} ${collapsed ? 'justify-center' : ''}`}
        >
          <Icon size={18} className="flex-none text-brand-green-200/80" />
          {!collapsed && (
            <>
              <span className="min-w-0 flex-1 text-[12.5px] font-bold">
                {t(`module.${module.id}`)}
              </span>
              {!single && <IconChevRight size={12} className={open ? 'rotate-90' : ''} />}
            </>
          )}
        </button>
        {!collapsed && !single && open && (
          <ul className="ml-4 mt-1 flex flex-col gap-0.5 border-l border-white/10 pl-2">
            {module.items.map((item) => (
              <li key={item.id}>
                <button
                  type="button"
                  disabled={!item.available}
                  onClick={() => pick(item)}
                  data-training-tab={item.trainingTab}
                  aria-current={
                    activeNavigationItem(item, active, activeTrainingTab, pathname)
                      ? 'page'
                      : undefined
                  }
                  className={`sidebar-hover flex min-h-[32px] w-full flex-col justify-center rounded-md px-2 py-1 text-left text-[12px] font-semibold disabled:cursor-default disabled:opacity-40 ${activeNavigationItem(item, active, activeTrainingTab, pathname) ? 'sidebar-active text-white' : 'text-white/70'}`}
                >
                  <span>{item.labelKey ? t(item.labelKey) : item.label}</span>
                  {!item.available && (
                    <span className="text-[10px] font-normal">{t('nav.not_connected')}</span>
                  )}
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
    )
  }

  const contract = getUserContract(user)
  const initials = user.name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0])
    .join('')
    .toUpperCase()

  return (
    <aside
      className={`cursor-sidebar sidebar-bg sticky top-0 z-40 flex h-screen flex-none flex-col overflow-hidden text-white transition-[width] duration-200 ${
        collapsed ? 'w-16' : 'w-[248px]'
      }`}
      aria-label="Основная навигация"
    >
      {/* Logo header */}
      <div
        className={`flex h-[58px] flex-none items-center gap-2.5 border-b border-white/[0.08] px-3 ${
          collapsed ? 'justify-center px-2' : ''
        }`}
      >
        {collapsed ? (
          <button
            onClick={onToggle}
            title="Развернуть сайдбар"
            aria-label="Развернуть сайдбар"
            className="flex h-9 w-9 flex-none items-center justify-center rounded-lg bg-white/[0.07] transition hover:bg-white/[0.12]"
          >
            <Logo size={21} />
          </button>
        ) : (
          <>
            <span className="flex h-9 w-9 flex-none items-center justify-center rounded-lg bg-white/[0.07]">
              <Logo size={22} />
            </span>
            <div className="min-w-0 flex-1">
              <div className="text-[16px] font-extrabold leading-4 tracking-[-0.025em] text-white">
                ePharm
              </div>
              <div className="mt-1 truncate text-[8px] font-semibold uppercase leading-none tracking-[0.08em] text-brand-green-200/65">
                {trainingWorkspace ? 'Центр обучения' : 'Операционный центр'}
              </div>
            </div>
            <button
              onClick={onToggle}
              title="Свернуть"
              aria-label="Свернуть сайдбар"
              className="flex h-7 w-7 flex-none items-center justify-center rounded-md text-white/50 transition hover:bg-white/[0.08] hover:text-white"
            >
              <IconChevLeft size={15} />
            </button>
          </>
        )}
      </div>

      <nav className="cursor-sidebar__nav min-h-0 flex-1 overflow-y-auto overscroll-contain py-2">
        {modules.filter((module) => module.id !== 'management').map(renderModule)}
      </nav>
      <div
        className="flex-none border-t border-white/[0.08] py-1"
        aria-label={t('module.management')}
      >
        {modules.filter((module) => module.id === 'management').map(renderModule)}
      </div>

      {/* Contract widget — рендерится всегда. Без контракта — empty state без цифр. */}
      {!trainingWorkspace && (
        <ContractWidget contract={contract} collapsed={collapsed} onOpen={onContractOpen} />
      )}

      <div
        className={`flex flex-none items-center border-t border-white/[0.08] ${collapsed ? 'justify-center p-2' : 'gap-2.5 px-3 py-2.5'}`}
      >
        <div
          className="flex h-8 w-8 flex-none items-center justify-center rounded-lg bg-brand-green-400/15 text-[10px] font-extrabold tracking-[0.04em] text-brand-green-100 ring-1 ring-inset ring-brand-green-200/15"
          title={collapsed ? `${user.name} · ${roleLabel(user.role)}` : undefined}
          aria-label={collapsed ? `${user.name} · ${roleLabel(user.role)}` : undefined}
        >
          {initials}
        </div>
        {!collapsed && (
          <div className="min-w-0 flex-1">
            <div className="truncate text-[12px] font-bold leading-4 text-white/90">
              {user.name}
            </div>
            <div className="truncate text-[10px] font-medium leading-4 text-white/40">
              {roleLabel(user.role)} · {user.company}
            </div>
          </div>
        )}
      </div>
    </aside>
  )
}

// ─────────────────────────────────────────────────────────────────────────
// ContractWidget — два состояния (has contract / no contract) × collapsed
// ─────────────────────────────────────────────────────────────────────────

interface ContractWidgetProps {
  contract: Contract | null
  collapsed: boolean
  onOpen: () => void
}

function ContractWidget({ contract, collapsed, onOpen }: ContractWidgetProps) {
  const t = useT()
  // ── Collapsed mode — только иконка, без цифр в любом состоянии
  if (collapsed) {
    if (contract) {
      return (
        <div className="border-t border-white/[0.06] p-2">
          <button
            onClick={onOpen}
            title="Активный контракт"
            className="flex h-9 w-full items-center justify-center rounded-lg bg-white/5 text-brand-green-300 hover:bg-white/10"
          >
            <IconShield size={18} />
          </button>
        </div>
      )
    }
    return (
      <div className="border-t border-white/[0.06] p-2">
        <div
          title="Нет активного контракта"
          className="flex h-9 w-full items-center justify-center rounded-lg bg-white/[0.03] text-white/25"
        >
          <IconShield size={18} />
        </div>
      </div>
    )
  }

  // ── Expanded mode — empty state без цифр
  if (!contract) {
    return (
      <div
        className="border-t border-white/[0.06] px-2 py-2"
        aria-label={t('sidebar.contractActive')}
        data-testid="contract-widget-empty"
      >
        <div className="rounded-md border border-white/[0.07] bg-white/[0.025] px-2.5 py-2">
          <div className="flex items-center gap-2">
            <span className="flex h-7 w-7 flex-none items-center justify-center rounded-md bg-white/5 text-white/30">
              <IconShield size={14} />
            </span>
            <div className="min-w-0 flex-1">
              <div className="truncate text-[11px] font-bold uppercase tracking-[0.06em] text-white/40">
                {t('sidebar.contract')}
              </div>
              <div className="truncate text-[13px] font-bold text-white/55">
                {t('sidebar.contractNone')}
              </div>
            </div>
          </div>
          <div className="mt-1 truncate text-[10px] leading-4 text-white/30">
            {t('sidebar.contractHint')}
          </div>
        </div>
      </div>
    )
  }

  // ── Expanded mode — есть контракт, показываем полные данные
  return (
    <div
      className="border-t border-white/[0.06] p-2"
      aria-label="Активный контракт"
      data-testid="contract-widget-active"
    >
      <button
        onClick={onOpen}
        className="w-full rounded-md border border-brand-green-300/15 bg-brand-green-300/[0.06] p-2.5 text-left transition hover:bg-brand-green-300/[0.1]"
      >
        <div className="mb-2 flex items-center gap-2">
          <span className="flex h-7 w-7 items-center justify-center rounded-lg bg-brand-green-600">
            <IconShield size={14} />
          </span>
          <div className="min-w-0 flex-1">
            <div className="text-[11px] font-bold uppercase tracking-[0.06em] text-white/55">
              Активный контракт
            </div>
            <div className="truncate text-[13px] font-bold text-white">{contract.brand}</div>
          </div>
          <IconChevRight size={14} className="text-white/40" />
        </div>
        <div className="mb-1.5 flex items-center justify-between text-[11px] font-semibold text-white/60">
          <span>{contract.brandsCount} бренда · бюджет</span>
          <span className="num text-white">
            {Math.round((contract.budgetUsed / contract.budgetTotal) * 100)}%
          </span>
        </div>
        <div className="h-1.5 overflow-hidden rounded-full bg-white/10">
          <div
            className="h-full bg-brand-green-400"
            style={{ width: `${(contract.budgetUsed / contract.budgetTotal) * 100}%` }}
          />
        </div>
        <div className="num mt-1.5 text-[11px] text-white/45">
          {formatKzt(contract.budgetUsed)} / {formatKzt(contract.budgetTotal)}
        </div>
      </button>
    </div>
  )
}
