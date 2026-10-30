import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, expect, it, vi } from 'vitest'
import { AppRouter } from '@/app/router'
import { useUiStore } from '@/app/store'
import { USERS } from '@/mocks/fixtures'
import MerchPortalPage from './MerchPortalPage'

afterEach(() => {
  useUiStore.setState({ authedUser: null, tokens: null })
  vi.unstubAllGlobals()
})
it('keeps producer on this origin and sends pharmacist and HQ to their canonical entries', () => {
  render(
    <MemoryRouter>
      <MerchPortalPage />
    </MemoryRouter>,
  )
  expect(screen.getByRole('link', { name: 'Кабинет производителя' })).toHaveAttribute(
    'href',
    '/producer/login',
  )
  expect(screen.getByRole('link', { name: 'Личный кабинет фармацевта' })).toHaveAttribute(
    'href',
    'https://epharm.inkar.kz/learn',
  )
  expect(screen.queryByText(/Фотоотчёты по коду доступа/)).not.toBeInTheDocument()
  expect(screen.getByRole('link', { name: 'Вход HQ' })).toHaveAttribute(
    'href',
    'https://epharm.inkar.kz/login',
  )
  expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
  expect(screen.getByRole('heading', { name: 'Нужно выполнить конкретное задание?' })).toBeVisible()
})
it('opens the public portal directly without choosing a role or calling an API for a signed-in administrator', async () => {
  useUiStore.setState({ authedUser: USERS.bauyrzhan })
  const fetcher = vi.fn()
  vi.stubGlobal('fetch', fetcher)
  render(
    <MemoryRouter initialEntries={['/merch/portal']}>
      <AppRouter />
    </MemoryRouter>,
  )
  expect(
    await screen.findByRole('heading', { name: 'Выберите рабочую область' }),
  ).toBeInTheDocument()
  expect(
    screen.queryByRole('complementary', { name: 'Основная навигация' }),
  ).not.toBeInTheDocument()
  expect(fetcher).not.toHaveBeenCalled()
})
