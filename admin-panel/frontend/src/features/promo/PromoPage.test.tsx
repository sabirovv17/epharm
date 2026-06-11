// Тесты PromoPage — пустой/loading/error/полный список + create flow.
// Мокаем @/lib/queries/promo напрямую (vi.mock). Это даёт детерминированные данные
// без MSW.

import { describe, expect, it, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { ToastHost } from '@/ui'
import type { PromoDto } from '@/lib/api-types'
import PromoPage from './PromoPage'

// Пробник текущего пути — карточка теперь навигирует на /promo/:id, а не
// открывает модалку. Проверяем переход по pathname.
function LocationProbe() {
  const loc = useLocation()
  return <div data-testid="location-probe">{loc.pathname}</div>
}

// ── Mock queries ────────────────────────────────────────────────────────
const promoHooks = vi.hoisted(() => ({
  usePromos: vi.fn(),
  usePromo: vi.fn(),
  useCreatePromo: vi.fn(),
  useUpdatePromo: vi.fn(),
  useArchivePromo: vi.fn(),
  useRestorePromo: vi.fn(),
}))

// PromoProductPicker дёргает useStorefront — мокаем, чтобы поиск товара отдавал фикстуру.
const storefrontHooks = vi.hoisted(() => ({ useStorefront: vi.fn() }))

vi.mock('@/lib/queries/promo', () => promoHooks)
vi.mock('@/lib/queries/storefront', () => ({
  storefrontKeys: { all: ['storefront'], list: () => ['storefront', 'list'] },
  useStorefront: storefrontHooks.useStorefront,
}))

function mkPromo(over: Partial<PromoDto> = {}): PromoDto {
  return {
    id: 'pr_001',
    title: 'Test campaign',
    status: 'active',
    brand: 'Jadran-Galenski',
    period: '01.05 — 31.05',
    pharmacies: 100,
    budget: 1_000_000,
    spent: 250_000,
    kpi: '1000 рек.',
    cover: '#16C97A',
    medusaProductId: null,
    productName: '',
    productImage: null,
    dateStart: null,
    dateEnd: null,
    tiers: [],
    createdBy: 'u',
    createdAt: '2026-05-01T00:00:00Z',
    updatedAt: '2026-05-01T00:00:00Z',
    ...over,
  }
}

function setPromosResponse(promos: PromoDto[] = []) {
  promoHooks.usePromos.mockReturnValue({
    data: promos,
    isLoading: false,
    isError: false,
    refetch: vi.fn(),
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  setPromosResponse([])
  promoHooks.useCreatePromo.mockReturnValue({ mutateAsync: vi.fn(), isPending: false })
  promoHooks.useUpdatePromo.mockReturnValue({ mutate: vi.fn(), isPending: false })
  promoHooks.useArchivePromo.mockReturnValue({ mutate: vi.fn(), isPending: false })
  promoHooks.useRestorePromo.mockReturnValue({ mutate: vi.fn(), isPending: false })
  storefrontHooks.useStorefront.mockReturnValue({
    data: {
      items: [
        {
          id: 'prod_1',
          name: 'Панкраген 0,2г капс. №60',
          brand: 'Access Bioscience',
          mnn: null,
          rxOtc: null,
          price: null,
          currency: 'KZT',
          imageUrl: null,
          barcode: null,
          category: null,
        },
      ],
      total: 1,
      limit: 20,
      offset: 0,
    },
    isFetching: false,
  })
})

function renderPromo() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/promo']}>
        <ToastHost>
          <Routes>
            <Route path="/promo" element={<PromoPage />} />
            <Route path="/promo/:id" element={<div>DETAIL</div>} />
          </Routes>
          <LocationProbe />
        </ToastHost>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

// ── Тесты ────────────────────────────────────────────────────────────────

describe('PromoPage — базовый рендер (пустой список)', () => {
  it('показывает H1 «Промо-кампании»', () => {
    renderPromo()
    expect(screen.getByRole('heading', { level: 1, name: /Промо-кампании/i })).toBeInTheDocument()
  })

  it('4 метрики с нулевыми значениями', () => {
    renderPromo()
    expect(screen.getByText('Активных кампаний')).toBeInTheDocument()
    expect(screen.getByText('Бюджет в работе')).toBeInTheDocument()
    expect(screen.getByText('Освоено')).toBeInTheDocument()
    expect(screen.getByText('Среднее ROI')).toBeInTheDocument()
    expect(screen.getByText('из 0')).toBeInTheDocument()
  })

  it('Empty state с CTA «Новая кампания»', () => {
    renderPromo()
    expect(screen.getByText(/Кампаний пока нет/i)).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: /Новая кампания/ }).length).toBeGreaterThan(0)
  })
})

describe('PromoPage — загрузка / ошибка', () => {
  it('isLoading → loading state', () => {
    promoHooks.usePromos.mockReturnValue({
      data: undefined,
      isLoading: true,
      isError: false,
      refetch: vi.fn(),
    })
    renderPromo()
    expect(screen.getByText(/Загружаем кампании…/i)).toBeInTheDocument()
  })

  it('isError → ошибка + кнопка «Повторить»', async () => {
    const refetch = vi.fn()
    promoHooks.usePromos.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: true,
      refetch,
    })
    const user = userEvent.setup()
    renderPromo()
    expect(screen.getByText(/Не удалось загрузить кампании/i)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /Повторить/ }))
    expect(refetch).toHaveBeenCalled()
  })
})

describe('PromoPage — отображение списка', () => {
  it('renders grid с карточками promo', () => {
    setPromosResponse([
      mkPromo({ id: 'pr_001', title: 'Майская кампания', status: 'active' }),
      mkPromo({ id: 'pr_002', title: 'Черновик', status: 'draft' }),
    ])
    renderPromo()
    expect(screen.getByTestId('promo-grid')).toBeInTheDocument()
    expect(screen.getByTestId('promo-card-pr_001')).toBeInTheDocument()
    expect(screen.getByTestId('promo-card-pr_002')).toBeInTheDocument()
    expect(screen.getByText('Майская кампания')).toBeInTheDocument()
  })

  it('метрики корректно считают активные кампании и бюджет', () => {
    setPromosResponse([
      mkPromo({ id: 'pr_1', status: 'active', budget: 1_000_000, spent: 500_000 }),
      mkPromo({ id: 'pr_2', status: 'paused', budget: 2_000_000, spent: 0 }),
      mkPromo({ id: 'pr_3', status: 'active', budget: 500_000, spent: 100_000 }),
    ])
    renderPromo()
    // 2 активные из 3
    expect(screen.getByText('2')).toBeInTheDocument()
    expect(screen.getByText('из 3')).toBeInTheDocument()
  })

  it('фильтр по status=active скрывает draft/paused', async () => {
    setPromosResponse([
      mkPromo({ id: 'pr_a', status: 'active', title: 'Активная XYZ' }),
      mkPromo({ id: 'pr_d', status: 'draft', title: 'Черновик XYZ' }),
    ])
    const user = userEvent.setup()
    renderPromo()
    // По умолчанию обе карточки видны (используем уникальные части названий
    // чтобы не пересечься с лейблами вариантов Select «Черновики»/«Активные»).
    expect(screen.getByText('Активная XYZ')).toBeInTheDocument()
    expect(screen.getByText('Черновик XYZ')).toBeInTheDocument()

    const select = screen.getByDisplayValue('Все статусы') as HTMLSelectElement
    await user.selectOptions(select, 'active')
    expect(screen.getByText('Активная XYZ')).toBeInTheDocument()
    expect(screen.queryByText('Черновик XYZ')).not.toBeInTheDocument()
  })

  it('поиск по title фильтрует список', async () => {
    setPromosResponse([
      mkPromo({ id: 'pr_a', title: 'Майская кампания' }),
      mkPromo({ id: 'pr_b', title: 'Летняя кампания' }),
    ])
    const user = userEvent.setup()
    renderPromo()
    await user.type(screen.getByPlaceholderText(/Поиск кампании/), 'Майск')
    expect(screen.getByText('Майская кампания')).toBeInTheDocument()
    expect(screen.queryByText('Летняя кампания')).not.toBeInTheDocument()
  })
})

describe('PromoPage — toggle и архивация', () => {
  it('кнопка пауза → useUpdatePromo.mutate с status=paused', async () => {
    const mutate = vi.fn()
    promoHooks.useUpdatePromo.mockReturnValue({ mutate, isPending: false })
    setPromosResponse([mkPromo({ id: 'pr_x', status: 'active' })])
    const user = userEvent.setup()
    renderPromo()
    await user.click(screen.getByRole('button', { name: /Поставить на паузу/ }))
    expect(mutate).toHaveBeenCalledWith(
      { id: 'pr_x', patch: { status: 'paused' } },
      expect.any(Object),
    )
  })

  it('кнопка возобновить (пауза → активная)', async () => {
    const mutate = vi.fn()
    promoHooks.useUpdatePromo.mockReturnValue({ mutate, isPending: false })
    setPromosResponse([mkPromo({ id: 'pr_p', status: 'paused' })])
    const user = userEvent.setup()
    renderPromo()
    await user.click(screen.getByRole('button', { name: /Возобновить/ }))
    expect(mutate).toHaveBeenCalledWith(
      { id: 'pr_p', patch: { status: 'active' } },
      expect.any(Object),
    )
  })

  it('у archived promo кнопки toggle/архив заменены на «Восстановить»', () => {
    setPromosResponse([mkPromo({ id: 'pr_arch', status: 'archived' })])
    renderPromo()
    expect(screen.queryByRole('button', { name: /Поставить на паузу/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Архивировать/ })).not.toBeInTheDocument()
    // Bug L: кнопка восстановления видна
    expect(screen.getByRole('button', { name: /Восстановить/ })).toBeInTheDocument()
  })
})

describe('Bug L regression — restore из архива', () => {
  it('клик «Восстановить» на archived card → useRestorePromo.mutate', async () => {
    const mutate = vi.fn()
    promoHooks.useRestorePromo.mockReturnValue({ mutate, isPending: false })
    setPromosResponse([mkPromo({ id: 'pr_arch', status: 'archived' })])
    const user = userEvent.setup()
    renderPromo()
    await user.click(screen.getByRole('button', { name: /Восстановить/ }))
    expect(mutate).toHaveBeenCalledWith('pr_arch', expect.any(Object))
  })
})

describe('Bug M regression — мёртвые кнопки удалены', () => {
  it('«Экспорт» отсутствует в header (раньше была без onClick)', () => {
    renderPromo()
    expect(screen.queryByRole('button', { name: /Экспорт/ })).not.toBeInTheDocument()
  })

  it('⋯-button «Дополнительные действия» отсутствует на карточке', () => {
    setPromosResponse([mkPromo({ id: 'pr_a', status: 'active' })])
    renderPromo()
    expect(
      screen.queryByRole('button', { name: /Дополнительные действия/ }),
    ).not.toBeInTheDocument()
  })
})

// Cover-overlay + редактирование теперь на отдельной странице — см.
// PromoDetailPage.test.tsx.

// (Удалён блок «Bug O — live preview cover»: новая форма создания — товарная
//  акция с пикером товара и порогами, без cover-превью кампании.)

describe('Клик по карточке → переход на /promo/:id (не модалка)', () => {
  it('клик по PromoCard навигирует на страницу кампании', async () => {
    setPromosResponse([mkPromo({ id: 'pr_test', title: 'Майский марафон' })])
    const user = userEvent.setup()
    renderPromo()
    expect(screen.getByTestId('location-probe')).toHaveTextContent('/promo')
    await user.click(screen.getByTestId('promo-card-pr_test'))
    expect(screen.getByTestId('location-probe')).toHaveTextContent('/promo/pr_test')
  })

  it('Enter на карточке тоже навигирует (a11y)', async () => {
    setPromosResponse([mkPromo({ id: 'pr_k' })])
    const user = userEvent.setup()
    renderPromo()
    screen.getByTestId('promo-card-pr_k').focus()
    await user.keyboard('{Enter}')
    expect(screen.getByTestId('location-probe')).toHaveTextContent('/promo/pr_k')
  })

  it('клик на inline кнопку pause НЕ навигирует (stopPropagation)', async () => {
    const mutate = vi.fn()
    promoHooks.useUpdatePromo.mockReturnValue({ mutate, isPending: false })
    setPromosResponse([mkPromo({ id: 'pr_x', status: 'active', title: 'Тест' })])
    const user = userEvent.setup()
    renderPromo()
    await user.click(screen.getByRole('button', { name: /Поставить на паузу/ }))
    expect(mutate).toHaveBeenCalledTimes(1)
    // Остались на списке — навигации не было
    expect(screen.getByTestId('location-probe')).toHaveTextContent(/^\/promo$/)
  })
})

describe('PromoPage — Create modal (товарная акция)', () => {
  it('клик «Новая кампания» открывает модалку с пикером товара', async () => {
    const user = userEvent.setup()
    renderPromo()
    const buttons = screen.getAllByRole('button', { name: /Новая кампания/ })
    await user.click(buttons[0])
    expect(screen.getByPlaceholderText(/Поиск товара/i)).toBeInTheDocument()
  })

  it('«Создать черновик» disabled пока не выбран товар', async () => {
    const user = userEvent.setup()
    renderPromo()
    await user.click(screen.getAllByRole('button', { name: /Новая кампания/ })[0])
    expect(screen.getByRole('button', { name: /Создать черновик/ })).toBeDisabled()
    // выбираем товар из выдачи витрины — title авто-заполнится названием
    await user.click(screen.getByText('Панкраген 0,2г капс. №60'))
    expect(screen.getByRole('button', { name: /Создать черновик/ })).toBeEnabled()
  })

  it('submit вызывает useCreatePromo.mutateAsync с товаром и статусом draft', async () => {
    const mutateAsync = vi.fn().mockResolvedValue(mkPromo())
    promoHooks.useCreatePromo.mockReturnValue({ mutateAsync, isPending: false })
    const user = userEvent.setup()
    renderPromo()
    await user.click(screen.getAllByRole('button', { name: /Новая кампания/ })[0])
    await user.click(screen.getByText('Панкраген 0,2г капс. №60'))
    await user.click(screen.getByRole('button', { name: /Создать черновик/ }))
    expect(mutateAsync).toHaveBeenCalledWith(
      expect.objectContaining({
        medusaProductId: 'prod_1',
        title: 'Панкраген 0,2г капс. №60',
        status: 'draft',
      }),
    )
  })
})
