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
    items: [
      section('promo'),
      section('rules'),
      section('reconcile'),
      section('ledger'),
      section('finance'),
    ],
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
      section('merch_placements'),
      section('merch_tasks'),
      {
        id: 'staff_access',
        section: 'merch_tasks',
        label: 'Коды доступа',
        path: '/merch/staff-access',
        available: true,
      },
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

export interface MerchNavigationCapabilities {
  inventory?: boolean
  placements?: boolean
  tasks?: boolean
  staffAccess?: boolean
  ledger?: boolean
}
export function adminNavigationForRole(
  role: AdminRole,
  capabilities: boolean | MerchNavigationCapabilities = false,
): AdminNavigationModule[] {
  const caps = typeof capabilities === 'boolean' ? { inventory: capabilities } : capabilities
  const merchGranted = caps.inventory || caps.placements || caps.tasks || caps.staffAccess
  return ADMIN_NAVIGATION.map((module) => ({
    ...module,
    items: module.items.flatMap((item) => {
      if (item.id === 'staff_access') return caps.staffAccess ? [item] : []
      if (item.section === 'ledger') return caps.ledger ? [item] : []
      if (item.section?.startsWith('merch_')) {
        const permitted =
          item.section === 'merch_inventory'
            ? caps.inventory
            : item.section === 'merch_placements'
              ? caps.placements
              : caps.tasks
        if (permitted) return [item]
        return item.section !== 'merch_inventory' &&
          (!isTrainingWorkspaceRole(role) || merchGranted)
          ? [{ ...item, available: false, path: undefined }]
          : []
      }
      return item.section &&
        canAccessSection(role, item.section) &&
        (!item.hqOnly || role === 'HQ_HEAD' || role === 'SYSTEM_ADMIN')
        ? [item]
        : []
    }),
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
  if (item.id === 'staff_access') return pathname.startsWith('/merch/staff-access')
  return (
    item.section === active &&
    !(item.section === 'merch_tasks' && pathname.startsWith('/merch/staff-access')) &&
    !(active === 'dashboard' && pathname.startsWith('/dashboard/reports'))
  )
}

export function navigationIcon(item: AdminNavigationItem) {
  if (item.trainingTab)
    return TRAINING_NAVIGATION.find((tab) => tab.value === item.trainingTab)?.Icon
  return SECTIONS.find((entry) => entry.id === item.section)?.Icon
}
