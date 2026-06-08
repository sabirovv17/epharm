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
import { SECTION_ROUTES } from './router'

export function AppShell() {
  const navigate = useNavigate()
  const location = useLocation()
  const t = useT()

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
    const entry = (Object.entries(SECTION_ROUTES) as [SectionId, string][]).find(([, path]) =>
      location.pathname.startsWith(path),
    )
    return entry?.[0] ?? 'rules'
  }, [location.pathname])

  const activeLabel = t(`nav.${activeSection}`)

  const onSelectSection = (id: SectionId) => navigate(SECTION_ROUTES[id])

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

  return (
    <ToastHost>
      <div className="flex h-screen bg-paper" style={{ minWidth: 1280 }}>
        <Sidebar
          active={activeSection}
          onSelect={onSelectSection}
          collapsed={sidebarCollapsed}
          onToggle={toggleSidebar}
          onContractOpen={openContractModal}
          user={authedUser}
        />
        <div className="flex min-w-0 flex-1 flex-col">
          <Topbar
            sectionLabel={activeLabel}
            role={authedUser}
            onRoleSwitch={openRoleSwitcher}
            onLogout={handleLogout}
            onMenu={toggleSidebar}
            onCommand={openCommandPalette}
          />
          <main className="scrollbar-thin flex-1 overflow-auto px-6 pb-10 pt-6">
            <Outlet />
          </main>
        </div>

        <RoleSwitcher
          open={roleSwitcherOpen}
          onClose={closeRoleSwitcher}
          current={authedUser}
          onPick={setActiveRole}
        />
        <ContractModal open={contractModalOpen} onClose={closeContractModal} user={authedUser} />
        <CommandPalette
          open={commandPaletteOpen}
          onClose={closeCommandPalette}
          onNav={onSelectSection}
        />
      </div>
    </ToastHost>
  )
}
