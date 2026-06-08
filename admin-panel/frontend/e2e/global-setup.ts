// Playwright globalSetup — выполняется ОДИН раз перед всем E2E-прогоном.
//
// Возвращает dev-БД backend к seed-базису через POST /api/admin/dev/reset.
// Зачем: backend — single-instance с персистентной БД; мутации прошлых прогонов
// (созданные промо, заархивированные правила, новые аптеки) копились и делали
// count-зависимые тесты (фильтры) flaky. Теперь каждый прогон стартует с
// идентичной фикстуры → детерминированные ассерты.
//
// Эндпоинт существует только в profile=dev (в prod бина нет). Если backend не
// поднят — бросаем понятную ошибку (как assertBackendUp в fixtures).

import { request, type FullConfig } from '@playwright/test'
import { BACKEND_URL } from './fixtures'

export default async function globalSetup(_config: FullConfig): Promise<void> {
  const ctx = await request.newContext()
  try {
    const health = await ctx.get(`${BACKEND_URL}/api/health`).catch(() => null)
    if (!health || !health.ok()) {
      throw new Error(
        `Backend (${BACKEND_URL}) недоступен. Запусти \`./gradlew bootRun\` ` +
          'из admin-panel/backend перед E2E.',
      )
    }
    const res = await ctx.post(`${BACKEND_URL}/api/admin/dev/reset`)
    if (!res.ok()) {
      throw new Error(
        `Сброс dev-данных не удался: ${res.status()}. Backend должен быть в profile=dev ` +
          '(DevController доступен только там).',
      )
    }
    const body = await res.json()
    // eslint-disable-next-line no-console
    console.log('[e2e] dev-данные сброшены к seed-базису:', JSON.stringify(body))
  } finally {
    await ctx.dispose()
  }
}
