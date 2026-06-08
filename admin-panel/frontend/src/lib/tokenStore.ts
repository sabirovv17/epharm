// Persist auth state в localStorage между перезагрузками страницы.
// Ключ epharm.auth.* — namespace чтобы не конфликтовать с другими ключами.
//
// Граcefully handles environments без localStorage (SSR, web workers, jsdom
// в некоторых конфигурациях Vitest) — операции становятся no-op.

import type { AuthTokens, UserDto } from './api-types'

const KEY_TOKENS = 'epharm.auth.tokens'
const KEY_USER = 'epharm.auth.user'

const storage: Storage | null =
  typeof globalThis !== 'undefined' && 'localStorage' in globalThis
    ? (globalThis as { localStorage: Storage }).localStorage
    : null

export interface PersistedAuth {
  tokens: AuthTokens
  user: UserDto
}

export const tokenStore = {
  load(): PersistedAuth | null {
    if (!storage) return null
    try {
      const rawT = storage.getItem(KEY_TOKENS)
      const rawU = storage.getItem(KEY_USER)
      if (!rawT || !rawU) return null
      const tokens = JSON.parse(rawT) as AuthTokens
      const user = JSON.parse(rawU) as UserDto
      return { tokens, user }
    } catch {
      return null
    }
  },

  save(auth: PersistedAuth): void {
    if (!storage) return
    try {
      storage.setItem(KEY_TOKENS, JSON.stringify(auth.tokens))
      storage.setItem(KEY_USER, JSON.stringify(auth.user))
    } catch {
      // QuotaExceededError, private mode etc. — silently ignore.
    }
  },

  clear(): void {
    if (!storage) return
    try {
      storage.removeItem(KEY_TOKENS)
      storage.removeItem(KEY_USER)
    } catch {
      // ignore
    }
  },
}
