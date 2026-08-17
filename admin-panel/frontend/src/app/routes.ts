import type { SectionId } from '@/mocks/fixtures'

/** Canonical path for every navigation section. */
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
