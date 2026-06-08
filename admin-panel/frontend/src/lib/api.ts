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

const BASE_URL =
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

// Refresh-state — нужен чтобы параллельные 401-запросы не запускали refresh параллельно.
// Все ждут одного промиса.
let refreshInFlight: Promise<AuthTokens | null> | null = null

// Текущие токены — нам нужны для interceptor'а вне React-tree. Store обновляет это поле
// через setTokens() при каждом login/logout/refresh.
let currentTokens: AuthTokens | null = null
export const setTokens = (tokens: AuthTokens | null): void => {
  currentTokens = tokens
}
export const getTokens = (): AuthTokens | null => currentTokens

export const api: AxiosInstance = axios.create({
  baseURL: BASE_URL,
  timeout: 15_000,
})

api.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  if (currentTokens?.accessToken) {
    config.headers.set('Authorization', `Bearer ${currentTokens.accessToken}`)
  }
  return config
})

// Маркер чтобы не уйти в бесконечный цикл retry.
interface RetryableConfig extends AxiosRequestConfig {
  _retried?: boolean
}

api.interceptors.response.use(
  (resp) => resp,
  async (error: AxiosError<ApiErrorResponse>) => {
    const original = error.config as RetryableConfig | undefined
    const status = error.response?.status

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
      try {
        const tokens = await ensureRefreshing(currentTokens.refreshToken)
        if (tokens) {
          original.headers = original.headers ?? {}
          ;(original.headers as Record<string, string>)['Authorization'] =
            `Bearer ${tokens.accessToken}`
          return api.request(original)
        }
      } catch {
        // fallthrough to logout
      }
      fireLogout()
    }

    return Promise.reject(error)
  },
)

async function ensureRefreshing(refreshToken: string): Promise<AuthTokens | null> {
  if (!refreshInFlight) {
    refreshInFlight = (async () => {
      try {
        const { data } = await api.post<RefreshResponse>('/api/admin/auth/refresh', {
          refreshToken,
        })
        currentTokens = data.tokens
        return data.tokens
      } catch {
        currentTokens = null
        return null
      } finally {
        refreshInFlight = null
      }
    })()
  }
  return refreshInFlight
}

// ── Auth API клиент-функции ─────────────────────────────────────────────

export const authApi = {
  login: (email: string, password: string) =>
    api.post<LoginResponse>('/api/admin/auth/login', { email, password }).then((r) => r.data),

  logout: () => api.post<void>('/api/admin/auth/logout').then(() => undefined),

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
