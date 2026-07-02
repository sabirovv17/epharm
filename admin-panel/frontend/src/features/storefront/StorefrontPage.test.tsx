import { describe, expect, it, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { ToastHost } from '@/ui'
import type { StorefrontPageDto, StorefrontProductDto } from '@/lib/api-types'
import StorefrontPage from './StorefrontPage'

const hooks = vi.hoisted(() => ({ useStorefront: vi.fn() }))
vi.mock('@/lib/queries/storefront', () => hooks)

function mkProduct(over: Partial<StorefrontProductDto> = {}): StorefrontProductDto {
  return {
    id: 'p1',
    name: 'Аспирин Кардио 100мг №28',
    brand: 'Bayer',
    mnn: 'Ацетилсалициловая кислота',
    rxOtc: 'OTC',
    price: 1290,
    currency: 'KZT',
    imageUrl: null,
    barcode: '4600123456789',
    category: 'Сердце и сосуды',
    ...over,
  }
}

function mkPage(items: StorefrontProductDto[], total = items.length): StorefrontPageDto {
  return { items, total, limit: 50, offset: 0 }
}

beforeEach(() => {
  vi.clearAllMocks()
  hooks.useStorefront.mockReturnValue({
    data: mkPage([]),
    isLoading: false,
    isError: false,
    error: null,
    refetch: vi.fn(),
    isFetching: false,
  })
})

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <ToastHost>
          <StorefrontPage />
        </ToastHost>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('StorefrontPage', () => {
  it('показывает пустое состояние без товаров', () => {
    renderPage()
    expect(screen.getByText('Каталог витрины пуст')).toBeInTheDocument()
  })

  it('показывает товары и «Цена в аптеке» для товара без цены', () => {
    hooks.useStorefront.mockReturnValue({
      data: mkPage([
        mkProduct(),
        mkProduct({
          id: 'p2',
          name: 'Везилют капс. №30',
          brand: null,
          mnn: null,
          price: null,
          rxOtc: 'Rx',
        }),
      ]),
      isLoading: false,
      isError: false,
      error: null,
      refetch: vi.fn(),
      isFetching: false,
    })
    renderPage()
    expect(screen.getByText('Аспирин Кардио 100мг №28')).toBeInTheDocument()
    expect(screen.getByText('Везилют капс. №30')).toBeInTheDocument()
    expect(screen.getByText('Цена в аптеке')).toBeInTheDocument()
  })

  it('показывает retail-цену и диапазон по аптекам', () => {
    hooks.useStorefront.mockReturnValue({
      data: mkPage([
        mkProduct({
          price: 5775,
          priceMin: 2060,
          priceMax: 6670,
          pharmacyPriceCount: 227,
        }),
      ]),
      isLoading: false,
      isError: false,
      error: null,
      refetch: vi.fn(),
      isFetching: false,
    })
    renderPage()
    expect(screen.getByText('5 775 ₸')).toBeInTheDocument()
    expect(screen.getByText('2 060 ₸–6 670 ₸')).toBeInTheDocument()
  })

  it('ошибка загрузки показывает кнопку «Повторить»', () => {
    hooks.useStorefront.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: true,
      error: new Error('boom'),
      refetch: vi.fn(),
      isFetching: false,
    })
    renderPage()
    expect(screen.getByText('Повторить')).toBeInTheDocument()
  })
})
