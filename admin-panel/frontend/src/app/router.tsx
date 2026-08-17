// React Router v6 — 12 routes + /login. Каждая защищённая секция lazy-loaded.
// /login отдельный (без AppShell). RequireAuth защищает всё кроме /login.

import { lazy, Suspense } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import { AppShell } from './AppShell'
import { RequireAuth } from './RequireAuth'
import NotFoundPage from './NotFoundPage'
import type { SectionId } from '@/mocks/fixtures'
import { useUiStore } from './store'
import { defaultPathForRole } from './accessPolicy'
import { SectionRoute } from './SectionRoute'

const LoginPage = lazy(() => import('@/features/auth/LoginPage'))
const Dashboard = lazy(() => import('@/features/dashboard/DashboardPage'))
const Promo = lazy(() => import('@/features/promo/PromoPage'))
const PromoDetail = lazy(() => import('@/features/promo/PromoDetailPage'))
const Rules = lazy(() => import('@/features/rules/RulesPage'))
const Screens = lazy(() => import('@/features/screens/ScreensPage'))
const Pharmacies = lazy(() => import('@/features/pharmacies/PharmaciesPage'))
const PharmacyDetail = lazy(() => import('@/features/pharmacies/PharmacyDetailPage'))
const Pharmacists = lazy(() => import('@/features/pharmacists/PharmacistsPage'))
const Reconcile = lazy(() => import('@/features/reconcile/ReconcilePage'))
const AIExam = lazy(() => import('@/features/ai-exam/AIExamPage'))
const Finance = lazy(() => import('@/features/finance/FinancePage'))
const Lift = lazy(() => import('@/features/lift/LiftPage'))
const LMS = lazy(() => import('@/features/lms/LMSPage'))
const Settings = lazy(() => import('@/features/settings/SettingsPage'))
const Storefront = lazy(() => import('@/features/storefront/StorefrontPage'))

function PageFallback() {
  return (
    <div className="flex items-center justify-center py-20 text-[13px] text-ink-400">Загрузка…</div>
  )
}

function withSuspense(node: React.ReactNode) {
  return <Suspense fallback={<PageFallback />}>{node}</Suspense>
}

function protectedPage(section: SectionId, page: React.ReactNode) {
  return <SectionRoute section={section}>{withSuspense(page)}</SectionRoute>
}

function HomeRedirect() {
  const user = useUiStore((state) => state.authedUser)
  return <Navigate to={user ? defaultPathForRole(user.role) : '/login'} replace />
}

export function AppRouter() {
  return (
    <Routes>
      {/* Public — login screen без AppShell */}
      <Route path="/login" element={withSuspense(<LoginPage />)} />

      {/* Private — всё за RequireAuth → AppShell с Sidebar + Topbar */}
      <Route element={<RequireAuth />}>
        <Route element={<AppShell />}>
          <Route path="/dashboard" element={protectedPage('dashboard', <Dashboard />)} />
          <Route path="/promo" element={protectedPage('promo', <Promo />)} />
          <Route path="/promo/:id" element={protectedPage('promo', <PromoDetail />)} />
          <Route path="/rules" element={protectedPage('rules', <Rules />)} />
          <Route path="/screens" element={protectedPage('screens', <Screens />)} />
          {/* Баннеры переехали внутрь раздела «Экраны» (вкладка). Редирект для старых ссылок. */}
          <Route path="/banners" element={<Navigate to="/screens" replace />} />
          <Route path="/pharmacies" element={protectedPage('pharmacies', <Pharmacies />)} />
          <Route path="/pharmacies/:id" element={protectedPage('pharmacies', <PharmacyDetail />)} />
          <Route path="/pharmacists" element={protectedPage('pharmacists', <Pharmacists />)} />
          <Route path="/reconcile" element={protectedPage('reconcile', <Reconcile />)} />
          <Route path="/ai-exam" element={protectedPage('ai_exam', <AIExam />)} />
          <Route path="/finance" element={protectedPage('finance', <Finance />)} />
          <Route path="/lift" element={protectedPage('lift', <Lift />)} />
          <Route path="/lms" element={protectedPage('lms', <LMS />)} />
          <Route path="/settings" element={protectedPage('settings', <Settings />)} />
          <Route path="/storefront" element={protectedPage('storefront', <Storefront />)} />
          <Route path="/" element={<HomeRedirect />} />
          <Route path="*" element={<NotFoundPage />} />
        </Route>
      </Route>
    </Routes>
  )
}
