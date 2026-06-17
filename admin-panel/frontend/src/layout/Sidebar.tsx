// Sidebar — admin §7.1
// Тёмный фон (sidebar-bg radial-gradient), 12 пунктов сгруппированы по 6 категориям.
// Collapsed: 72px. Expanded: 260px + Contract widget внизу (для всех пользователей —
// у кого нет контракта показывается empty state без цифр).

import {
  SECTIONS,
  formatKzt,
  getUserContract,
  type Contract,
  type Section,
  type SectionId,
  type User,
} from '@/mocks/fixtures'
import { IconChevLeft, IconChevRight, IconShield } from '@/ui/icons'
import { useT } from '@/i18n'
import { Logo } from './Logo'

interface SidebarProps {
  active: SectionId
  onSelect: (id: SectionId) => void
  collapsed: boolean
  onToggle: () => void
  onContractOpen: () => void
  user: User
}

const GROUP_ORDER = ['Обзор', 'Кампании', 'Сеть', 'Операции', 'Аналитика', 'Система'] as const

export function Sidebar({
  active,
  onSelect,
  collapsed,
  onToggle,
  onContractOpen,
  user,
}: SidebarProps) {
  const t = useT()
  const groups = SECTIONS.reduce<Record<string, Section[]>>((acc, s) => {
    ;(acc[s.group] = acc[s.group] || []).push(s)
    return acc
  }, {})

  const contract = getUserContract(user)

  return (
    <aside
      className={`sidebar-bg relative flex flex-none flex-col text-white transition-[width] duration-200 ${
        collapsed ? 'w-[72px]' : 'w-[260px]'
      }`}
    >
      {/* Logo header */}
      <div
        className={`flex h-16 items-center gap-3 border-b border-white/5 px-4 ${
          collapsed ? 'justify-center px-2' : ''
        }`}
      >
        {collapsed ? (
          <button
            onClick={onToggle}
            title="Развернуть"
            className="flex h-10 w-10 flex-none items-center justify-center rounded-xl bg-white/10 transition hover:bg-white/15"
          >
            <Logo size={22} />
          </button>
        ) : (
          <>
            <span className="flex h-9 w-9 flex-none items-center justify-center rounded-xl bg-white/10">
              <Logo size={22} />
            </span>
            <div className="min-w-0 flex-1">
              {/* Текстовый wordmark «Epharm» убран — бренд = логотип-глиф слева. */}
              <div className="text-[11px] font-bold uppercase tracking-[0.08em] text-white/45">
                Console · HQ
              </div>
            </div>
            <button
              onClick={onToggle}
              title="Свернуть"
              className="flex h-7 w-7 items-center justify-center rounded-md text-white/55 hover:bg-white/10"
            >
              <IconChevLeft size={16} />
            </button>
          </>
        )}
      </div>

      {/* Floating expand tab — only visible when collapsed */}
      {collapsed && (
        <button
          onClick={onToggle}
          title="Развернуть сайдбар"
          className="absolute -right-3 top-1/2 z-10 flex h-12 w-6 -translate-y-1/2 items-center justify-center rounded-r-lg border border-l-0 border-white/10 bg-ink-900 text-white/70 shadow-elevated hover:bg-ink-800 hover:text-white"
          style={{ boxShadow: '4px 0 12px rgba(15,20,36,0.18)' }}
        >
          <IconChevRight size={14} />
        </button>
      )}

      {/* Navigation */}
      <nav className="scrollbar-thin flex-1 overflow-y-auto py-3">
        {GROUP_ORDER.map(
          (g) =>
            groups[g] && (
              <div key={g} className="mb-3">
                {!collapsed && (
                  <div className="mb-1.5 px-4 text-[10px] font-bold uppercase tracking-[0.1em] text-white/40">
                    {t(`group.${g}`)}
                  </div>
                )}
                <ul className="flex flex-col gap-0.5 px-2">
                  {groups[g].map((s) => {
                    const Icon = s.Icon
                    const isActive = active === s.id
                    return (
                      <li key={s.id}>
                        <button
                          onClick={() => onSelect(s.id)}
                          className={`sidebar-hover flex h-10 w-full items-center gap-3 rounded-lg px-2.5 ${
                            isActive ? 'sidebar-active text-white' : 'text-white/75'
                          } ${collapsed ? 'justify-center' : ''}`}
                          title={collapsed ? t(`nav.${s.id}`) : ''}
                        >
                          <span className={isActive ? 'text-brand-green-400' : 'text-white/65'}>
                            <Icon size={20} />
                          </span>
                          {!collapsed && (
                            <>
                              <span className="flex-1 truncate text-left text-sm font-semibold">
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
      </nav>

      {/* Contract widget — рендерится всегда. Без контракта — empty state без цифр. */}
      <ContractWidget contract={contract} collapsed={collapsed} onOpen={onContractOpen} />
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
        <div className="border-t border-white/5 p-3">
          <button
            onClick={onOpen}
            title="Активный контракт"
            className="flex h-10 w-full items-center justify-center rounded-lg bg-white/5 text-brand-green-400 hover:bg-white/10"
          >
            <IconShield size={18} />
          </button>
        </div>
      )
    }
    return (
      <div className="border-t border-white/5 p-3">
        <div
          title="Нет активного контракта"
          className="flex h-10 w-full items-center justify-center rounded-lg bg-white/[0.03] text-white/30"
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
        className="border-t border-white/5 p-3"
        aria-label={t('sidebar.contractActive')}
        data-testid="contract-widget-empty"
      >
        <div className="rounded-xl border border-dashed border-white/10 bg-white/[0.02] p-3">
          <div className="flex items-center gap-2">
            <span className="flex h-7 w-7 flex-none items-center justify-center rounded-lg bg-white/5 text-white/35">
              <IconShield size={14} />
            </span>
            <div className="min-w-0 flex-1">
              <div className="truncate text-[11px] font-bold uppercase tracking-[0.06em] text-white/40">
                {t('sidebar.contract')}
              </div>
              <div className="truncate text-[13px] font-extrabold text-white/55">
                {t('sidebar.contractNone')}
              </div>
            </div>
          </div>
          <div className="mt-2 text-[11px] leading-snug text-white/35">
            {t('sidebar.contractHint')}
          </div>
        </div>
      </div>
    )
  }

  // ── Expanded mode — есть контракт, показываем полные данные
  return (
    <div
      className="border-t border-white/5 p-3"
      aria-label="Активный контракт"
      data-testid="contract-widget-active"
    >
      <button
        onClick={onOpen}
        className="w-full rounded-xl border border-white/5 bg-gradient-to-br from-brand-green-700/40 to-brand-green-700/10 p-3 text-left transition hover:from-brand-green-700/55"
      >
        <div className="mb-2 flex items-center gap-2">
          <span className="flex h-7 w-7 items-center justify-center rounded-lg bg-brand-green-600">
            <IconShield size={14} />
          </span>
          <div className="min-w-0 flex-1">
            <div className="text-[11px] font-bold uppercase tracking-[0.06em] text-white/55">
              Активный контракт
            </div>
            <div className="truncate text-[13px] font-extrabold text-white">{contract.brand}</div>
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
