import type { ComponentType } from 'react'
import type { AdminRole } from '@/lib/api-types'
import { SECTIONS, type SectionId } from '@/mocks/fixtures'
import {
  IconDashboard,
  IconLMS,
  IconPharmacist,
  IconPharmacy,
  IconPromo,
  IconSettings,
  IconLayers,
} from '@/ui/icons'
import { canAccessSection, isTrainingWorkspaceRole } from './accessPolicy'
import { SECTION_ROUTES } from './routes'
import { TRAINING_NAVIGATION, trainingPath, type TrainingTab } from './trainingNavigation'

export type AdminModuleId =
  | 'analytics'
  | 'motivation'
  | 'training'
  | 'merch'
  | 'network'
  | 'pharmacists'
  | 'management'
export interface AdminNavigationItem {
  id: string
  labelKey?: string
  label?: string
  section?: SectionId
  trainingTab?: TrainingTab
  path?: string
  available: boolean
  hqOnly?: boolean
}
export interface AdminNavigationModule {
  id: AdminModuleId
  Icon: ComponentType<{ size?: number; className?: string }>
  items: AdminNavigationItem[]
}

const section = (id: SectionId): AdminNavigationItem => ({
  id,
  section: id,
  labelKey: `nav.${id}`,
  path: SECTION_ROUTES[id],
  available: true,
})

/** Existing route identities stay unchanged; grouping never grants server permissions. */
export const ADMIN_NAVIGATION: readonly AdminNavigationModule[] = [
  {
    id: 'analytics',
    Icon: IconDashboard,
    items: [
      section('dashboard'),
      section('lift'),
      {
        id: 'campaign_reports',
        section: 'dashboard',
        labelKey: 'nav.campaign_reports',
        path: '/dashboard/reports',
        available: true,
        hqOnly: true,
      },
    ],
  },
  {
    id: 'motivation',
    Icon: IconPromo,
    items: [section('promo'), section('rules'), section('reconcile'), section('finance')],
  },
  {
    id: 'training',
    Icon: IconLMS,
    items: [
      ...TRAINING_NAVIGATION.map((item) => ({
        id: `training_${item.value}`,
        section: 'lms' as const,
        trainingTab: item.value,
        label: item.sidebarLabel,
        path: trainingPath(item.value),
        available: true,
      })),
      section('ai_exam'),
    ],
  },
  {
    id: 'merch',
    Icon: IconLayers,
    items: [
      { id: 'merch_manufacturer', labelKey: 'nav.merch_manufacturer', available: false },
      { id: 'merch_tasks', labelKey: 'nav.merch_tasks', available: false },
      section('merch_inventory'),
    ],
  },
  {
    id: 'network',
    Icon: IconPharmacy,
    items: [
      section('pharmacies'),
      section('storefront'),
      section('screens'),
      section('fulfillment'),
    ],
  },
  { id: 'pharmacists', Icon: IconPharmacist, items: [section('pharmacists')] },
  { id: 'management', Icon: IconSettings, items: [section('settings')] },
]

export function adminNavigationForRole(
  role: AdminRole,
  canViewMerchInventory = false,
): AdminNavigationModule[] {
  return ADMIN_NAVIGATION.map((module) => ({
    ...module,
    items: module.items.filter(
      (item) =>
        (item.section === 'merch_inventory'
          ? canViewMerchInventory
          : !item.section
            ? canViewMerchInventory || !isTrainingWorkspaceRole(role)
            : canAccessSection(role, item.section)) &&
        (!item.hqOnly || role === 'HQ_HEAD' || role === 'SYSTEM_ADMIN'),
    ),
  })).filter((module) => module.items.length > 0)
}

export function activeNavigationItem(
  item: AdminNavigationItem,
  active: SectionId,
  trainingTab: TrainingTab,
  pathname: string,
): boolean {
  if (item.trainingTab) return active === 'lms' && item.trainingTab === trainingTab
  if (item.id === 'campaign_reports') return pathname.startsWith('/dashboard/reports')
  return (
    item.section === active &&
    !(active === 'dashboard' && pathname.startsWith('/dashboard/reports'))
  )
}

export function navigationIcon(item: AdminNavigationItem) {
  if (item.trainingTab)
    return TRAINING_NAVIGATION.find((tab) => tab.value === item.trainingTab)?.Icon
  return SECTIONS.find((entry) => entry.id === item.section)?.Icon
}
