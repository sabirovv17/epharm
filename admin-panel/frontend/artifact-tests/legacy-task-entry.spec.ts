import { expect, test } from '@playwright/test'

const token = 'synthetic_legacy_task_capability_0000000000000000000000'
for (const entry of [`/merch/staff?task=${token}`, `/staff?task=1#task=${token}`]) {
  test(`legacy QR enters the built native free-task portal: ${entry.split('?')[0]}`, async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 900 })
    const requests: string[] = []
    await page.route('**/api/**', async (route) => {
      const request = route.request()
      const path = new URL(request.url()).pathname
      requests.push(path)
      expect(new URL(page.url()).pathname).toBe('/tasks/staff')
      expect(page.url()).not.toContain(token)
      expect(request.headers()['x-merch-task-token']).toBe(token)
      expect(request.headers().authorization).toBeUndefined()
      expect(request.headers().referer).toBeUndefined()
      if (path === '/api/public/merch/tasks/session')
        return route.fulfill({ json: { dispatchId: 'synthetic-task', branchId: 'synthetic-branch',
          expiresAt: '2099-01-01T00:00:00Z', readOnly: false, rewardAmountKzt: 0, identityRequired: false } })
      expect(path).toBe('/api/public/merch/tasks')
      return route.fulfill({ json: { id: 'synthetic-task', branchId: 'synthetic-branch', title: 'Сохранённое задание аптеки',
        status: 'ready', dueAt: null, sourceSystem: 'MERCH_CRM', version: 1, rewardAmountKzt: 0,
        reward: null, items: [], itemCount: 0, detailsIncluded: true, priority: 'normal', taskType: 'placement' } })
    })
    await page.goto(entry)
    await expect(page.getByRole('heading', { name: 'Сохранённое задание аптеки' })).toBeVisible()
    expect(requests).toEqual(['/api/public/merch/tasks/session', '/api/public/merch/tasks'])
    expect(await page.evaluate(() => ({ local: localStorage.length, session: sessionStorage.length,
      overflow: document.documentElement.scrollWidth > window.innerWidth }))).toEqual({ local: 0, session: 0, overflow: false })
    await page.screenshot({ path: `test-results/artifact/legacy-task-${entry.startsWith('/merch') ? 'merch' : 'staff'}-390.png`, fullPage: true })
  })
}
