// REPRODUCTION-тест для Bug J — Cmd+R разлогинивает.
//
// Сценарий бага:
//   1. Юзер логинится → token + user в localStorage.
//   2. Юзер обновляет страницу (Cmd+R).
//   3. React монтирует App. useUiStore.getState().authedUser = null (initial).
//   4. RequireAuth видит authedUser=null → редиректит на /login через <Navigate>.
//   5. useEffect в App.tsx вызывает init() — но уже поздно, навигация произошла.
//   6. Юзер на /login, хотя токены валидны в localStorage.
//
// Корень: hydration происходит в useEffect (асинхронно, после первого рендера),
// а должен быть в initial state factory (синхронно, до первого рендера).
//
// Тест: мокаем tokenStore, проверяем что initial state считывается без useEffect.

import { describe, expect, it, beforeEach, vi } from 'vitest'
import type { LoginResponse } from '@/lib/api-types'
import { USERS } from '@/mocks/fixtures'

vi.mock('@/lib/api', () => ({
  authApi: { login: vi.fn(), logout: vi.fn(), me: vi.fn() },
  setTokens: vi.fn(),
  onForcedLogout: vi.fn(),
  errorCodeOf: () => null,
}))

const tokenStoreMock = vi.hoisted(() => ({
  load: vi.fn(),
  save: vi.fn(),
  clear: vi.fn(),
}))

vi.mock('@/lib/tokenStore', () => ({
  tokenStore: tokenStoreMock,
}))

const mockAuth: LoginResponse = {
  tokens: {
    accessToken: 'access-xyz',
    accessTokenExpiresAt: '2099-01-01T00:00:00Z',
    refreshToken: 'refresh-xyz',
    refreshTokenExpiresAt: '2099-01-01T00:00:00Z',
  },
  user: USERS.damir,
}

describe('Store hydration — synchronous from localStorage (REPRO Bug J)', () => {
  beforeEach(() => {
    vi.resetModules()
    tokenStoreMock.load.mockReset()
    tokenStoreMock.save.mockReset()
    tokenStoreMock.clear.mockReset()
  })

  it('пустой tokenStore → initial authedUser=null', async () => {
    tokenStoreMock.load.mockReturnValue(null)
    const { useUiStore } = await import('./store')
    expect(useUiStore.getState().authedUser).toBeNull()
    expect(useUiStore.getState().tokens).toBeNull()
  })

  it('REPRO: tokenStore возвращает persisted → initial state СИНХРОННО гидрирован', async () => {
    // tokenStore.load() возвращает сохранённое — имитация: юзер залогинен, Cmd+R.
    tokenStoreMock.load.mockReturnValue({
      tokens: mockAuth.tokens,
      user: mockAuth.user,
    })

    // Свежий импорт store (как новая загрузка приложения после reload).
    const { useUiStore } = await import('./store')

    // ВАЖНО: НЕ вызываем init() и не ждём useEffect.
    // Если падает — RequireAuth на первом рендере увидит null → редирект на /login.
    expect(useUiStore.getState().authedUser).toEqual(mockAuth.user)
    expect(useUiStore.getState().tokens).toEqual(mockAuth.tokens)
  })

  it('tokenStore.load выбрасывает (corrupted storage) → graceful null без падения', async () => {
    tokenStoreMock.load.mockImplementation(() => {
      throw new Error('Quota exceeded')
    })
    const { useUiStore } = await import('./store')
    expect(useUiStore.getState().authedUser).toBeNull()
    expect(useUiStore.getState().tokens).toBeNull()
  })

  it('REPRO: после гидрации setTokens вызван с tokens (axios-interceptor увидит Bearer)', async () => {
    tokenStoreMock.load.mockReturnValue({
      tokens: mockAuth.tokens,
      user: mockAuth.user,
    })
    const api = await import('@/lib/api')
    await import('./store')
    // Если падает — axios-клиент не знает про token, запросы пойдут без Authorization.
    expect(api.setTokens).toHaveBeenCalledWith(mockAuth.tokens)
  })
})
