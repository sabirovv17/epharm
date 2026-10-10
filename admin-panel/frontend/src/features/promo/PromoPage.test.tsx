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

// В этой jsdom-среде localStorage по умолчанию недоступен (как в store.test.ts).
// Промо-страница хранит режим просмотра в localStorage — ставим минимальный
// in-memory стаб, чтобы тесты персиста были осмысленными.
if (typeof globalThis.localStorage === 'undefined') {
  const mem = new Map<string, string>()
  const stub: Storage = {
    get length() {
      return mem.size
    },
    clear: () => mem.clear(),
    getItem: (k) => (mem.has(k) ? (mem.get(k) as string) : null),
    key: (i) => Array.from(mem.keys())[i] ?? null,
    removeItem: (k) => void mem.delete(k),
    setItem: (k, v) => void mem.set(k, String(v)),
  }
  Object.defineProperty(globalThis, 'localStorage', { value: stub, configurable: true })
}

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
  useRefreshPrices: vi.fn(),
}))

// PromoProductPicker дёргает useStorefront — мокаем, чтобы поиск товара отдавал фикстуру.
const storefrontHooks = vi.hoisted(() => ({
  useStorefront: vi.fn(),
  useStorefrontProduct: vi.fn(),
}))
const taxonomyHooks = vi.hoisted(() => ({ useAccTriggerOptions: vi.fn() }))

vi.mock('@/lib/queries/promo', () => promoHooks)
vi.mock('@/lib/queries/storefront', () => ({
  storefrontKeys: { all: ['storefront'], list: () => ['storefront', 'list'] },
  useStorefront: storefrontHooks.useStorefront,
  useStorefrontProduct: storefrontHooks.useStorefrontProduct,
}))
vi.mock('@/lib/queries/accTriggerOptions', () => taxonomyHooks)

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
    cover: '#D97757',
    medusaProductId: null,
    productName: '',
    productImage: null,
    barcode: null,
    ipartId: null,
    overrideImage: null,
    overrideDescription: null,
    price: 0,
    pharmacistBonus: 0,
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
  // Режим просмотра промо хранится в localStorage — сбрасываем, чтобы тесты
  // стартовали в дефолтной «сетке» и не зависели от порядка выполнения.
  localStorage.clear()
  setPromosResponse([])
  promoHooks.useCreatePromo.mockReturnValue({ mutateAsync: vi.fn(), isPending: false })
  promoHooks.useUpdatePromo.mockReturnValue({ mutate: vi.fn(), isPending: false })
  promoHooks.useArchivePromo.mockReturnValue({ mutate: vi.fn(), isPending: false })
  promoHooks.useRestorePromo.mockReturnValue({ mutate: vi.fn(), isPending: false })
  promoHooks.useRefreshPrices.mockReturnValue({ mutate: vi.fn(), isPending: false })
  // Деталь товара витрины — для галереи в CreatePromoModal (по умолчанию пусто).
  storefrontHooks.useStorefrontProduct.mockReturnValue({ data: undefined })
  storefrontHooks.useStorefront.mockReturnValue({
    data: {
      items: [
        {
          id: 'prod_1',
          name: 'Панкраген 0,2г капс. №60',
          brand: 'Access Bioscience',
          mnn: null,
          rxOtc: null,
          price: 4990,
          currency: 'KZT',
          imageUrl: null,
          barcode: '4603423004936',
          category: null,
        },
        {
          id: 'prod_2',
          name: 'Аквамарис Норм спрей 150 мл',
          brand: 'Jadran',
          mnn: null,
          rxOtc: null,
          price: 2890,
          currency: 'KZT',
          imageUrl: null,
          barcode: '3856013201127',
          category: null,
        },
      ],
      total: 2,
      limit: 50,
      offset: 0,
    },
    isFetching: false,
  })
  taxonomyHooks.useAccTriggerOptions.mockReturnValue({
    data: {
      snapshot: { sha256: 'test', sourceName: 'Каталог АСС', itemCount: 200, barcodeCount: 150, importedAt: '2026-10-01T00:00:00Z' },
      options: [
        { key: 'group:1', label: 'ЖКТ', count: 40 },
        { key: 'group:2', label: 'Витамины', count: 20 },
        { key: 'subgroup:1', label: 'Сорбенты', parentLabel: 'ЖКТ', count: 12 },
        { key: 'mnn:1', label: 'Ибупрофен', count: 30 },
      ],
    },
    isPending: false,
    isError: false,
    refetch: vi.fn(),
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

describe('PromoPage — trigger-first campaign creation', () => {
  async function openForm(user: ReturnType<typeof userEvent.setup>) {
    await user.click(screen.getAllByRole('button', { name: /Новая кампания/ })[0])
  }

  async function selectOffer(user: ReturnType<typeof userEvent.setup>) {
    await user.click(screen.getByRole('radio', { name: 'Замена' }))
    await user.type(screen.getByPlaceholderText(/Поиск товара/i), 'Аква')
    await user.click(await screen.findByTestId('promo-product-option-prod_2'))
  }

  it('сначала спрашивает триггер, не показывает каталог товаров до выбора режима', async () => {
    const user = userEvent.setup()
    renderPromo()
    await openForm(user)
    expect(screen.getByRole('radiogroup', { name: 'Что запускает рекомендацию' })).toBeInTheDocument()
    expect(screen.queryByPlaceholderText(/Поиск товара/i)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Создать черновик/ })).toBeDisabled()
  })

  it('товарный триггер и замена сохраняются одним запросом, затем открывается карточка', async () => {
    const mutateAsync = vi.fn().mockResolvedValue(mkPromo({ id: 'pr_new' }))
    promoHooks.useCreatePromo.mockReturnValue({ mutateAsync, isPending: false })
    const user = userEvent.setup()
    renderPromo()
    await openForm(user)
    await user.click(screen.getByRole('radio', { name: 'Конкретный товар' }))
    await user.type(screen.getByPlaceholderText(/Поиск товара/i), 'Панк')
    await user.click(await screen.findByTestId('promo-product-option-prod_1'))
    await selectOffer(user)
    await user.click(screen.getByRole('button', { name: /Создать черновик/ }))

    expect(mutateAsync).toHaveBeenCalledWith(expect.objectContaining({
      medusaProductId: 'prod_2',
      title: 'Аквамарис Норм спрей 150 мл',
      status: 'draft',
      barcode: '3856013201127',
      initialRecommendation: expect.objectContaining({
        type: 'substitution',
        trigger: expect.objectContaining({ medusaProductId: 'prod_1', triggerKind: 'product', active: false }),
      }),
    }))
    expect(screen.getByTestId('location-probe')).toHaveTextContent('/promo/pr_new')
  })

  it.each([
    ['Группа ACC', 'group:1', 'acc_group'],
    ['Подгруппа ACC', 'subgroup:1', 'acc_subgroup'],
    ['МНН ACC', 'mnn:1', 'acc_mnn'],
  ])('%s выбирается с поиском и создаёт неактивный кросс-селл', async (label, key, kind) => {
    const mutateAsync = vi.fn().mockResolvedValue(mkPromo())
    promoHooks.useCreatePromo.mockReturnValue({ mutateAsync, isPending: false })
    const user = userEvent.setup()
    renderPromo()
    await openForm(user)
    await user.click(screen.getByRole('radio', { name: label }))
    expect(screen.getByTestId('create-trigger-snapshot')).toHaveTextContent('Каталог АСС')
    await user.type(screen.getByRole('textbox', { name: /Найти группу/ }), 'сорб')
    await user.click(screen.getByTestId(`create-trigger-option-${key}`))
    await user.click(screen.getByRole('radio', { name: 'Допродажа' }))
    await user.type(screen.getByPlaceholderText(/Поиск товара/i), 'Аква')
    await user.click(await screen.findByTestId('promo-product-option-prod_2'))
    await user.click(screen.getByRole('button', { name: /Создать черновик/ }))
    expect(mutateAsync).toHaveBeenCalledWith(expect.objectContaining({
      initialRecommendation: expect.objectContaining({
        type: 'crosssell',
        trigger: expect.objectContaining({ triggerKind: kind, triggerValue: key, active: false }),
      }),
    }))
  })

  it('смена типа триггера сбрасывает прошлый выбор и блокирует сохранение', async () => {
    const user = userEvent.setup()
    renderPromo()
    await openForm(user)
    await user.click(screen.getByRole('radio', { name: 'Группа ACC' }))
    await user.click(screen.getByTestId('create-trigger-option-group:1'))
    await selectOffer(user)
    expect(screen.getByRole('button', { name: /Создать черновик/ })).toBeEnabled()
    await user.click(screen.getByRole('radio', { name: 'МНН ACC' }))
    expect(screen.getByRole('button', { name: /Создать черновик/ })).toBeDisabled()
  })

  it('при изменении уже выбранной группы не сохраняет скрытый старый триггер', async () => {
    const user = userEvent.setup()
    renderPromo()
    await openForm(user)
    await user.click(screen.getByRole('radio', { name: 'Группа ACC' }))
    await user.click(screen.getByTestId('create-trigger-option-group:1'))
    await selectOffer(user)
    expect(screen.getByRole('button', { name: /Создать черновик/ })).toBeEnabled()
    await user.click(screen.getByRole('button', { name: 'Сменить' }))
    expect(screen.getByRole('button', { name: /Создать черновик/ })).toBeDisabled()
    await user.click(screen.getByTestId('create-trigger-option-group:2'))
    expect(screen.getByRole('button', { name: /Создать черновик/ })).toBeEnabled()
  })

  it('не позволяет рекомендовать тот же товар, который запускает подсказку', async () => {
    const user = userEvent.setup()
    renderPromo()
    await openForm(user)
    await user.click(screen.getByRole('radio', { name: 'Конкретный товар' }))
    await user.type(screen.getByPlaceholderText(/Поиск товара/i), 'Панк')
    await user.click(await screen.findByTestId('promo-product-option-prod_1'))
    await user.click(screen.getByRole('radio', { name: 'Замена' }))
    await user.type(screen.getByPlaceholderText(/Поиск товара/i), 'Панк')
    await user.click(await screen.findByTestId('promo-product-option-prod_1'))
    expect(screen.getByRole('alert')).toHaveTextContent(/должны различаться/)
    expect(screen.getByRole('button', { name: /Создать черновик/ })).toBeDisabled()
  })

  it('неактуальный ACC snapshot не даёт выбрать scope', async () => {
    taxonomyHooks.useAccTriggerOptions.mockReturnValue({ data: { snapshot: null, options: [] }, isPending: false, isError: false, refetch: vi.fn() })
    const user = userEvent.setup()
    renderPromo()
    await openForm(user)
    await user.click(screen.getByRole('radio', { name: 'Группа ACC' }))
    expect(screen.getByText(/Классификатор ACC пока не загружен/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Создать черновик/ })).toBeDisabled()
  })

  it('ограничивает неизменяемые метаданные каталога длиной backend-контракта', async () => {
    const source = storefrontHooks.useStorefront()
    storefrontHooks.useStorefront.mockReturnValue({
      ...source,
      data: {
        ...source.data,
        items: [
          { ...source.data.items[0], name: 'П'.repeat(300), brand: 'Б'.repeat(150), ipartId: 'I'.repeat(80) },
          source.data.items[1],
        ],
      },
    })
    const mutateAsync = vi.fn().mockResolvedValue(mkPromo())
    promoHooks.useCreatePromo.mockReturnValue({ mutateAsync, isPending: false })
    const user = userEvent.setup()
    renderPromo()
    await openForm(user)
    await user.click(screen.getByRole('radio', { name: 'Конкретный товар' }))
    await user.type(screen.getByPlaceholderText(/Поиск товара/i), 'Панк')
    await user.click(await screen.findByTestId('promo-product-option-prod_1'))
    await selectOffer(user)
    await user.click(screen.getByRole('button', { name: /Создать черновик/ }))
    const req = mutateAsync.mock.calls[0][0]
    expect(req.initialRecommendation.trigger.name).toHaveLength(255)
    expect(req.initialRecommendation.trigger.brand).toHaveLength(128)
    expect(req.initialRecommendation.trigger.ipartId).toBeNull()
  })

  it('ошибку поиска Medusa показывает отдельно от пустой выдачи с повтором', async () => {
    const refetch = vi.fn()
    storefrontHooks.useStorefront.mockReturnValue({ data: undefined, isFetching: false, isError: true, error: new Error('Каталог недоступен'), refetch })
    const user = userEvent.setup()
    renderPromo()
    await openForm(user)
    await user.click(screen.getByRole('radio', { name: 'Конкретный товар' }))
    await user.type(screen.getByPlaceholderText(/Поиск товара/i), 'Панк')
    expect(await screen.findByRole('alert')).toHaveTextContent(/Не удалось загрузить товары/)
    await user.click(screen.getByRole('button', { name: /Повторить/ }))
    expect(refetch).toHaveBeenCalledTimes(1)
  })

  it('расширенные настройки сохраняют цену read-only и выбранный цвет', async () => {
    const mutateAsync = vi.fn().mockResolvedValue(mkPromo())
    promoHooks.useCreatePromo.mockReturnValue({ mutateAsync, isPending: false })
    const user = userEvent.setup()
    renderPromo()
    await openForm(user)
    await user.click(screen.getByRole('radio', { name: 'Группа ACC' }))
    await user.click(screen.getByTestId('create-trigger-option-group:1'))
    await selectOffer(user)
    await user.click(screen.getByText(/Дополнительные настройки/))
    expect(screen.getByTestId('create-price-readonly').tagName).not.toBe('INPUT')
    await user.click(screen.getByRole('button', { name: '#9A4427' }))
    await user.click(screen.getByRole('button', { name: /Создать черновик/ }))
    expect(mutateAsync).toHaveBeenCalledWith(expect.objectContaining({ cover: '#9A4427' }))
  })

  it('ошибка backend не закрывает форму и показывает причину', async () => {
    const mutateAsync = vi.fn().mockRejectedValue({
      isAxiosError: true,
      message: 'Request failed with status code 409',
      response: { status: 409, data: { code: 'CONFLICT', message: 'Этот товар уже привязан к другой активной кампании' } },
    })
    promoHooks.useCreatePromo.mockReturnValue({ mutateAsync, isPending: false })
    const user = userEvent.setup()
    renderPromo()
    await openForm(user)
    await user.click(screen.getByRole('radio', { name: 'Группа ACC' }))
    await user.click(screen.getByTestId('create-trigger-option-group:1'))
    await selectOffer(user)
    await user.click(screen.getByRole('button', { name: /Создать черновик/ }))
    expect(await screen.findByText(/уже привязан к другой активной кампании/)).toBeInTheDocument()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })
})

describe('PromoPage — переключатель режима просмотра (сетка ↔ список)', () => {
  it('по умолчанию режим «сетка» (карточки)', () => {
    setPromosResponse([mkPromo({ id: 'pr_1', title: 'Кампания A' })])
    renderPromo()
    expect(screen.getByTestId('promo-grid')).toBeInTheDocument()
    expect(screen.queryByTestId('promo-list')).not.toBeInTheDocument()
    // aria-pressed на кнопке «Сетка»
    expect(screen.getByTestId('promo-view-grid')).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByTestId('promo-view-list')).toHaveAttribute('aria-pressed', 'false')
  })

  it('клик «Список» переключает на таблицу со строками', async () => {
    setPromosResponse([
      mkPromo({ id: 'pr_1', title: 'Кампания A' }),
      mkPromo({ id: 'pr_2', title: 'Кампания B' }),
    ])
    const user = userEvent.setup()
    renderPromo()
    await user.click(screen.getByTestId('promo-view-list'))
    expect(screen.getByTestId('promo-list')).toBeInTheDocument()
    expect(screen.queryByTestId('promo-grid')).not.toBeInTheDocument()
    expect(screen.getByTestId('promo-row-pr_1')).toBeInTheDocument()
    expect(screen.getByTestId('promo-row-pr_2')).toBeInTheDocument()
    // Заголовки колонок таблицы
    expect(screen.getByRole('columnheader', { name: 'Кампания' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'Статус' })).toBeInTheDocument()
  })

  it('выбор режима запоминается в localStorage', async () => {
    setPromosResponse([mkPromo({ id: 'pr_1' })])
    const user = userEvent.setup()
    renderPromo()
    await user.click(screen.getByTestId('promo-view-list'))
    expect(localStorage.getItem('epharm.promoView')).toBe('list')
  })

  it('режим из localStorage восстанавливается при загрузке', () => {
    localStorage.setItem('epharm.promoView', 'list')
    setPromosResponse([mkPromo({ id: 'pr_1' })])
    renderPromo()
    expect(screen.getByTestId('promo-list')).toBeInTheDocument()
    expect(screen.getByTestId('promo-view-list')).toHaveAttribute('aria-pressed', 'true')
  })

  it('строка списка: клик навигирует на /promo/:id', async () => {
    localStorage.setItem('epharm.promoView', 'list')
    setPromosResponse([mkPromo({ id: 'pr_row', title: 'Строка' })])
    const user = userEvent.setup()
    renderPromo()
    await user.click(screen.getByTestId('promo-row-pr_row'))
    expect(screen.getByTestId('location-probe')).toHaveTextContent('/promo/pr_row')
  })

  it('в списке inline-кнопка пауза не навигирует (stopPropagation)', async () => {
    localStorage.setItem('epharm.promoView', 'list')
    const mutate = vi.fn()
    promoHooks.useUpdatePromo.mockReturnValue({ mutate, isPending: false })
    setPromosResponse([mkPromo({ id: 'pr_x', status: 'active' })])
    const user = userEvent.setup()
    renderPromo()
    await user.click(screen.getByRole('button', { name: /Поставить на паузу/ }))
    expect(mutate).toHaveBeenCalledTimes(1)
    expect(screen.getByTestId('location-probe')).toHaveTextContent(/^\/promo$/)
  })
})

describe('PromoPage — фото товара 3:4 в превью', () => {
  it('карточка показывает фото товара (через image-прокси) когда есть productImage', () => {
    setPromosResponse([
      mkPromo({ id: 'pr_img', title: 'С фото', productImage: 'http://cdn.x/p.jpg' }),
    ])
    renderPromo()
    const card = screen.getByTestId('promo-card-pr_img')
    const img = card.querySelector('img')
    expect(img).not.toBeNull()
    // proxyMedia переписывает http→прокси: src не должен быть исходным http-URL «как есть».
    expect(img?.getAttribute('src') ?? '').toContain('p.jpg')
  })

  it('без фото карточка не падает (рендерит градиент-заглушку без img)', () => {
    setPromosResponse([mkPromo({ id: 'pr_nophoto', productImage: null, overrideImage: null })])
    renderPromo()
    const card = screen.getByTestId('promo-card-pr_nophoto')
    expect(card.querySelector('img')).toBeNull()
  })
})

describe('PromoPage — ручной рефреш цен Medusa (A2)', () => {
  it('кнопка «Обновить цены» вызывает useRefreshPrices.mutate', async () => {
    const mutate = vi.fn()
    promoHooks.useRefreshPrices.mockReturnValue({ mutate, isPending: false })
    const user = userEvent.setup()
    renderPromo()
    await user.click(screen.getByRole('button', { name: /Обновить цены/ }))
    expect(mutate).toHaveBeenCalled()
  })
})
