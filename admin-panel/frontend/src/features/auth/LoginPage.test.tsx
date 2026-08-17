// Тесты LoginPage — рендер формы, валидация, успешный + неуспешный submit.
// Мокаем @/lib/api напрямую (vi.mock), чтобы не поднимать MSW для unit-теста.

import { describe, expect, it, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import LoginPage from './LoginPage'
import { useUiStore } from '@/app/store'
import { USERS } from '@/mocks/fixtures'
import type { LoginResponse } from '@/lib/api-types'

const authApi = vi.hoisted(() => ({
  login: vi.fn(),
  logout: vi.fn(),
  me: vi.fn(),
}))

vi.mock('@/lib/api', () => ({
  authApi,
  setTokens: vi.fn(),
  onForcedLogout: vi.fn(),
  errorCodeOf: (err: unknown) => {
    if (err && typeof err === 'object' && 'code' in err) return (err as { code: string }).code
    return null
  },
}))

const mockLoginResponse: LoginResponse = {
  tokens: {
    accessToken: 'access-token-xyz',
    accessTokenExpiresAt: '2099-01-01T00:00:00Z',
    refreshToken: 'refresh-token-xyz',
    refreshTokenExpiresAt: '2099-01-01T00:00:00Z',
  },
  user: USERS.damir,
}

beforeEach(() => {
  authApi.login.mockReset()
  authApi.logout.mockReset()
  useUiStore.setState({ authedUser: null, tokens: null })
})

function renderLogin() {
  return render(
    <MemoryRouter initialEntries={['/login']}>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/rules" element={<div>RulesPage</div>} />
        <Route path="/lms" element={<div>LearningPage</div>} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('LoginPage — рендер', () => {
  it('показывает заголовок, поля email/пароль и кнопку «Войти»', () => {
    renderLogin()
    expect(screen.getByText(/Вход для HQ Inkar/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/Email/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/Пароль/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Войти/i })).toBeInTheDocument()
  })

  it('email автофокус при mount', () => {
    renderLogin()
    expect(screen.getByLabelText(/Email/i)).toHaveFocus()
  })

  it('изначально ошибка не показана', () => {
    renderLogin()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('бренд = логотип-глиф + Console (текст Epharm убран); нет PharmaPay', () => {
    const { container } = renderLogin()
    expect(container.querySelector('svg')).toBeInTheDocument() // логотип-глиф
    expect(screen.getByText('Console')).toBeInTheDocument()
    expect(container.innerHTML).not.toMatch(/Epharm/i)
    expect(container.innerHTML).not.toMatch(/PharmaPay/i)
  })
})

describe('LoginPage — валидация на клиенте (без сетевого вызова)', () => {
  it('пустые поля → ошибка без вызова authApi', async () => {
    const user = userEvent.setup()
    renderLogin()
    await user.click(screen.getByRole('button', { name: /Войти/i }))
    expect(screen.getByRole('alert')).toHaveTextContent(/Заполните email и пароль/i)
    expect(authApi.login).not.toHaveBeenCalled()
  })

  it('некорректный email → ошибка без вызова authApi', async () => {
    const user = userEvent.setup()
    renderLogin()
    await user.type(screen.getByLabelText(/Email/i), 'not-an-email')
    await user.type(screen.getByLabelText(/Пароль/i), 'somepass')
    await user.click(screen.getByRole('button', { name: /Войти/i }))
    expect(screen.getByRole('alert')).toHaveTextContent(/Неверный формат email/i)
    expect(authApi.login).not.toHaveBeenCalled()
  })
})

describe('LoginPage — submit (с моком authApi)', () => {
  it('успешный логин → редирект на /rules + authedUser обновлён', async () => {
    authApi.login.mockResolvedValueOnce(mockLoginResponse)
    const user = userEvent.setup()
    renderLogin()
    await user.type(screen.getByLabelText(/Email/i), 'damir@jadran.com')
    await user.type(screen.getByLabelText(/Пароль/i), 'damir2026')
    await user.click(screen.getByRole('button', { name: /Войти/i }))

    await waitFor(() => {
      expect(screen.getByText('RulesPage')).toBeInTheDocument()
    })
    expect(authApi.login).toHaveBeenCalledWith('damir@jadran.com', 'damir2026')
    expect(useUiStore.getState().authedUser?.id).toBe('damir')
  })

  it('руководитель обучения после входа попадает сразу в LMS', async () => {
    authApi.login.mockResolvedValueOnce({ ...mockLoginResponse, user: USERS.lms })
    const user = userEvent.setup()
    renderLogin()
    await user.type(screen.getByLabelText(/Email/i), 'lms@epharm.kz')
    await user.type(screen.getByLabelText(/Пароль/i), 'lms123')
    await user.click(screen.getByRole('button', { name: /Войти/i }))

    await waitFor(() => {
      expect(screen.getByText('LearningPage')).toBeInTheDocument()
    })
    expect(useUiStore.getState().authedUser?.role).toBe('TRAINING_MANAGER')
  })

  it('INVALID_CREDENTIALS → показывает ошибку, authedUser=null', async () => {
    authApi.login.mockRejectedValueOnce({ code: 'INVALID_CREDENTIALS' })
    const user = userEvent.setup()
    renderLogin()
    await user.type(screen.getByLabelText(/Email/i), 'damir@jadran.com')
    await user.type(screen.getByLabelText(/Пароль/i), 'wrong')
    await user.click(screen.getByRole('button', { name: /Войти/i }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/Неверный email или пароль/i)
    })
    expect(useUiStore.getState().authedUser).toBeNull()
  })

  it('NETWORK error → показывает понятное сообщение', async () => {
    authApi.login.mockRejectedValueOnce({ code: 'ERR_NETWORK', message: 'Network Error' })
    const user = userEvent.setup()
    renderLogin()
    await user.type(screen.getByLabelText(/Email/i), 'damir@jadran.com')
    await user.type(screen.getByLabelText(/Пароль/i), 'damir2026')
    await user.click(screen.getByRole('button', { name: /Войти/i }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/Сервер недоступен/i)
    })
  })

  it('пока запрос летит — кнопка disabled и текст «Входим…»', async () => {
    // Promise, который никогда не резолвится — submitting state виден
    authApi.login.mockReturnValueOnce(new Promise(() => {}))
    const user = userEvent.setup()
    renderLogin()
    await user.type(screen.getByLabelText(/Email/i), 'damir@jadran.com')
    await user.type(screen.getByLabelText(/Пароль/i), 'damir2026')
    await user.click(screen.getByRole('button', { name: /Войти/i }))
    expect(screen.getByRole('button', { name: /Входим…/ })).toBeDisabled()
  })
})
