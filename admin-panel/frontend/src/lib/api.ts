// Единый axios-клиент к Spring-backend'у.
//
// Поведение:
//   - Request interceptor: подкладывает Authorization: Bearer <accessToken> если есть.
//   - Response interceptor: на 401 пробует /refresh — если успех, retry оригинала; иначе logout.
//   - Раскрывает доменные ошибки (ApiErrorResponse → axios error.response.data).
//
// Использование:
//   import { api } from '@/lib/api'
//   const { data } = await api.post<LoginResponse>('/admin/auth/login', { email, password })

import axios, {
  type AxiosError,
  type AxiosInstance,
  type AxiosRequestConfig,
  type InternalAxiosRequestConfig,
} from 'axios'
import type {
  ApiErrorResponse,
  AuthTokens,
  LoginResponse,
  RefreshResponse,
  UserDto,
} from './api-types'

// Базовый URL бэкенда. В проде пуст ('') → same-origin (Caddy проксирует /api/*),
// в dev — localhost:8080. Экспортируем: image-прокси (proxyMedia) строит абсолютный
// src от того же базового URL, что и axios, чтобы картинки били в тот же бэкенд.
export const BASE_URL =
  (import.meta.env.VITE_API_BASE_URL as string | undefined) ?? 'http://localhost:8080'

// Подписки на logout — store подписывается, чтобы синхронно очистить authedUser при провале refresh.
type LogoutHandler = () => void
const logoutHandlers: LogoutHandler[] = []
export const onForcedLogout = (handler: LogoutHandler): (() => void) => {
  logoutHandlers.push(handler)
  return () => {
    const idx = logoutHandlers.indexOf(handler)
    if (idx >= 0) logoutHandlers.splice(idx, 1)
  }
}
const fireLogout = (): void => {
  logoutHandlers.forEach((h) => h())
}
const tokenRefreshHandlers: Array<(tokens: AuthTokens) => void> = []
export const onTokensRefreshed = (handler: (tokens: AuthTokens) => void): (() => void) => {
  tokenRefreshHandlers.push(handler)
  return () => {
    const index = tokenRefreshHandlers.indexOf(handler)
    if (index >= 0) tokenRefreshHandlers.splice(index, 1)
  }
}

// Refresh-state — нужен чтобы параллельные 401-запросы не запускали refresh параллельно.
// Все ждут одного промиса.
let refreshInFlight: { epoch: number; promise?: Promise<AuthTokens | null> } | null = null
let adminSessionEpoch = 0

// Текущие токены — нам нужны для interceptor'а вне React-tree. Store обновляет это поле
// setTokens changes the session; same-session refresh rotates tokens without advancing its epoch.
let currentTokens: AuthTokens | null = null
export const setTokens = (tokens: AuthTokens | null): void => {
  adminSessionEpoch++
  refreshInFlight = null
  currentTokens = tokens
}
export const getTokens = (): AuthTokens | null => currentTokens

export const api: AxiosInstance = axios.create({
  baseURL: BASE_URL,
  timeout: 15_000,
})

api.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    const request = config as SessionConfig
    if (
      request._adminSessionEpoch !== undefined &&
      request._adminSessionEpoch !== adminSessionEpoch
    ) {
      throw new axios.CanceledError('Сессия изменилась.')
    }
    request._adminSessionEpoch = adminSessionEpoch
    if (currentTokens?.accessToken) {
      config.headers.set('Authorization', `Bearer ${currentTokens.accessToken}`)
    }
    return config
  },
  (error) => {
    throw error
  },
  { synchronous: true },
)

// Маркер чтобы не уйти в бесконечный цикл retry.
interface RetryableConfig extends AxiosRequestConfig {
  _retried?: boolean
  _adminSessionEpoch?: number
}
interface SessionConfig extends InternalAxiosRequestConfig {
  _adminSessionEpoch?: number
}

api.interceptors.response.use(
  (resp) => {
    if ((resp.config as SessionConfig)._adminSessionEpoch !== adminSessionEpoch)
      throw new axios.CanceledError('Сессия изменилась.')
    return resp
  },
  async (error: AxiosError<ApiErrorResponse>) => {
    const original = error.config as RetryableConfig | undefined
    const status = error.response?.status
    if (
      original?._adminSessionEpoch !== undefined &&
      original._adminSessionEpoch !== adminSessionEpoch
    ) {
      return Promise.reject(new axios.CanceledError('Сессия изменилась.'))
    }

    // Если 401 и есть refresh — пробуем обновить токены и retry.
    if (
      status === 401 &&
      original &&
      !original._retried &&
      currentTokens?.refreshToken &&
      // Не пробуем refresh-цикл на /refresh самом — он же провалился.
      !original.url?.includes('/admin/auth/refresh') &&
      !original.url?.includes('/admin/auth/login')
    ) {
      original._retried = true
      const epoch = adminSessionEpoch
      try {
        const tokens = await ensureRefreshing(currentTokens.refreshToken, epoch)
        if (epoch !== adminSessionEpoch)
          return Promise.reject(new axios.CanceledError('Сессия изменилась.'))
        if (tokens) {
          original.headers = original.headers ?? {}
          ;(original.headers as Record<string, string>)['Authorization'] =
            `Bearer ${tokens.accessToken}`
          return api.request(original)
        }
      } catch {
        // fallthrough to logout
      }
      if (epoch === adminSessionEpoch) fireLogout()
    }

    return Promise.reject(error)
  },
)

async function ensureRefreshing(refreshToken: string, epoch: number): Promise<AuthTokens | null> {
  if (refreshInFlight?.epoch === epoch && refreshInFlight.promise) return refreshInFlight.promise
  const flight: { epoch: number; promise?: Promise<AuthTokens | null> } = { epoch }
  refreshInFlight = flight
  flight.promise = (async () => {
    try {
      const { data } = await api.post<RefreshResponse>('/api/admin/auth/refresh', {
        refreshToken,
      })
      if (epoch !== adminSessionEpoch) return null
      currentTokens = data.tokens
      tokenRefreshHandlers.forEach((handler) => handler(data.tokens))
      return data.tokens
    } catch {
      if (epoch === adminSessionEpoch) currentTokens = null
      return null
    } finally {
      if (refreshInFlight === flight) refreshInFlight = null
    }
  })()
  return flight.promise
}

// ── Auth API клиент-функции ─────────────────────────────────────────────

export const authApi = {
  login: (email: string, password: string) =>
    api.post<LoginResponse>('/api/admin/auth/login', { email, password }).then((r) => r.data),

  // Capture the outgoing bearer before local logout replaces the session.
  logout: () =>
    axios
      .post<void>(`${BASE_URL}/api/admin/auth/logout`, undefined, {
        timeout: 15_000,
        headers: currentTokens ? { Authorization: `Bearer ${currentTokens.accessToken}` } : {},
      })
      .then(() => undefined),

  me: () => api.get<UserDto>('/api/admin/auth/me').then((r) => r.data),
}

// ── Утилита: достать ApiErrorCode из AxiosError ─────────────────────────

import type { ApiErrorCode } from './api-types'

export function errorCodeOf(err: unknown): ApiErrorCode | null {
  if (axios.isAxiosError<ApiErrorResponse>(err)) {
    return err.response?.data?.code ?? null
  }
  return null
}
