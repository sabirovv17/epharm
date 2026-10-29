export interface ProducerProfile {
  id: string
  name: string | null
  phoneMasked: string
  profileComplete?: boolean
}
export interface ProducerSession {
  accessToken: string
  refreshToken: string
  tokenType: 'Bearer'
  expiresIn: number
  sessionExpiresAt: string
  customer: ProducerProfile
}
export interface ProducerChallenge {
  challengeId: string
  ttlSeconds: number
  retryAfterSeconds: number
  phoneMasked: string
  message: string
}
interface StoragePort {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
  removeItem(key: string): void
}
const STORAGE = 'epharm.producer.session.v1'
function clearProducerDraftSnapshots() {
  try {
    for (const key of Object.keys(sessionStorage))
      if (key.startsWith('epharm.commercial.draft.v1.producer.')) sessionStorage.removeItem(key)
  } catch {
    /* unavailable storage must not block session changes */
  }
}
const cancelled = () => new DOMException('Producer session changed', 'AbortError')
export class ProducerApiError extends Error {
  status: number
  fields: Record<string, string>
  code?: string
  constructor(status: number, message: string, fields: Record<string, string> = {}, code?: string) {
    super(message)
    this.status = status
    this.fields = fields
    this.code = code
  }
}
export function producerError(error: unknown) {
  if (error instanceof ProducerApiError && [404, 503].includes(error.status))
    return 'Кабинет производителя временно недоступен. Попробуйте позже.'
  return error instanceof Error && error.name !== 'AbortError'
    ? error.message
    : 'Запрос отменён. Повторите действие в текущей сессии.'
}

/** Independent producer realm. Admin/mobile stores, cookies and interceptors are never used. */
export class ProducerClient {
  private value: { session: ProducerSession | null; epoch: number } = { session: null, epoch: 0 }
  private listeners = new Set<() => void>()
  private refreshing: Promise<void> | null = null
  private transport: typeof fetch
  private storage: StoragePort | null
  private base: string
  constructor(transport: typeof fetch, storage: StoragePort | null, base = '') {
    this.transport = transport
    this.storage = storage
    this.base = base
    try {
      const stored = JSON.parse(storage?.getItem(STORAGE) ?? 'null') as ProducerSession | null
      if (
        stored?.accessToken &&
        stored.refreshToken &&
        stored.customer?.id &&
        Date.parse(stored.sessionExpiresAt) > Date.now()
      )
        this.value = { session: stored, epoch: 0 }
      else {
        storage?.removeItem(STORAGE)
        clearProducerDraftSnapshots()
      }
    } catch {
      /* unavailable or corrupt session storage is never an authenticated session */
      clearProducerDraftSnapshots()
    }
  }
  snapshot = () => this.value
  subscribe = (listener: () => void) => {
    this.listeners.add(listener)
    return () => {
      this.listeners.delete(listener)
    }
  }
  private change(session: ProducerSession | null, principalChange: boolean) {
    if (principalChange) {
      this.refreshing = null
      clearProducerDraftSnapshots()
    }
    this.value = { session, epoch: this.value.epoch + (principalChange ? 1 : 0) }
    try {
      if (session) this.storage?.setItem(STORAGE, JSON.stringify(session))
      else this.storage?.removeItem(STORAGE)
    } catch {
      /* session remains memory-only */
    }
    this.listeners.forEach((listener) => listener())
  }
  private async send<T>(
    path: string,
    init: RequestInit,
    token?: string,
    format: 'json' | 'xlsx' | 'pdf' | 'video' | 'image' = 'json',
  ): Promise<T> {
    if (!path.startsWith('/api/producer/') || path.includes('..') || path.includes('\\'))
      throw new Error('Invalid producer API path')
    const headers = new Headers(init.headers)
    const videos = ['video/mp4', 'video/quicktime', 'video/webm']
    const mime =
      format === 'xlsx'
        ? 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
        : format === 'pdf'
          ? 'application/pdf'
          : format === 'video'
            ? videos.join(', ')
            : format === 'image'
              ? 'image/jpeg, image/png, image/webp'
              : 'application/json'
    headers.set('Accept', mime)
    if (init.body instanceof FormData) headers.delete('Content-Type')
    else if (init.body) headers.set('Content-Type', 'application/json')
    if (token) headers.set('Authorization', `Bearer ${token}`)
    const response = await this.transport(`${this.base}${path}`, {
      ...init,
      headers,
      credentials: 'omit',
      cache: 'no-store',
    })
    if (!response.ok) {
      const body = await response.json().catch(() => ({}))
      throw new ProducerApiError(
        response.status,
        body.message ||
          (response.status === 401
            ? 'Войдите в кабинет производителя заново.'
            : 'Не удалось выполнить запрос.'),
        body.fields ?? {},
        body.code,
      )
    }
    if (format !== 'json') {
      const contentType = response.headers.get('content-type')?.toLowerCase().split(';')[0].trim()
      if (
        format === 'video'
          ? !videos.includes(contentType ?? '')
          : format === 'image'
            ? !['image/jpeg', 'image/png', 'image/webp'].includes(contentType ?? '')
            : contentType !== mime
      )
        throw new Error(
          `Сервер не вернул ${format === 'xlsx' ? 'Excel' : format === 'pdf' ? 'PDF' : format === 'image' ? 'фотографию' : 'видео'}. Файл не сохранён.`,
        )
      const blob = await response.blob()
      if (format === 'pdf' && (await blob.slice(0, 5).text()) !== '%PDF-')
        throw new Error('Сервер не вернул действительный PDF. Файл не сохранён.')
      return blob as T
    }
    return response.status === 204 ? (undefined as T) : (response.json() as Promise<T>)
  }
  requestCode(phone: string) {
    return this.send<ProducerChallenge>('/api/producer/auth/sms/request', {
      method: 'POST',
      body: JSON.stringify({ phone }),
    })
  }
  async verify(challengeId: string, code: string) {
    const epoch = this.value.epoch
    const session = await this.send<ProducerSession>('/api/producer/auth/sms/verify', {
      method: 'POST',
      body: JSON.stringify({ challengeId, code }),
    })
    if (epoch !== this.value.epoch) throw cancelled()
    this.change(session, true)
    return session.customer
  }
  private refresh(epoch: number): Promise<void> {
    if (this.refreshing) return this.refreshing
    const session = this.value.session
    if (!session) return Promise.reject(new ProducerApiError(401, 'Войдите заново.'))
    const promise = this.send<ProducerSession>('/api/producer/auth/refresh', {
      method: 'POST',
      body: JSON.stringify({ refreshToken: session.refreshToken }),
    })
      .then((next) => {
        if (epoch !== this.value.epoch || this.value.session?.refreshToken !== session.refreshToken)
          throw cancelled()
        if (next.customer.id !== session.customer.id)
          throw new ProducerApiError(401, 'Сессия производителя изменилась.')
        this.change(next, false)
      })
      .catch((error) => {
        if (
          epoch === this.value.epoch &&
          error instanceof ProducerApiError &&
          [401, 403].includes(error.status)
        )
          this.change(null, true)
        throw error
      })
      .finally(() => {
        if (this.refreshing === promise) this.refreshing = null
      })
    this.refreshing = promise
    return promise
  }
  async request<T>(path: string, init: RequestInit = {}): Promise<T> {
    return this.authenticated<T>(path, init, 'json')
  }
  download(path: string, signal: AbortSignal): Promise<Blob> {
    return this.authenticated<Blob>(path, { signal }, 'xlsx')
  }
  downloadPdf(path: string, signal: AbortSignal): Promise<Blob> {
    return this.authenticated<Blob>(path, { signal }, 'pdf')
  }
  downloadVideo(path: string, signal: AbortSignal): Promise<Blob> {
    return this.authenticated<Blob>(path, { signal }, 'video')
  }
  downloadImage(path: string, signal: AbortSignal): Promise<Blob> {
    return this.authenticated<Blob>(path, { signal }, 'image')
  }
  upload<T>(path: string, form: FormData, signal?: AbortSignal): Promise<T> {
    return this.authenticated<T>(path, { method: 'POST', body: form, signal }, 'json')
  }
  private async authenticated<T>(
    path: string,
    init: RequestInit,
    format: 'json' | 'xlsx' | 'pdf' | 'video' | 'image',
  ): Promise<T> {
    const { session, epoch } = this.value
    if (!session) throw new ProducerApiError(401, 'Войдите в кабинет производителя.')
    try {
      const data = await this.send<T>(path, init, session.accessToken, format)
      if (epoch !== this.value.epoch) throw cancelled()
      return data
    } catch (error) {
      if (epoch !== this.value.epoch) throw cancelled()
      if (!(error instanceof ProducerApiError) || error.status !== 401) throw error
      await this.refresh(epoch)
      if (epoch !== this.value.epoch || !this.value.session) throw cancelled()
      const data = await this.send<T>(path, init, this.value.session.accessToken, format)
      if (epoch !== this.value.epoch) throw cancelled()
      return data
    }
  }
  logout() {
    const session = this.value.session
    this.change(null, true)
    this.refreshing = null
    return session
      ? this.send<void>('/api/producer/auth/logout', { method: 'POST' }, session.accessToken).catch(
          () => {},
        )
      : Promise.resolve()
  }
}

let storage: StoragePort | null = null
try {
  storage = typeof sessionStorage === 'undefined' ? null : sessionStorage
} catch {
  /* private browsing */
}
export const producerClient = new ProducerClient(
  (...args) => fetch(...args),
  storage,
  (import.meta.env.VITE_API_BASE_URL as string | undefined) ?? 'http://localhost:8080',
)
