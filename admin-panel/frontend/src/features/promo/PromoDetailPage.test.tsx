// Тесты PromoDetailPage — полная страница кампании /promo/:id.
// Редактирование полей (PATCH), статус-действия, cover-overlay, archived read-only.

import { describe, expect, it, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { ToastHost } from '@/ui'
import type { PromoDto } from '@/lib/api-types'
import PromoDetailPage from './PromoDetailPage'

const promoHooks = vi.hoisted(() => ({
  usePromo: vi.fn(),
  useUpdatePromo: vi.fn(),
  useArchivePromo: vi.fn(),
  useRestorePromo: vi.fn(),
  // не используются страницей, но модуль экспортит — заглушим на всякий случай
  usePromos: vi.fn(),
  useCreatePromo: vi.fn(),
}))
vi.mock('@/lib/queries/promo', () => promoHooks)

function LocationProbe() {
  const loc = useLocation()
  return <div data-testid="location-probe">{loc.pathname}</div>
}

function mkPromo(over: Partial<PromoDto> = {}): PromoDto {
  return {
    id: 'pr_1',
    title: 'Майский марафон',
    status: 'active',
    brand: 'Jadran',
    period: '01.05 — 31.05',
    pharmacies: 100,
    budget: 1_000_000,
    spent: 250_000,
    kpi: '1000 рек.',
    cover: '#16C97A',
    createdBy: 'u',
    createdAt: '2026-05-01T00:00:00Z',
    updatedAt: '2026-05-02T00:00:00Z',
    ...over,
  }
}

function setPromo(data: PromoDto | undefined, extra: Record<string, unknown> = {}) {
  promoHooks.usePromo.mockReturnValue({
    data,
    isLoading: false,
    isError: false,
    error: null,
    refetch: vi.fn(),
    ...extra,
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  setPromo(mkPromo())
  promoHooks.useUpdatePromo.mockReturnValue({ mutate: vi.fn(), isPending: false })
  promoHooks.useArchivePromo.mockReturnValue({ mutate: vi.fn(), isPending: false })
  promoHooks.useRestorePromo.mockReturnValue({ mutate: vi.fn(), isPending: false })
})

function renderDetail(id = 'pr_1') {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[`/promo/${id}`]}>
        <ToastHost>
          <Routes>
            <Route path="/promo" element={<div>LIST</div>} />
            <Route path="/promo/:id" element={<PromoDetailPage />} />
          </Routes>
          <LocationProbe />
        </ToastHost>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('PromoDetailPage — рендер', () => {
  it('cover-overlay содержит title + brand', () => {
    renderDetail()
    const cover = screen.getByTestId('promo-detail-cover')
    expect(cover).toHaveTextContent('Майский марафон')
    expect(cover).toHaveTextContent('Jadran')
  })

  it('cover наследует цвет промо (jsdom hex→rgb)', () => {
    setPromo(mkPromo({ cover: '#F4B73A' }))
    renderDetail()
    expect(screen.getByTestId('promo-detail-cover').getAttribute('style')).toMatch(
      /rgb\(244,\s*183,\s*58\)/,
    )
  })

  it('поля формы заполнены значениями кампании', () => {
    renderDetail()
    expect(screen.getByDisplayValue('Майский марафон')).toBeInTheDocument()
    expect(screen.getByDisplayValue('Jadran')).toBeInTheDocument()
    expect(screen.getByDisplayValue('1000000')).toBeInTheDocument()
  })

  it('«К кампаниям» возвращает на список', async () => {
    const user = userEvent.setup()
    renderDetail()
    await user.click(screen.getByRole('button', { name: /К кампаниям/ }))
    expect(screen.getByTestId('location-probe')).toHaveTextContent('/promo')
  })
})

describe('PromoDetailPage — редактирование', () => {
  it('«Сохранить» disabled пока нет изменений, активна после правки', async () => {
    const user = userEvent.setup()
    renderDetail()
    expect(screen.getByRole('button', { name: /^Сохранить$/ })).toBeDisabled()
    await user.type(screen.getByDisplayValue('Майский марафон'), ' 2026')
    expect(screen.getByRole('button', { name: /^Сохранить$/ })).toBeEnabled()
  })

  it('Сохранить вызывает useUpdatePromo с патчем', async () => {
    const mutate = vi.fn()
    promoHooks.useUpdatePromo.mockReturnValue({ mutate, isPending: false })
    const user = userEvent.setup()
    renderDetail()
    const titleInput = screen.getByDisplayValue('Майский марафон')
    await user.clear(titleInput)
    await user.type(titleInput, 'Летняя кампания')
    await user.click(screen.getByRole('button', { name: /^Сохранить$/ }))
    expect(mutate).toHaveBeenCalledWith(
      { id: 'pr_1', patch: expect.objectContaining({ title: 'Летняя кампания', brand: 'Jadran' }) },
      expect.any(Object),
    )
  })

  it('пустое название → ошибка, mutate не зовётся', async () => {
    const mutate = vi.fn()
    promoHooks.useUpdatePromo.mockReturnValue({ mutate, isPending: false })
    const user = userEvent.setup()
    renderDetail()
    await user.clear(screen.getByDisplayValue('Майский марафон'))
    await user.click(screen.getByRole('button', { name: /^Сохранить$/ }))
    expect(screen.getByText(/Введите название кампании/i)).toBeInTheDocument()
    expect(mutate).not.toHaveBeenCalled()
  })
})

describe('PromoDetailPage — статус-действия', () => {
  it('«На паузу» для active → useUpdatePromo status=paused', async () => {
    const mutate = vi.fn()
    promoHooks.useUpdatePromo.mockReturnValue({ mutate, isPending: false })
    const user = userEvent.setup()
    renderDetail()
    await user.click(screen.getByRole('button', { name: /На паузу/ }))
    expect(mutate).toHaveBeenCalledWith(
      { id: 'pr_1', patch: { status: 'paused' } },
      expect.any(Object),
    )
  })

  it('archived → поля заблокированы + кнопка «Восстановить из архива»', () => {
    setPromo(mkPromo({ status: 'archived' }))
    renderDetail()
    expect(screen.getByDisplayValue('Майский марафон')).toBeDisabled()
    expect(screen.queryByRole('button', { name: /^Сохранить$/ })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Восстановить из архива/ })).toBeInTheDocument()
  })

  it('Восстановить вызывает useRestorePromo', async () => {
    const mutate = vi.fn()
    promoHooks.useRestorePromo.mockReturnValue({ mutate, isPending: false })
    setPromo(mkPromo({ status: 'archived' }))
    const user = userEvent.setup()
    renderDetail()
    await user.click(screen.getByRole('button', { name: /Восстановить из архива/ }))
    expect(mutate).toHaveBeenCalledWith('pr_1', expect.any(Object))
  })
})

describe('PromoDetailPage — loading/error/not-found', () => {
  it('isLoading', () => {
    setPromo(undefined, { isLoading: true })
    renderDetail()
    expect(screen.getByText(/Загружаем кампанию…/i)).toBeInTheDocument()
  })

  it('isError + Повторить', async () => {
    const refetch = vi.fn()
    setPromo(undefined, { isError: true, error: new Error('x'), refetch })
    const user = userEvent.setup()
    renderDetail()
    expect(screen.getByText(/Не удалось загрузить кампанию/i)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /Повторить/ }))
    expect(refetch).toHaveBeenCalled()
  })

  it('not-found когда data пуст', () => {
    setPromo(undefined)
    renderDetail()
    expect(screen.getByText(/Кампания не найдена/i)).toBeInTheDocument()
  })
})
