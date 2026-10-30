// Тесты SettingsPage — профиль (read-only), подтверждение выхода, порог авто-сверки.

import { describe, expect, it, beforeEach, vi } from 'vitest'
import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { useUiStore } from '@/app/store'
import type { UserDto } from '@/lib/api-types'
import SettingsPage from './SettingsPage'

const navigate = vi.fn()
vi.mock('react-router-dom', async (orig) => {
  const actual = (await orig()) as Record<string, unknown>
  return { ...actual, useNavigate: () => navigate }
})

const logout = vi.fn()
const setLanguage = vi.fn()

const USER: UserDto = {
  id: 'a1',
  name: 'Дамир Жанибеков',
  email: 'damir@jadran.com',
  role: 'BRAND_MANAGER',
  company: 'Jadran',
}

beforeEach(() => {
  vi.clearAllMocks()
  useUiStore.setState({ authedUser: USER, language: 'ru', logout, setLanguage })
})

function renderPage() {
  return render(
    <MemoryRouter>
      <SettingsPage />
    </MemoryRouter>,
  )
}

describe('SettingsPage', () => {
  it('показывает профиль (имя/компания из authedUser, поля read-only)', () => {
    renderPage()
    const nameInput = screen.getByDisplayValue('Дамир Жанибеков')
    expect(nameInput).toBeDisabled()
    expect(screen.getByDisplayValue('Jadran')).toBeDisabled()
    expect(screen.getByDisplayValue('damir@jadran.com')).toBeDisabled()
  })

  it('выход требует подтверждения: отмена → НЕ выходит', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    const user = userEvent.setup()
    renderPage()
    await user.click(screen.getByRole('button', { name: /Выйти из аккаунта/i }))
    expect(logout).not.toHaveBeenCalled()
    expect(navigate).not.toHaveBeenCalled()
  })

  it('выход с подтверждением → logout + переход на /login', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const user = userEvent.setup()
    renderPage()
    await user.click(screen.getByRole('button', { name: /Выйти из аккаунта/i }))
    expect(logout).toHaveBeenCalledTimes(1)
    expect(navigate).toHaveBeenCalledWith('/login', { replace: true })
  })

  it('не имитирует серверные настройки безопасности и автоодобрения', () => {
    renderPage()
    expect(screen.queryByRole('switch')).not.toBeInTheDocument()
    expect(screen.queryByRole('spinbutton')).not.toBeInTheDocument()
    for (const field of screen.getAllByPlaceholderText('Не настраивается на этой странице')) {
      expect(field).toBeDisabled()
      expect(field).toHaveValue('')
    }
    expect(screen.getByText(/Настройки 2FA и уведомлений не подключены/)).toBeVisible()
    expect(screen.getByText(/Действующие серверные правила эта страница не меняет/)).toBeVisible()
  })

  it('оставляет рабочим выбор языка', async () => {
    const user = userEvent.setup()
    renderPage()
    await user.selectOptions(screen.getByRole('combobox'), 'kk')
    expect(setLanguage).toHaveBeenCalledWith('kk')
  })

  it('обновляет read-only профиль при изменении текущего пользователя', () => {
    renderPage()
    act(() =>
      useUiStore.setState({
        authedUser: { ...USER, name: 'Новое имя', email: 'new@example.test' },
      }),
    )
    expect(screen.getByDisplayValue('Новое имя')).toBeDisabled()
    expect(screen.getByDisplayValue('new@example.test')).toBeDisabled()
    expect(screen.queryByDisplayValue(USER.name)).not.toBeInTheDocument()
  })
})
