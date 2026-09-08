// Прокси изображений витрины Medusa для админ-консоли.
//
// Фото Medusa идут через backend-прокси, поэтому браузер не зависит от origin
// витрины. `/api/media/img?u=…` тянет картинку server-side и отдаёт по нашему HTTPS.
//
// Здесь — только переписывание URL для тега <img>. ВАЖНО: проксируем лишь для
// отображения; в БД (overrideImage/productImage) храним исходный URL Medusa, не
// прокси-путь — иначе данные завязались бы на инфраструктуру.

import { BASE_URL } from './api'

const EPHARM_MEDIA_HOST = 'epharm.inkar.kz'
const EPHARM_MEDIA_PATH = '/s3/epharm-receipts/'
const SAFE_RELATIVE_ORIGIN = 'https://relative-media.invalid'
const LOOPBACK_HOSTS = new Set(['localhost', '127.0.0.1', '[::1]'])

/**
 * Restrict values assigned to media URL attributes. Relative URLs stay on the
 * current origin, remote media must use HTTPS, and HTTP is accepted only for
 * a local development backend. Active-content schemes (javascript:, data:,
 * blob:, file:) and malformed URLs are rejected.
 */
export function safeImageSrc(url?: string | null): string | undefined {
  const raw = url?.trim()
  if (!raw || raw.startsWith('//')) return undefined

  try {
    const parsed = new URL(raw, SAFE_RELATIVE_ORIGIN)
    if (parsed.origin === SAFE_RELATIVE_ORIGIN) return raw
    if (parsed.protocol === 'https:') return parsed.toString()
    if (parsed.protocol === 'http:' && LOOPBACK_HOSTS.has(parsed.hostname)) {
      return parsed.toString()
    }
  } catch {
    // Invalid URLs must never reach a DOM URL attribute.
  }
  return undefined
}

/**
 * URL для `<img src>`: http-картинка Medusa → наш HTTPS-прокси; HTTPS and
 * same-origin relative URLs pass through the common URL policy.
 */
export function proxyMedia(url?: string | null): string {
  const u = url?.trim()
  if (!u) return ''
  // Только голый http проксируем (именно он ломает mixed content). Бэкенд-прокси
  // дополнительно ограничивает хост (SSRF-guard) — чужой http вернёт 400 и отвалится.
  if (u.startsWith('http://')) {
    return safeImageSrc(`${BASE_URL}/api/media/img?u=${encodeURIComponent(u)}`) ?? ''
  }
  return safeImageSrc(u) ?? ''
}

/**
 * Ролики экрана хранятся как абсолютные production-URL. Пока основной HTTPS
 * ingress вводится в эксплуатацию, консоль может открываться через тот же
 * Caddy по `http://…:8060` или внутреннему имени. В таком случае браузер не
 * должен пытаться получить видео через ещё недоступный HTTPS-host: Caddy уже
 * умеет отдать тот же объект по текущему origin через `/s3/*`.
 *
 * Переписываем только URL собственного публичного bucket-а. Сторонние ссылки
 * и ссылки из будущих внешних хранилищ сохраняем без изменений.
 */
export function resolveEpharmMediaUrl(
  url?: string | null,
  pageOrigin: string | null = typeof window === 'undefined' ? null : window.location.origin,
): string {
  const raw = url?.trim()
  if (!raw || !pageOrigin) return raw ?? ''

  try {
    const media = new URL(raw)
    const page = new URL(pageOrigin)
    const isStoredEpharmMedia =
      media.hostname.toLowerCase() === EPHARM_MEDIA_HOST &&
      media.pathname.startsWith(EPHARM_MEDIA_PATH)

    if (!isStoredEpharmMedia || media.origin === page.origin) return raw

    return new URL(`${media.pathname}${media.search}${media.hash}`, page.origin).toString()
  } catch {
    // Относительные и некорректные URL оставляем браузеру: относительный /s3
    // уже разрешается относительно текущего origin.
    return raw
  }
}
