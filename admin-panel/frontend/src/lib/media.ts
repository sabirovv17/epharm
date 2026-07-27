// Прокси изображений витрины Medusa для админ-консоли.
//
// Medusa отдаёт фото товаров по голому HTTP (`http://78.140.246.238:9000/static/…`),
// а админка работает по HTTPS. Браузер блокирует загрузку http-картинок на
// https-странице (mixed content) → в галерее «битые» иконки. Бэкенд-прокси
// `/api/media/img?u=…` тянет картинку server-side и отдаёт по нашему HTTPS.
//
// Здесь — только переписывание URL для тега <img>. ВАЖНО: проксируем лишь для
// отображения; в БД (overrideImage/productImage) храним исходный URL Medusa, не
// прокси-путь — иначе данные завязались бы на инфраструктуру.

import { BASE_URL } from './api'

const EPHARM_MEDIA_HOST = 'epharm.inkar.kz'
const EPHARM_MEDIA_PATH = '/s3/epharm-receipts/'

/**
 * URL для `<img src>`: http-картинка Medusa → наш HTTPS-прокси, остальное (https,
 * data:, относительные) — без изменений. Пустое/недоступное → ''.
 */
export function proxyMedia(url?: string | null): string {
  const u = url?.trim()
  if (!u) return ''
  // Только голый http проксируем (именно он ломает mixed content). Бэкенд-прокси
  // дополнительно ограничивает хост (SSRF-guard) — чужой http вернёт 400 и отвалится.
  if (u.startsWith('http://')) return `${BASE_URL}/api/media/img?u=${encodeURIComponent(u)}`
  return u
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
