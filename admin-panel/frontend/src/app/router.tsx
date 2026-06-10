// React Router v6 — 12 routes + /login. Каждая защищённая секция lazy-loaded.
// /login отдельный (без AppShell). RequireAuth защищает всё кроме /login.

import { lazy, Suspense } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import { AppShell } from './AppShell'
import { RequireAuth } from './RequireAuth'
import NotFoundPage from './NotFoundPage'
import type { SectionId } from '@/mocks/fixtures'

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

// path ↔ SectionId mapping. Используется и Sidebar (highlight) и router (path).
export const SECTION_ROUTES: Record<SectionId, string> = {
  dashboard: '/dashboard',
  promo: '/promo',
  rules: '/rules',
  screens: '/screens',
  pharmacies: '/pharmacies',
  pharmacists: '/pharmacists',
  reconcile: '/reconcile',
  ai_exam: '/ai-exam',
  finance: '/finance',
  lift: '/lift',
  lms: '/lms',
  settings: '/settings',
  storefront: '/storefront',
}

function PageFallback() {
  return (
    <div className="flex items-center justify-center py-20 text-[13px] text-ink-400">Загрузка…</div>
  )
}

function withSuspense(node: React.ReactNode) {
  return <Suspense fallback={<PageFallback />}>{node}</Suspense>
}

export function AppRouter() {
  return (
    <Routes>
      {/* Public — login screen без AppShell */}
      <Route path="/login" element={withSuspense(<LoginPage />)} />

      {/* Private — всё за RequireAuth → AppShell с Sidebar + Topbar */}
      <Route element={<RequireAuth />}>
        <Route element={<AppShell />}>
          <Route path="/dashboard" element={withSuspense(<Dashboard />)} />
          <Route path="/promo" element={withSuspense(<Promo />)} />
          <Route path="/promo/:id" element={withSuspense(<PromoDetail />)} />
          <Route path="/rules" element={withSuspense(<Rules />)} />
          <Route path="/screens" element={withSuspense(<Screens />)} />
          <Route path="/pharmacies" element={withSuspense(<Pharmacies />)} />
          <Route path="/pharmacies/:id" element={withSuspense(<PharmacyDetail />)} />
          <Route path="/pharmacists" element={withSuspense(<Pharmacists />)} />
          <Route path="/reconcile" element={withSuspense(<Reconcile />)} />
          <Route path="/ai-exam" element={withSuspense(<AIExam />)} />
          <Route path="/finance" element={withSuspense(<Finance />)} />
          <Route path="/lift" element={withSuspense(<Lift />)} />
          <Route path="/lms" element={withSuspense(<LMS />)} />
          <Route path="/settings" element={withSuspense(<Settings />)} />
          <Route path="/storefront" element={withSuspense(<Storefront />)} />
          <Route path="/" element={<Navigate to="/rules" replace />} />
          <Route path="*" element={<NotFoundPage />} />
        </Route>
      </Route>
    </Routes>
  )
}
