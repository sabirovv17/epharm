// AppShell — Sidebar + Topbar + общие модалки + Outlet.
// ⌘K / Ctrl+K listener, esc для CommandPalette, sync Sidebar.active с роутом.
// Защищён RequireAuth — authedUser гарантированно не null.

import { useEffect, useMemo } from 'react'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { type SectionId } from '@/mocks/fixtures'
import { Sidebar, Topbar, CommandPalette, RoleSwitcher, ContractModal } from '@/layout'
import { ToastHost } from '@/ui'
import { useT } from '@/i18n'
import { useUiStore } from './store'
import { SECTION_ROUTES } from './routes'
import { adminNavigationForRole } from './adminNavigation'
import { useAdministrativeCapabilities } from './useAdministrativeCapabilities'
import {
  canAccessSection,
  defaultSectionForRole,
  isTrainingWorkspaceRole,
  workspaceLabelForRole,
} from './accessPolicy'
import {
  trainingItem,
  trainingPath,
  trainingTabFromSearch,
  type TrainingTab,
} from './trainingNavigation'

export function AppShell() {
  const navigate = useNavigate()
  const location = useLocation()
  const t = useT()
  const {
    canViewMerchInventory,
    canViewMerchPlacements,
    canViewMerchTasks,
    canViewMerchStaffAccess,
    canViewLedger,
  } = useAdministrativeCapabilities()

  const {
    authedUser,
    sidebarCollapsed,
    commandPaletteOpen,
    roleSwitcherOpen,
    contractModalOpen,
    toggleSidebar,
    openCommandPalette,
    closeCommandPalette,
    openRoleSwitcher,
    closeRoleSwitcher,
    openContractModal,
    closeContractModal,
    setActiveRole,
    logout,
  } = useUiStore()

  // Sync active section with current pathname.
  const activeSection = useMemo<SectionId>(() => {
    if (location.pathname.startsWith('/merch/staff-access')) return 'merch_tasks'
    const entry = (Object.entries(SECTION_ROUTES) as [SectionId, string][])
      .sort((a, b) => b[1].length - a[1].length)
      .find(([, path]) => location.pathname.startsWith(path))
    return entry?.[0] ?? defaultSectionForRole(authedUser?.role ?? 'HQ_HEAD')
  }, [authedUser?.role, location.pathname])

  const activeTrainingTab = useMemo(
    () =>
      trainingTabFromSearch(
        location.search,
        authedUser ? isTrainingWorkspaceRole(authedUser.role) : false,
      ),
    [authedUser, location.search],
  )
  const activeLabel =
    location.pathname === '/account'
      ? t('account.title')
      : location.pathname.startsWith('/merch/staff-access')
        ? 'Общие коды отключены'
        : activeSection === 'lms' && authedUser && isTrainingWorkspaceRole(authedUser.role)
          ? trainingItem(activeTrainingTab).sidebarLabel
          : t(`nav.${activeSection}`)

  const onSelectSection = (id: SectionId) => {
    if (
      authedUser &&
      (id === 'ledger'
        ? canViewLedger
        : id === 'merch_inventory'
          ? canViewMerchInventory
          : id === 'merch_placements'
            ? canViewMerchPlacements
            : id === 'merch_tasks'
              ? canViewMerchTasks
              : canAccessSection(authedUser.role, id))
    )
      navigate(SECTION_ROUTES[id])
  }

  const onSelectTrainingTab = (tab: TrainingTab) => {
    if (authedUser && canAccessSection(authedUser.role, 'lms')) navigate(trainingPath(tab))
  }

  const onNavigate = (path: string) => {
    if (
      authedUser &&
      adminNavigationForRole(authedUser.role, {
        inventory: canViewMerchInventory,
        placements: canViewMerchPlacements,
        tasks: canViewMerchTasks,
        staffAccess: canViewMerchStaffAccess,
        ledger: canViewLedger,
      }).some((module) => module.items.some((item) => item.available && item.path === path))
    )
      navigate(path)
  }

  // Global ⌘K / Ctrl+K — toggle command palette
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault()
        if (commandPaletteOpen) closeCommandPalette()
        else openCommandPalette()
      } else if (e.key === 'Escape' && commandPaletteOpen) {
        closeCommandPalette()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [commandPaletteOpen, openCommandPalette, closeCommandPalette])

  const handleLogout = () => {
    logout()
    navigate('/login', { replace: true })
  }

  // RequireAuth уже гарантирует не-null user; этот guard — для TS narrowing.
  if (!authedUser) return null

  // Смена роли — ДЕМО-механика (подменяет authedUser фейком без нового JWT):
  // UI показывал бы чужую личность, а API-запросы шли бы под исходным токеном.
  // В проде прячем и пункт меню, и саму модалку — роли назначает админ на бэке.
  const roleSwitchEnabled = import.meta.env.DEV

  return (
    <ToastHost>
      <div className="admin-console flex h-screen min-w-[1024px] overflow-hidden bg-paper">
        <Sidebar
          active={activeSection}
          onSelect={onSelectSection}
          collapsed={sidebarCollapsed}
          onToggle={toggleSidebar}
          onContractOpen={openContractModal}
          user={authedUser}
          activeTrainingTab={activeTrainingTab}
          onSelectTrainingTab={onSelectTrainingTab}
          pathname={location.pathname}
          onNavigate={onNavigate}
          canViewMerchInventory={canViewMerchInventory}
          canViewMerchPlacements={canViewMerchPlacements}
          canViewMerchTasks={canViewMerchTasks}
          canViewMerchStaffAccess={canViewMerchStaffAccess}
          canViewLedger={canViewLedger}
        />
        <div className="flex min-w-0 flex-1 flex-col">
          <Topbar
            sectionLabel={activeLabel}
            workspaceLabel={workspaceLabelForRole(authedUser.role)}
            role={authedUser}
            onRoleSwitch={roleSwitchEnabled ? openRoleSwitcher : undefined}
            onLogout={handleLogout}
            onProfile={() => navigate('/account')}
            onMenu={toggleSidebar}
            onCommand={openCommandPalette}
          />
          <main className="scrollbar-thin flex-1 overflow-auto px-5 pb-8 pt-4">
            <Outlet />
          </main>
        </div>

        {roleSwitchEnabled && (
          <RoleSwitcher
            open={roleSwitcherOpen}
            onClose={closeRoleSwitcher}
            current={authedUser}
            onPick={setActiveRole}
          />
        )}
        <ContractModal open={contractModalOpen} onClose={closeContractModal} user={authedUser} />
        {commandPaletteOpen && (
          <CommandPalette
            user={authedUser}
            onClose={closeCommandPalette}
            onNav={onSelectSection}
            onNavigate={onNavigate}
            canViewMerchInventory={canViewMerchInventory}
            canViewMerchPlacements={canViewMerchPlacements}
            canViewMerchTasks={canViewMerchTasks}
            canViewMerchStaffAccess={canViewMerchStaffAccess}
            canViewLedger={canViewLedger}
          />
        )}
      </div>
    </ToastHost>
  )
}
