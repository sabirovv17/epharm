// Sidebar — компактный двухрежимный workspace-nav в духе Cursor.
// Навигация прокручивается отдельно, а бренд, контракт и профиль остаются на месте.

import {
  formatKzt,
  getUserContract,
  roleLabel,
  type Contract,
  type Section,
  type SectionId,
  type User,
} from '@/mocks/fixtures'
import { IconChevLeft, IconChevRight, IconShield } from '@/ui/icons'
import { useT } from '@/i18n'
import { isTrainingWorkspaceRole, sectionsForRole } from '@/app/accessPolicy'
import {
  TRAINING_NAVIGATION,
  TRAINING_NAVIGATION_GROUPS,
  type TrainingTab,
} from '@/app/trainingNavigation'
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
}

const GROUP_ORDER = ['Обзор', 'Кампании', 'Сеть', 'Операции', 'Аналитика', 'Система'] as const

export function Sidebar({
  active,
  onSelect,
  collapsed,
  onToggle,
  onContractOpen,
  user,
  activeTrainingTab = 'overview',
  onSelectTrainingTab,
}: SidebarProps) {
  const t = useT()
  const trainingWorkspace = isTrainingWorkspaceRole(user.role)
  const availableSections = sectionsForRole(user.role).filter(
    (section) => !trainingWorkspace || section.id !== 'lms',
  )
  const groups = availableSections.reduce<Record<string, Section[]>>((acc, s) => {
    ;(acc[s.group] = acc[s.group] || []).push(s)
    return acc
  }, {})

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

      {/* Navigation */}
      <nav className="cursor-sidebar__nav min-h-0 flex-1 overflow-y-auto overscroll-contain py-2">
        {GROUP_ORDER.map(
          (g) =>
            groups[g] && (
              <div key={g} className="mb-1.5">
                {!collapsed && (
                  <div className="mb-0.5 px-3 pt-1.5 text-[9px] font-bold uppercase tracking-[0.11em] text-brand-green-100/45">
                    {t(`group.${g}`)}
                  </div>
                )}
                <ul className="flex flex-col gap-px px-2">
                  {groups[g].map((s) => {
                    const Icon = s.Icon
                    const isActive = active === s.id
                    return (
                      <li key={s.id}>
                        <button
                          onClick={() => onSelect(s.id)}
                          className={`cursor-sidebar__item sidebar-hover flex h-[34px] w-full items-center gap-2.5 rounded-md px-2 ${
                            isActive ? 'sidebar-active text-white' : 'text-white/75'
                          } ${collapsed ? 'justify-center' : ''}`}
                          title={collapsed ? t(`nav.${s.id}`) : ''}
                        >
                          <span className={`flex flex-none ${isActive ? 'text-brand-green-200' : 'text-white/55'}`}>
                            <Icon size={18} />
                          </span>
                          {!collapsed && (
                            <>
                              <span className="flex-1 truncate text-left text-[12.5px] font-semibold leading-none">
                                {t(`nav.${s.id}`)}
                              </span>
                              {s.badge && (
                                <span
                                  className={`inline-flex h-[18px] items-center rounded-full px-1.5 text-[10px] font-bold ${
                                    s.badge === '!'
                                      ? 'bg-accent-danger text-white'
                                      : 'bg-white/15 text-white/80'
                                  }`}
                                >
                                  {s.badge}
                                </span>
                              )}
                            </>
                          )}
                        </button>
                      </li>
                    )
                  })}
                </ul>
              </div>
            ),
        )}

        {trainingWorkspace &&
          TRAINING_NAVIGATION_GROUPS.map((group) => {
            const items = TRAINING_NAVIGATION.filter((item) => item.group === group)
            return (
              <div key={group} className="mb-1.5">
                {!collapsed && (
                  <div className="mb-0.5 px-3 pt-1.5 text-[9px] font-bold uppercase tracking-[0.11em] text-brand-green-100/45">
                    {group}
                  </div>
                )}
                <ul className="flex flex-col gap-px px-2">
                  {items.map((item) => {
                    const Icon = item.Icon
                    const isActive = active === 'lms' && activeTrainingTab === item.value
                    return (
                      <li key={item.value}>
                        <button
                          type="button"
                          onClick={() => onSelectTrainingTab?.(item.value)}
                          className={`cursor-sidebar__item sidebar-hover flex h-[34px] w-full items-center gap-2.5 rounded-md px-2 ${
                            isActive ? 'sidebar-active text-white' : 'text-white/75'
                          } ${collapsed ? 'justify-center' : ''}`}
                          title={collapsed ? item.sidebarLabel : ''}
                          aria-current={isActive ? 'page' : undefined}
                          data-training-tab={item.value}
                        >
                          <span
                            className={`flex-none ${
                              isActive ? 'text-brand-green-200' : 'text-white/55'
                            }`}
                          >
                            <Icon size={18} />
                          </span>
                          {!collapsed && (
                            <span className="min-w-0 flex-1 truncate text-left text-[12.5px] font-semibold leading-none">
                              {item.sidebarLabel}
                            </span>
                          )}
                        </button>
                      </li>
                    )
                  })}
                </ul>
              </div>
            )
          })}
      </nav>

      {/* Contract widget — рендерится всегда. Без контракта — empty state без цифр. */}
      {!trainingWorkspace && (
        <ContractWidget contract={contract} collapsed={collapsed} onOpen={onContractOpen} />
      )}

      <div className={`flex flex-none items-center border-t border-white/[0.08] ${collapsed ? 'justify-center p-2' : 'gap-2.5 px-3 py-2.5'}`}>
        <div
          className="flex h-8 w-8 flex-none items-center justify-center rounded-lg bg-brand-green-400/15 text-[10px] font-extrabold tracking-[0.04em] text-brand-green-100 ring-1 ring-inset ring-brand-green-200/15"
          title={collapsed ? `${user.name} · ${roleLabel(user.role)}` : undefined}
          aria-label={collapsed ? `${user.name} · ${roleLabel(user.role)}` : undefined}
        >
          {initials}
        </div>
        {!collapsed && (
          <div className="min-w-0 flex-1">
            <div className="truncate text-[12px] font-bold leading-4 text-white/90">{user.name}</div>
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
