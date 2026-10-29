import { expect, test } from '@playwright/test'

test('native merch and education export buttons use independent capabilities and only the actual applied filters', async ({
  page,
  baseURL,
}) => {
  const blocked: string[] = []
  const exports: { path: string; params: Record<string, string> }[] = []
  const branch = {
    id: 'branch-demo',
    name: 'Демо-аптека',
    city: 'Демо-город',
    address: 'Демо-адрес, 1',
    organizationName: 'Демо-сеть',
    active: true,
  }
  await page.addInitScript(() => {
    localStorage.setItem(
      'epharm.auth.user',
      JSON.stringify({
        id: 'owner-demo',
        name: 'Демо HQ',
        email: 'demo@example.test',
        role: 'HQ_HEAD',
        company: 'DEMO',
      }),
    )
    localStorage.setItem(
      'epharm.auth.tokens',
      JSON.stringify({
        accessToken: 'synthetic-admin',
        refreshToken: 'synthetic-refresh',
        accessTokenExpiresAt: '2099-01-01T00:00:00Z',
        refreshTokenExpiresAt: '2099-01-02T00:00:00Z',
      }),
    )
  })
  await page.route('**/*', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    if (url.origin !== new URL(baseURL!).origin) {
      blocked.push(path)
      return route.abort()
    }
    if (!path.startsWith('/api/')) return route.continue()
    expect(request.method()).toBe('GET')
    expect(request.headers().authorization).toBe('Bearer synthetic-admin')
    if (path === '/api/admin/access/me')
      return route.fulfill({
        json: {
          managed: true,
          roleCeiling: 'HQ_HEAD',
          version: 1,
          grants: [],
          canViewAccounts: false,
          canManageAccounts: false,
          canViewMerchInventory: true,
          canViewMerchPlacements: true,
          canViewMerchTasks: true,
          canExportMerchInventory: true,
          canExportMerchPlacements: true,
          canExportMerchTasks: true,
          canExportTrainingCourses: true,
          canExportTrainingPrograms: true,
        },
      })
    if (path.endsWith('.xlsx')) {
      exports.push({ path, params: Object.fromEntries(url.searchParams) })
      return route.fulfill({
        contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        body: 'SYNTHETIC-XLSX-TRANSPORT-ONLY',
      })
    }
    if (path === '/api/admin/merch/branches')
      return route.fulfill({ json: { items: [branch], total: 1, limit: 50, offset: 0 } })
    if (path.endsWith('/inventory'))
      return route.fulfill({ json: { branch, containers: [], shelves: [] } })
    if (path.endsWith('/availability'))
      return route.fulfill({
        json: {
          branch,
          startMonth: url.searchParams.get('startMonth'),
          monthCount: 6,
          months: [],
          shelves: [],
          canManage: false,
          canViewPrices: false,
        },
      })
    if (path.endsWith('/placements/producers'))
      return route.fulfill({
        json: [{ id: 'producer-demo', name: 'Демо Производитель', active: true }],
      })
    if (path.endsWith('/placements'))
      return route.fulfill({ json: { items: [], total: 0, limit: 50, offset: 0 } })
    if (path.endsWith('/tasks/branches')) return route.fulfill({ json: [] })
    if (path.endsWith('/merch/tasks'))
      return route.fulfill({ json: { items: [], limit: 20, offset: 0, hasMore: false } })
    if (path === '/api/admin/training/dashboard')
      return route.fulfill({
        json: {
          capabilities: {},
          activePrograms: 0,
          totalAssignments: 0,
          notStarted: 0,
          inProgress: 0,
          completed: 0,
          overdue: 0,
        },
      })
    if (
      [
        '/api/admin/lms/courses',
        '/api/admin/training/programs',
        '/api/admin/training/assignments',
        '/api/admin/training/events',
        '/api/admin/training/certificates',
        '/api/admin/training/pharmacists',
        '/api/admin/pharmacies',
      ].includes(path)
    )
      return route.fulfill({ json: [] })
    blocked.push(path)
    return route.abort()
  })
  const download = async (label: string) => {
    await Promise.all([
      page.waitForEvent('download'),
      page.getByRole('button', { name: label, exact: true }).click(),
    ])
  }
  await page.goto('/merch/inventory')
  await expect(page.getByRole('button', { name: 'Excel: доступность' })).toBeDisabled()
  await page.getByRole('combobox', { name: 'Аптека мерча' }).selectOption(branch.id)
  await page.getByLabel('Начало календаря').fill('2026-11')
  await expect(page.getByRole('button', { name: 'Excel: доступность' })).toBeEnabled()
  await download('Excel: доступность')
  await page.goto('/merch/placements')
  await page.getByRole('combobox', { name: 'Фильтр производителя' }).selectOption('producer-demo')
  await page.getByRole('combobox', { name: 'Статус медиаплана' }).selectOption('pending')
  await download('Excel: медиапланы')
  await page.goto('/merch/tasks')
  await download('Excel: задания')
  await page.goto('/lms?tab=courses')
  await download('Excel: курсы')
  await page.goto('/lms?tab=programs')
  await download('Excel: программы')
  expect(exports).toEqual([
    {
      path: '/api/admin/merch/exports/inventory.xlsx',
      params: { branchId: branch.id, startMonth: '2026-11-01', monthCount: '6' },
    },
    {
      path: '/api/admin/merch/exports/placements.xlsx',
      params: { producerId: 'producer-demo', status: 'pending' },
    },
    { path: '/api/admin/merch/exports/tasks.xlsx', params: {} },
    { path: '/api/admin/training/exports/courses.xlsx', params: {} },
    { path: '/api/admin/training/exports/programs.xlsx', params: {} },
  ])
  expect(blocked).toEqual([])
})
