// Global UI state + auth — Zustand single store.
//
// Auth pipeline (Этап 3.1):
//   - login(email, password) → POST /api/admin/auth/login → токены + user
//   - persist в localStorage через tokenStore
//   - hydrate при boot (init())
//   - logout(): POST /api/admin/auth/logout + clear
//   - 401-перехватчик в api.ts вызывает onForcedLogout → у нас тут это слушает init()

import { create } from 'zustand'
import { authApi, errorCodeOf, onForcedLogout, setTokens as setApiTokens } from '@/lib/api'
import type { AuthTokens, UserDto } from '@/lib/api-types'
import { tokenStore } from '@/lib/tokenStore'
import { clearPersistedCache } from './queryPersist'

// Storage для clearPersistedCache — берём localStorage если доступен.
const cacheStorage =
  typeof globalThis !== 'undefined' && 'localStorage' in globalThis
    ? (globalThis as { localStorage: Storage }).localStorage
    : null
const clearQueryCache = () => {
  if (cacheStorage) clearPersistedCache(cacheStorage)
}

/** Отчётный период (месяц + год). month — 0-11, как в Date.getMonth(). */
export interface PeriodValue {
  year: number
  month: number
}

/** Текущий месяц по реальной системной дате. */
export function currentPeriod(): PeriodValue {
  const d = new Date()
  return { year: d.getFullYear(), month: d.getMonth() }
}

export type Lang = 'ru' | 'kk'
const LANG_KEY = 'epharm.lang'

/** Читаем язык из localStorage синхронно (как loadInitialAuth). Дефолт — ru. */
function loadInitialLang(): Lang {
  try {
    const v = cacheStorage?.getItem(LANG_KEY)
    if (v === 'ru' || v === 'kk') {
      if (typeof document !== 'undefined') document.documentElement.lang = v
      return v
    }
  } catch {
    /* ignore */
  }
  return 'ru'
}

interface UiState {
  // Auth
  authedUser: UserDto | null
  tokens: AuthTokens | null

  // UI flags
  sidebarCollapsed: boolean
  commandPaletteOpen: boolean
  roleSwitcherOpen: boolean
  contractModalOpen: boolean

  // Отчётный период (Topbar PeriodPicker) — единая точка для будущей
  // фильтрации данных по месяцу. По умолчанию — текущий месяц.
  period: PeriodValue

  // Язык интерфейса (i18n). Персистится в localStorage, применяется live.
  language: Lang

  // Auth actions
  init: () => void
  login: (email: string, password: string) => Promise<LoginResult>
  logout: () => Promise<void>
  setActiveRole: (user: UserDto) => void

  setPeriod: (p: PeriodValue) => void
  setLanguage: (lang: Lang) => void

  // UI actions
  toggleSidebar: () => void
  openCommandPalette: () => void
  closeCommandPalette: () => void
  openRoleSwitcher: () => void
  closeRoleSwitcher: () => void
  openContractModal: () => void
  closeContractModal: () => void
}

export type LoginResult =
  | { ok: true }
  | { ok: false; code: 'INVALID_CREDENTIALS' | 'NETWORK' | 'UNKNOWN'; message?: string }

// Backward-compat: тесты/код смотрит на MOCK_CREDENTIALS — оставим пустым объектом
// для удаления ссылок без ломки. На самом деле моки больше не используются.
export const MOCK_CREDENTIALS: Record<string, never> = {}

/**
 * Bug J fix: гидрация ДОЛЖНА быть синхронной — иначе RequireAuth на первом рендере
 * видит authedUser=null и редиректит на /login, до того как useEffect успеет
 * прочитать tokenStore. Cmd+R разлогинивал юзера.
 *
 * Решение: читаем persisted state в initial-state factory create()'а. Любое
 * исключение из tokenStore.load() (повреждённый JSON, заблокированный localStorage)
 * → fallback на null без падения.
 */
function loadInitialAuth(): { authedUser: UserDto | null; tokens: AuthTokens | null } {
  try {
    const persisted = tokenStore.load()
    if (!persisted) return { authedUser: null, tokens: null }
    // Сразу прокидываем в axios-interceptor, чтобы первый же запрос ушёл с Bearer.
    setApiTokens(persisted.tokens)
    return { authedUser: persisted.user, tokens: persisted.tokens }
  } catch {
    return { authedUser: null, tokens: null }
  }
}

const initialAuth = loadInitialAuth()

export const useUiStore = create<UiState>((set, get) => ({
  authedUser: initialAuth.authedUser,
  tokens: initialAuth.tokens,
  sidebarCollapsed: false,
  commandPaletteOpen: false,
  roleSwitcherOpen: false,
  contractModalOpen: false,
  period: currentPeriod(),
  language: loadInitialLang(),

  init: () => {
    // Гидрация теперь синхронна (см. loadInitialAuth выше).
    // Здесь остаётся только подписка на forced-logout от axios-interceptor'а
    // (401 при провале refresh) — это side-effect, которому нужен useEffect.
    onForcedLogout(() => {
      tokenStore.clear()
      clearQueryCache()
      setApiTokens(null)
      set({ authedUser: null, tokens: null })
    })
  },

  login: async (email, password) => {
    try {
      const resp = await authApi.login(email, password)
      tokenStore.save({ tokens: resp.tokens, user: resp.user })
      setApiTokens(resp.tokens)
      set({ authedUser: resp.user, tokens: resp.tokens })
      return { ok: true }
    } catch (err) {
      const code = errorCodeOf(err)
      if (code === 'INVALID_CREDENTIALS' || code === 'VALIDATION_FAILED') {
        return { ok: false, code: 'INVALID_CREDENTIALS' }
      }
      // network-style errors (offline, CORS, timeout)
      const e = err as { message?: string; code?: string }
      const isNetwork = e?.code === 'ERR_NETWORK' || e?.message?.toLowerCase().includes('network')
      return {
        ok: false,
        code: isNetwork ? 'NETWORK' : 'UNKNOWN',
        message: e?.message,
      }
    }
  },

  logout: async () => {
    try {
      // Best-effort: если токен уже невалиден, всё равно очищаем локально.
      if (get().tokens) await authApi.logout()
    } catch {
      // ignore
    }
    tokenStore.clear()
    // Bug R fix: query cache очищается на logout — иначе при login другого юзера
    // он бы увидел кэшированные данные предыдущего.
    clearQueryCache()
    setApiTokens(null)
    set({
      authedUser: null,
      tokens: null,
      commandPaletteOpen: false,
      roleSwitcherOpen: false,
      contractModalOpen: false,
    })
  },

  setActiveRole: (user) => {
    // Без backend это просто UI-trick. При наличии настоящих токенов лучше переподписать сессию.
    set({ authedUser: user })
    // Также сохраняем в localStorage чтобы перезагрузка не сбрасывала демо-роль.
    const current = get()
    if (current.tokens) {
      tokenStore.save({ tokens: current.tokens, user })
    }
  },

  setPeriod: (p) => set({ period: p }),

  setLanguage: (lang) => {
    try {
      cacheStorage?.setItem(LANG_KEY, lang)
    } catch {
      /* ignore */
    }
    if (typeof document !== 'undefined') document.documentElement.lang = lang
    set({ language: lang })
  },

  toggleSidebar: () => set((s) => ({ sidebarCollapsed: !s.sidebarCollapsed })),
  openCommandPalette: () => set({ commandPaletteOpen: true }),
  closeCommandPalette: () => set({ commandPaletteOpen: false }),
  openRoleSwitcher: () => set({ roleSwitcherOpen: true }),
  closeRoleSwitcher: () => set({ roleSwitcherOpen: false }),
  openContractModal: () => set({ contractModalOpen: true }),
  closeContractModal: () => set({ contractModalOpen: false }),
}))
