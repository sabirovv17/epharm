import type { Page } from '@playwright/test'

import { ACCOUNTS, expect, test, type DevAccount } from './fixtures'

async function loginForRole(page: Page, account: DevAccount, landingPath: string): Promise<void> {
  await page.goto('/login')
  await page.getByLabel(/Email/i).fill(account.email)
  await page.getByLabel(/Пароль/i).fill(account.password)
  await page.getByRole('button', { name: /^Войти/i }).click()
  await page.waitForURL(`**${landingPath}`, { timeout: 10_000 })
}

test.describe('Role access — обучение и AI-экзамены', () => {
  test('HQ видит все разделы, а обучение открывается только для просмотра', async ({
    freshPage,
  }) => {
    await loginForRole(freshPage, ACCOUNTS.bauyrzhan, '/rules')

    await expect(freshPage.getByRole('button', { name: /Дашборд аналитики/i })).toBeVisible()
    await expect(freshPage.getByRole('button', { name: /^Обучение/i })).toBeVisible()
    await expect(freshPage.getByRole('button', { name: /AI-Экзаменация/i })).toBeVisible()

    await freshPage.getByRole('button', { name: /^Обучение/i }).click()
    await expect(freshPage).toHaveURL(/\/lms$/)
    await expect(freshPage.getByTestId('training-read-only')).toHaveText('Только просмотр')
    await expect(freshPage.getByRole('button', { name: 'Программы' })).toBeVisible()
    await freshPage.getByRole('button', { name: 'Программы' }).click()
    await expect(freshPage.getByRole('button', { name: 'Новая программа' })).toHaveCount(0)

    await freshPage.getByRole('button', { name: /AI-Экзаменация/i }).click()
    await expect(freshPage).toHaveURL(/\/ai-exam$/)
    await expect(freshPage.getByTestId('ai-exam-read-only')).toHaveText('Только просмотр')
    await expect(freshPage.getByRole('button', { name: /Новый вопрос/i })).toHaveCount(0)
  })

  test('руководитель обучения изолирован от HQ и сохраняет управляющие действия', async ({
    freshPage,
  }) => {
    await loginForRole(freshPage, ACCOUNTS.lms, '/lms')

    await expect(freshPage.getByText(ACCOUNTS.lms.name)).toBeVisible()
    await expect(freshPage.getByRole('button', { name: /^Обучение/i })).toBeVisible()
    await expect(freshPage.getByRole('button', { name: /AI-Экзаменация/i })).toBeVisible()
    await expect(freshPage.getByRole('button', { name: /Дашборд аналитики/i })).toHaveCount(0)
    await expect(freshPage.getByRole('button', { name: /Rules Engine/i })).toHaveCount(0)

    await freshPage.getByRole('button', { name: 'Программы' }).click()
    await expect(freshPage.getByRole('button', { name: 'Новая программа' })).toBeVisible()

    await freshPage.getByRole('button', { name: /AI-Экзаменация/i }).click()
    await expect(freshPage).toHaveURL(/\/ai-exam$/)
    await expect(freshPage.getByRole('button', { name: /Новый вопрос/i }).first()).toBeVisible()

    await freshPage.goto('/dashboard')
    await expect(freshPage).toHaveURL(/\/lms$/)
  })
})
