import { expect, test, type Page } from '@playwright/test'
import { ACCOUNTS, assertBackendUp, resetBackend } from './fixtures'

async function login(page: Page, account: (typeof ACCOUNTS)[keyof typeof ACCOUNTS]) {
  await page.goto('/login')
  await page.getByLabel(/Email/i).fill(account.email)
  await page.getByLabel(/Пароль/i).fill(account.password)
  await page.getByRole('button', { name: /^Войти/i }).click()
  await page.waitForURL(/\/(dashboard|rules|lms)(\?|$)/)
}

test.beforeEach(async ({ request, page }) => {
  await assertBackendUp(request)
  await resetBackend(request)
  await page.goto('/')
  await page.evaluate(() => {
    localStorage.clear()
    sessionStorage.clear()
  })
})

test('HQ shell uses the restrained shared visual system', async ({ page }) => {
  await login(page, ACCOUNTS.bauyrzhan)

  const sidebar = page.locator('aside')
  const card = page.locator('main .card').first()
  const search = page.getByRole('button', { name: /Найти правило/i })

  await expect(sidebar).toHaveCSS('background-image', 'none')
  await expect(sidebar).toHaveCSS('background-color', 'rgb(28, 27, 25)')
  await expect(card).toHaveCSS('border-radius', '12px')
  await expect(search).toHaveCSS('border-radius', '8px')
  await expect(search).toHaveCSS('border-top-style', 'solid')
})

test('Learning admin inherits the same system and keeps its navigation', async ({ page }) => {
  await login(page, ACCOUNTS.lms)
  await expect(page).toHaveURL(/\/lms(?:\?|$)/)

  const courses = page.locator('[data-training-tab="courses"]')
  await expect(courses).toBeVisible()
  await courses.click()
  await expect(page).toHaveURL(/\/lms\?tab=courses/)
  await expect(page.getByRole('heading', { name: 'Онлайн-курсы' })).toBeVisible()

  const primary = page.getByRole('button', { name: 'Новый курс' })
  await expect(primary).toHaveCSS('background-color', 'rgb(185, 83, 54)')
  await expect(primary).toHaveCSS('border-radius', '8px')
  await expect(page.locator('aside')).toHaveCSS('background-image', 'none')
})
