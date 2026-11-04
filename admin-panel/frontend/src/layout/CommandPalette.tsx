// CommandPalette — admin §11.4
// ⌘K / Ctrl+K (или клик на topbar search) → top-aligned 12vh, max-w 560.
// Фильтрует доступные разделы админки и реальный реестр аптек. Аптеки
// запрашиваются только для роли с доступом к сети и после ввода минимум двух знаков.

import { useState, type ReactNode } from 'react'
import type { UserDto } from '@/lib/api-types'
import { type SectionId } from '@/mocks/fixtures'
import { adminNavigationForRole, navigationIcon } from '@/app/adminNavigation'
import { canAccessSection } from '@/app/accessPolicy'
import { usePharmacies } from '@/lib/queries/pharmacies'
import { IconSearch } from '@/ui/icons'
import { useT } from '@/i18n'

interface CommandPaletteProps {
  onClose: () => void
  onNav: (id: SectionId) => void
  user: UserDto
  onNavigate?: (path: string) => void
  onOpenPharmacy?: (id: string) => void
  canViewMerchInventory?: boolean
  canViewMerchPlacements?: boolean
  canViewMerchTasks?: boolean
  canViewMerchStaffAccess?: boolean
  canViewLedger?: boolean
}

interface Item {
  id: string
  section?: SectionId
  path: string
  label: string
  sub: string
  icon: ReactNode
  kind: 'section' | 'pharmacy'
}

// Монтируется ТОЛЬКО когда открыта (см. AppShell), поэтому состояние поиска
// сбрасывается само при каждом открытии — без setState-в-effect (cascading renders).
export function CommandPalette({
  onClose,
  onNav,
  user,
  onNavigate,
  onOpenPharmacy,
  canViewMerchInventory = false,
  canViewMerchPlacements = false,
  canViewMerchTasks = false,
  canViewMerchStaffAccess = false,
  canViewLedger = false,
}: CommandPaletteProps) {
  const t = useT()
  const [q, setQ] = useState('')
  const normalizedQuery = q.trim().toLocaleLowerCase('ru')
  const canSearchPharmacies = canAccessSection(user.role, 'pharmacies')
  const {
    data: pharmacies = [],
    isFetching: pharmaciesLoading,
    isError: pharmaciesError,
  } = usePharmacies(
    {},
    canSearchPharmacies && normalizedQuery.length >= 2,
  )

  const items: Item[] = adminNavigationForRole(user.role, {
    inventory: canViewMerchInventory,
    placements: canViewMerchPlacements,
    tasks: canViewMerchTasks,
    staffAccess: canViewMerchStaffAccess,
    ledger: canViewLedger,
  }).flatMap((module) =>
    module.items
      .filter((item) => item.available && item.path)
      .map((item) => {
        const Icon = navigationIcon(item) ?? module.Icon
        return {
          id: item.id,
          section: item.section,
          path: item.path!,
          label: item.labelKey ? t(item.labelKey) : (item.label ?? ''),
          sub: t(`module.${module.id}`),
          icon: <Icon size={16} />,
          kind: 'section' as const,
        }
      }),
  )

  const filteredSections = normalizedQuery
    ? items.filter((i) => `${i.label} ${i.sub}`.toLocaleLowerCase('ru').includes(normalizedQuery))
    : items
  const pharmacyItems: Item[] =
    normalizedQuery.length >= 2
      ? pharmacies
          .filter((pharmacy) =>
            [
              pharmacy.name,
              pharmacy.chainName,
              pharmacy.city,
              pharmacy.district,
              pharmacy.addr,
            ]
              .join(' ')
              .toLocaleLowerCase('ru')
              .includes(normalizedQuery),
          )
          .slice(0, 8)
          .map((pharmacy) => ({
            id: `pharmacy:${pharmacy.id}`,
            section: 'pharmacies' as const,
            path: `/pharmacies/${pharmacy.id}`,
            label: pharmacy.name,
            sub: [pharmacy.city, pharmacy.addr].filter(Boolean).join(' · '),
            icon: <IconSearch size={16} />,
            kind: 'pharmacy' as const,
          }))
      : []
  const filtered = [...filteredSections, ...pharmacyItems]

  const onPick = (it: Item) => {
    if (it.kind === 'pharmacy' && onOpenPharmacy) onOpenPharmacy(it.path.split('/').at(-1)!)
    else if (onNavigate) onNavigate(it.path)
    else if (it.section) onNav(it.section)
    onClose()
  }

  return (
    <div
      className="scrim fixed inset-0 z-[85] flex items-start justify-center px-4 pt-[12vh]"
      onClick={onClose}
    >
      <div
        className="card slide-in w-full max-w-[560px] overflow-hidden shadow-elevated"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="hairline flex h-12 items-center gap-2 border-b px-3">
          <IconSearch size={16} className="text-ink-400" />
          <input
            autoFocus
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder={t('palette.search')}
            className="flex-1 bg-transparent text-sm font-semibold text-ink-900 outline-none"
          />
          <span className="kbd">esc</span>
        </div>
        <div className="scrollbar-thin max-h-[50vh] overflow-auto py-2">
          {pharmaciesError && normalizedQuery.length >= 2 && (
            <div role="alert" className="py-8 text-center text-[13px] text-accent-danger">
              {t('palette.error')}
            </div>
          )}
          {filtered.length === 0 && !pharmaciesLoading && !pharmaciesError ? (
            <div className="py-8 text-center text-[13px] text-ink-400">{t('palette.empty')}</div>
          ) : (
            filtered.map((it) => (
              <button
                key={it.id}
                onClick={() => onPick(it)}
                className="flex w-full items-center gap-3 px-3 py-2 text-left hover:bg-paper-hover"
              >
                <span className="flex h-7 w-7 flex-none items-center justify-center rounded-md bg-ink-100 text-ink-500">
                  {it.icon}
                </span>
                <div className="min-w-0 flex-1">
                  <div className="truncate text-sm font-bold text-ink-900">{it.label}</div>
                  <div className="truncate text-[12px] text-ink-500">{it.sub}</div>
                </div>
                <span className="text-[11px] font-bold uppercase text-ink-400">
                  {t(it.kind === 'pharmacy' ? 'palette.kind.pharmacy' : 'palette.kind.section')}
                </span>
              </button>
            ))
          )}
        </div>
      </div>
    </div>
  )
}
