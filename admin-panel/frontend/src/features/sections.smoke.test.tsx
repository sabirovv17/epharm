// Smoke-тесты для 8 секций (Rules, Promo, Pharmacies, Pharmacists — у них свои тесты с QueryClient).
// Каждая секция: рендерится PageHeader + Empty/structural плейсхолдеры.
import { describe, expect, it } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { ToastHost } from '@/ui'
import type { ComponentType } from 'react'

import SettingsPage from './settings/SettingsPage'

interface Case {
  name: string
  Page: ComponentType
  title: RegExp
  /** Хотя бы одно из этих ключевых выражений должно быть в DOM. */
  contains: RegExp[]
  /** Если страница не содержит "0" и не должна. */
  hasZeroMetric?: boolean
}

const CASES: Case[] = [
  // DashboardPage переехал на real API (useDashboardSummary) — свой тест с QueryClient
  // в dashboard/DashboardPage.test.tsx.
  // ReconcilePage переехал на real API (useReceipts/useReconcileSummary) — свой тест
  // в reconcile/ReconcilePage.test.tsx.
  // ScreensPage переехал на real API (usePlaylists/useSlides) — свой тест
  // в screens/ScreensPage.test.tsx.
  // AIExamPage переехал на real API (useExamQuestions) — свой тест в ai-exam/AIExamPage.test.tsx.
  // LMSPage переехал на real API (useCourses) — свой тест в lms/LMSPage.test.tsx.
  // LiftPage переехал на real API (useLiftSummary) — свой тест в lift/LiftPage.test.tsx.
  {
    name: 'SettingsPage',
    Page: SettingsPage,
    title: /Настройки/i,
    contains: [/Профиль/, /Локализация/, /Безопасность/, /Часовой пояс/],
    hasZeroMetric: false,
  },
]

function renderPage(Page: ComponentType) {
  return render(
    <MemoryRouter>
      <ToastHost>
        <Page />
      </ToastHost>
    </MemoryRouter>,
  )
}

describe.each(CASES)('$name — smoke', ({ Page, title, contains, hasZeroMetric }) => {
  it('рендерит H1 с правильным заголовком', () => {
    renderPage(Page)
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(title)
  })

  it.each(contains)('содержит ключевое выражение %s', (rx) => {
    renderPage(Page)
    expect(screen.getAllByText(rx).length).toBeGreaterThan(0)
  })

  if (hasZeroMetric) {
    it('как минимум одна метрика содержит «0» (фейковых цифр нет)', () => {
      const { container } = renderPage(Page)
      // metric tiles содержат num span — ищем «0» как value
      const allText = within(container).queryAllByText('0')
      expect(allText.length).toBeGreaterThan(0)
    })
  }
})
