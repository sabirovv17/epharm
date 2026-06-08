// Tests for Zustand auth store — async login flow, logout, persistence.
// Мокаем `@/lib/api` через vi.mock — Vitest подменит модуль до загрузки store.

import { describe, expect, it, beforeEach, vi } from 'vitest'
import { useUiStore } from './store'
import { USERS } from '@/mocks/fixtures'
import type { LoginResponse } from '@/lib/api-types'

// ─── Mocks ───────────────────────────────────────────────────────────────

const authApi = vi.hoisted(() => ({
  login: vi.fn(),
  logout: vi.fn(),
  me: vi.fn(),
}))

const setApiTokens = vi.hoisted(() => vi.fn())
const onForcedLogout = vi.hoisted(() => vi.fn())

vi.mock('@/lib/api', () => ({
  authApi,
  setTokens: setApiTokens,
  onForcedLogout,
  errorCodeOf: (err: unknown) => {
    if (err && typeof err === 'object' && 'code' in err) return (err as { code: string }).code
    return null
  },
}))

// ─── Helpers ─────────────────────────────────────────────────────────────

const mockLoginResponse: LoginResponse = {
  tokens: {
    accessToken: 'access-token-xyz',
    accessTokenExpiresAt: '2099-01-01T00:00:00Z',
    refreshToken: 'refresh-token-xyz',
    refreshTokenExpiresAt: '2099-01-01T00:00:00Z',
  },
  user: USERS.damir,
}

describe('useUiStore — auth (async)', () => {
  beforeEach(() => {
    useUiStore.setState({ authedUser: null, tokens: null })
    authApi.login.mockReset()
    authApi.logout.mockReset()
    setApiTokens.mockReset()
    if (typeof localStorage !== 'undefined') localStorage.clear()
  })

  it('изначально authedUser = null', () => {
    expect(useUiStore.getState().authedUser).toBeNull()
    expect(useUiStore.getState().tokens).toBeNull()
  })

  it('login успешный → возвращает {ok:true}, устанавливает authedUser + tokens', async () => {
    authApi.login.mockResolvedValueOnce(mockLoginResponse)
    const result = await useUiStore.getState().login('damir@jadran.com', 'damir2026')
    expect(result).toEqual({ ok: true })
    expect(useUiStore.getState().authedUser).toEqual(USERS.damir)
    expect(useUiStore.getState().tokens?.accessToken).toBe('access-token-xyz')
    expect(setApiTokens).toHaveBeenCalledWith(mockLoginResponse.tokens)
  })

  it('login провал с INVALID_CREDENTIALS → возвращает code, не меняет state', async () => {
    authApi.login.mockRejectedValueOnce({ code: 'INVALID_CREDENTIALS' })
    const result = await useUiStore.getState().login('damir@jadran.com', 'wrong')
    expect(result).toEqual({ ok: false, code: 'INVALID_CREDENTIALS' })
    expect(useUiStore.getState().authedUser).toBeNull()
  })

  it('login network error → возвращает code=NETWORK', async () => {
    authApi.login.mockRejectedValueOnce({ message: 'Network Error', code: 'ERR_NETWORK' })
    const result = await useUiStore.getState().login('damir@jadran.com', 'x')
    expect(result.ok).toBe(false)
    if (!result.ok) expect(result.code).toBe('NETWORK')
  })

  it('logout вызывает backend logout, очищает state и закрывает модалки', async () => {
    useUiStore.setState({
      authedUser: USERS.damir,
      tokens: mockLoginResponse.tokens,
      commandPaletteOpen: true,
      roleSwitcherOpen: true,
      contractModalOpen: true,
    })
    authApi.logout.mockResolvedValueOnce(undefined)
    await useUiStore.getState().logout()
    expect(authApi.logout).toHaveBeenCalled()
    expect(useUiStore.getState().authedUser).toBeNull()
    expect(useUiStore.getState().tokens).toBeNull()
    expect(useUiStore.getState().commandPaletteOpen).toBe(false)
    expect(useUiStore.getState().roleSwitcherOpen).toBe(false)
    expect(useUiStore.getState().contractModalOpen).toBe(false)
  })

  it('logout без токенов — backend не вызывается, state очищается', async () => {
    useUiStore.setState({ authedUser: null, tokens: null })
    await useUiStore.getState().logout()
    expect(authApi.logout).not.toHaveBeenCalled()
    expect(useUiStore.getState().authedUser).toBeNull()
  })

  it('setActiveRole меняет authedUser', () => {
    useUiStore.setState({ authedUser: USERS.damir })
    useUiStore.getState().setActiveRole(USERS.aigerim)
    expect(useUiStore.getState().authedUser).toEqual(USERS.aigerim)
  })
})

describe('useUiStore — UI flags', () => {
  beforeEach(() => {
    useUiStore.setState({
      sidebarCollapsed: false,
      commandPaletteOpen: false,
    })
  })

  it('toggleSidebar переключает collapsed', () => {
    expect(useUiStore.getState().sidebarCollapsed).toBe(false)
    useUiStore.getState().toggleSidebar()
    expect(useUiStore.getState().sidebarCollapsed).toBe(true)
    useUiStore.getState().toggleSidebar()
    expect(useUiStore.getState().sidebarCollapsed).toBe(false)
  })

  it('open/close командной палитры', () => {
    useUiStore.getState().openCommandPalette()
    expect(useUiStore.getState().commandPaletteOpen).toBe(true)
    useUiStore.getState().closeCommandPalette()
    expect(useUiStore.getState().commandPaletteOpen).toBe(false)
  })
})
