// E2E: локализация — переключение языка ru↔kk через Settings.
// Проверяем, что меняется навигация, заголовок секции и что выбор персистится.
//
// Селектор языка: на /settings ровно 2 <select> — [0] часовой пояс, [1] язык.
// Берём .locator('select').nth(1), чтобы не зависеть от меняющейся подписи (Язык/Тіл).

import { test, expect } from './fixtures'

test.describe('i18n — переключение языка ru ↔ kk', () => {
  test('смена на Қазақша переводит заголовок и навигацию', async ({ loggedInPage }) => {
    const page = loggedInPage
    await page.goto('/settings')
    await expect(page.getByRole('heading', { level: 1, name: 'Настройки' })).toBeVisible()

    await page.locator('select').nth(1).selectOption('kk')

    await expect(page.getByRole('heading', { level: 1, name: 'Баптаулар' })).toBeVisible()
    await expect(page.getByText('Аналитика дашборды')).toBeVisible() // sidebar nav
    await expect(page.getByRole('heading', { level: 1, name: 'Настройки' })).toHaveCount(0)
  })

  test('переключение назад на Русский', async ({ loggedInPage }) => {
    const page = loggedInPage
    await page.goto('/settings')
    await page.locator('select').nth(1).selectOption('kk')
    await expect(page.getByRole('heading', { level: 1, name: 'Баптаулар' })).toBeVisible()
    await page.locator('select').nth(1).selectOption('ru')
    await expect(page.getByRole('heading', { level: 1, name: 'Настройки' })).toBeVisible()
  })

  test('язык сохраняется после перезагрузки', async ({ loggedInPage }) => {
    const page = loggedInPage
    await page.goto('/settings')
    await page.locator('select').nth(1).selectOption('kk')
    await expect(page.getByRole('heading', { level: 1, name: 'Баптаулар' })).toBeVisible()

    await page.reload()
    await expect(page.getByRole('heading', { level: 1, name: 'Баптаулар' })).toBeVisible()
  })

  test('казахский язык виден на других секциях (Промо)', async ({ loggedInPage }) => {
    const page = loggedInPage
    await page.goto('/settings')
    await page.locator('select').nth(1).selectOption('kk')
    await expect(page.getByRole('heading', { level: 1, name: 'Баптаулар' })).toBeVisible()

    await page.getByText('Промо-науқандар').first().click()
    await expect(page).toHaveURL(/\/promo$/)
    await expect(page.getByRole('heading', { level: 1, name: 'Промо-науқандар' })).toBeVisible()
  })
})
