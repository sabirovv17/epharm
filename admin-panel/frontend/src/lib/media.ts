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
