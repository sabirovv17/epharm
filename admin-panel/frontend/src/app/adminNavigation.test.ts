import { describe, expect, it } from 'vitest'
import { adminNavigationForRole, activeNavigationItem } from './adminNavigation'
import { SECTION_ROUTES } from './routes'
import { sectionIdsForRole } from './accessPolicy'

describe('unified navigation preserves route identities and separate boundaries', () => {
  it('keeps every authorized legacy section and all training tabs', () => {
    const items = adminNavigationForRole('HQ_HEAD').flatMap((module) => module.items)
    for (const id of sectionIdsForRole('HQ_HEAD').filter((id) => id !== 'lms'))
      expect(items.some((item) => item.path === SECTION_ROUTES[id])).toBe(true)
    expect(items.filter((item) => item.trainingTab)).toHaveLength(10)
    expect(items.some((item) => item.path === '/dashboard/reports')).toBe(true)
    expect(items.some((item) => item.path?.startsWith('/learn'))).toBe(false)
  })
  it('never grants the new scope from a role or empty grants', () => {
    for (const role of ['HQ_HEAD', 'SYSTEM_ADMIN', 'REGIONAL_MANAGER'] as const) {
      expect(
        adminNavigationForRole(role)
          .flatMap((module) => module.items)
          .some((item) => item.section === 'merch_inventory'),
      ).toBe(false)
      expect(sectionIdsForRole(role)).not.toContain('merch_inventory')
    }
    const regional = adminNavigationForRole('REGIONAL_MANAGER', true)
    expect(regional.map((module) => module.id)).toEqual(['training', 'merch'])
    expect(
      regional.flatMap((module) => module.items).some((item) => item.section === 'finance'),
    ).toBe(false)
  })
  it('enables placements independently from inventory for an explicitly authorized regional manager', () => {
    const merch = adminNavigationForRole('REGIONAL_MANAGER', { placements: true }).find(
      (module) => module.id === 'merch',
    )!
    expect(merch.items.filter((item) => item.available).map((item) => item.path)).toEqual([
      '/merch/placements',
    ])
    expect(merch.items.some((item) => item.section === 'merch_inventory')).toBe(false)
  })
  it('distinguishes campaign reports from dashboard and each LMS query tab', () => {
    const items = adminNavigationForRole('HQ_HEAD').flatMap((module) => module.items)
    expect(
      activeNavigationItem(
        items.find((item) => item.id === 'dashboard')!,
        'dashboard',
        'overview',
        '/dashboard/reports',
      ),
    ).toBe(false)
    expect(
      activeNavigationItem(
        items.find((item) => item.id === 'campaign_reports')!,
        'dashboard',
        'overview',
        '/dashboard/reports',
      ),
    ).toBe(true)
    expect(
      items
        .filter((item) => activeNavigationItem(item, 'lms', 'results', '/lms'))
        .map((item) => item.id),
    ).toEqual(['training_results'])
  })
})
