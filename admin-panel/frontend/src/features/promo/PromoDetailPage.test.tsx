// Тесты PromoDetailPage — полная страница кампании /promo/:id.
// Редактирование полей (PATCH), статус-действия, cover-overlay, archived read-only.

import { describe, expect, it, beforeEach, vi } from 'vitest'
import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { ToastHost } from '@/ui'
import type { PromoChangesViewDto, PromoDto } from '@/lib/api-types'
import PromoDetailPage from './PromoDetailPage'
import { AxiosError } from 'axios'

const promoHooks = vi.hoisted(() => ({
  usePromo: vi.fn(),
  useUpdatePromo: vi.fn(),
  useArchivePromo: vi.fn(),
  useRestorePromo: vi.fn(),
  // не используются страницей, но модуль экспортит — заглушим на всякий случай
  usePromos: vi.fn(),
  useCreatePromo: vi.fn(),
}))
const storefrontHooks = vi.hoisted(() => ({
  useStorefront: vi.fn(),
  useStorefrontProduct: vi.fn(),
}))
const promoRulesHooks = vi.hoisted(() => ({
  usePromoRules: vi.fn(),
  useSavePromoRules: vi.fn(),
}))
const changesHooks = vi.hoisted(() => ({
  usePromoChanges: vi.fn(),
  useSavePromoChanges: vi.fn(),
  useApplyPromoChanges: vi.fn(),
}))
vi.mock('@/lib/queries/promoChanges', () => changesHooks)

vi.mock('@/lib/queries/promo', () => promoHooks)
vi.mock('@/lib/queries/storefront', () => ({
  storefrontKeys: {
    all: ['storefront'],
    list: () => ['storefront', 'list'],
    detail: (id: string) => ['storefront', 'detail', id],
  },
  useStorefront: storefrontHooks.useStorefront,
  useStorefrontProduct: storefrontHooks.useStorefrontProduct,
}))
vi.mock('@/lib/queries/promoRules', () => ({
  promoRulesKeys: { all: ['promo-rules'], detail: (id: string) => ['promo-rules', id] },
  usePromoRules: promoRulesHooks.usePromoRules,
  useSavePromoRules: promoRulesHooks.useSavePromoRules,
}))

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
  changesHooks.usePromoChanges.mockReturnValue({
    data: data ? changesFor(data) : undefined,
    isFetchedAfterMount: true,
    isFetching: false,
    isError: false,
    error: null,
    refetch: vi.fn(),
  })
}

function changesFor(
  promo = mkPromo(),
  patch: Partial<PromoChangesViewDto> = {},
): PromoChangesViewDto {
  return {
    promoId: promo.id,
    revision: 4,
    hasPendingChanges: false,
    conflict: false,
    canSave: true,
    canApply: false,
    readinessIssues: [],
    updatedAt: null,
    updatedBy: null,
    bundle: {
      campaign: {
        title: promo.title,
        brand: promo.brand,
        period: promo.period,
        budget: promo.budget,
        kpi: promo.kpi,
        cover: promo.cover,
        barcode: promo.barcode,
        barcodes: promo.barcodes,
        ipartId: promo.ipartId,
        overrideImage: promo.overrideImage,
        overrideDescription: promo.overrideDescription,
        overrideCharacteristics: promo.overrideCharacteristics,
        pharmacistBonus: promo.pharmacistBonus,
        dateStart: promo.dateStart,
        dateEnd: promo.dateEnd,
      },
      rules: {
        replacements: [],
        crossSells: [],
        script: '',
        advantages: [],
        partnerLabel: null,
        comparison: [],
        goalLabel: null,
        goalTarget: null,
        goalBonus: null,
      },
    },
    ...patch,
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  setPromo(mkPromo())
  promoHooks.useUpdatePromo.mockReturnValue({ mutate: vi.fn(), isPending: false })
  promoHooks.useArchivePromo.mockReturnValue({ mutate: vi.fn(), isPending: false })
  promoHooks.useRestorePromo.mockReturnValue({ mutate: vi.fn(), isPending: false })
  changesHooks.useSavePromoChanges.mockReturnValue({ mutate: vi.fn(), isPending: false })
  changesHooks.useApplyPromoChanges.mockReturnValue({ mutate: vi.fn(), isPending: false })
  storefrontHooks.useStorefront.mockReturnValue({
    data: { items: [], total: 0, limit: 50, offset: 0 },
    isFetching: false,
  })
  storefrontHooks.useStorefrontProduct.mockReturnValue({ data: undefined })
  promoRulesHooks.usePromoRules.mockReturnValue({
    data: {
      promoId: 'pr_1',
      config: {
        replacements: [],
        crossSells: [],
        script: '',
        advantages: [],
        partnerLabel: null,
        comparison: [],
        goalLabel: null,
        goalTarget: null,
        goalBonus: null,
      },
      ruleCount: 0,
      activeCount: 0,
    },
    isLoading: false,
    isError: false,
    error: null,
    refetch: vi.fn(),
  })
  promoRulesHooks.useSavePromoRules.mockReturnValue({ mutate: vi.fn(), isPending: false })
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
  it.each(['prod_multi', null])(
    'обычное редактирование кампании %s сохраняет все штрихкоды из ответа сервера',
    async (medusaProductId) => {
      const mutate = vi.fn()
      changesHooks.useSavePromoChanges.mockReturnValue({ mutate, isPending: false })
      setPromo(
        mkPromo({
          medusaProductId,
          barcode: '0012345678905',
          barcodes: ['0012345678905', '4601164003157'],
        }),
      )
      const user = userEvent.setup()
      renderDetail()
      expect(screen.getByTestId('detail-barcodes')).toHaveTextContent('Штрихкоды: 2')
      await user.type(screen.getByDisplayValue('Майский марафон'), ' новая')
      await user.click(screen.getByTestId('promo-changes-save'))
      expect(mutate).toHaveBeenCalledWith(
        {
          id: 'pr_1',
          expectedRevision: 4,
          bundle: expect.objectContaining({
            campaign: expect.objectContaining({
              barcode: '0012345678905',
              barcodes: ['0012345678905', '4601164003157'],
            }),
          }),
        },
        expect.any(Object),
      )
    },
  )

  it('«Сохранить» disabled пока нет изменений, активна после правки', async () => {
    const user = userEvent.setup()
    renderDetail()
    expect(screen.getByTestId('promo-changes-save')).toBeDisabled()
    await user.type(screen.getByDisplayValue('Майский марафон'), ' 2026')
    expect(screen.getByTestId('promo-changes-save')).toBeEnabled()
  })

  it('Сохранить вызывает useUpdatePromo с патчем', async () => {
    const mutate = vi.fn()
    changesHooks.useSavePromoChanges.mockReturnValue({ mutate, isPending: false })
    const user = userEvent.setup()
    renderDetail()
    const titleInput = screen.getByDisplayValue('Майский марафон')
    await user.clear(titleInput)
    await user.type(titleInput, 'Летняя кампания')
    await user.click(screen.getByTestId('promo-changes-save'))
    expect(mutate).toHaveBeenCalledWith(
      {
        id: 'pr_1',
        expectedRevision: 4,
        bundle: expect.objectContaining({
          campaign: expect.objectContaining({ title: 'Летняя кампания', brand: 'Jadran' }),
        }),
      },
      expect.any(Object),
    )
  })

  it('Medusa: описание/характеристики предзаполнены, изменённые попадают в патч, неизменённые → null', async () => {
    const mutate = vi.fn()
    changesHooks.useSavePromoChanges.mockReturnValue({ mutate, isPending: false })
    promoHooks.usePromo.mockReturnValue({
      data: mkPromo({
        medusaProductId: 'prod_1',
        overrideDescription: null,
        overrideCharacteristics: null,
      }),
      isLoading: false,
      isError: false,
      error: null,
      refetch: vi.fn(),
    })
    storefrontHooks.useStorefrontProduct.mockReturnValue({
      data: { description: 'Описание из Medusa', keyFacts: ['Форма: спрей', 'Объём: 50 мл'] },
    })
    const user = userEvent.setup()
    renderDetail()
    // Предзаполнение из Medusa
    expect(await screen.findByDisplayValue('Описание из Medusa')).toBeInTheDocument()
    const charsField = screen.getByDisplayValue(/Форма: спрей/)
    expect(charsField).toBeInTheDocument()
    // Меняем только характеристики
    await user.clear(charsField)
    await user.type(charsField, 'Своя характеристика')
    await user.click(screen.getByTestId('promo-changes-save'))
    expect(mutate).toHaveBeenCalledWith(
      {
        id: 'pr_1',
        expectedRevision: 4,
        bundle: expect.objectContaining({
          campaign: expect.objectContaining({
            // описание не трогали → совпадает с Medusa → null (сохраняем автоматический фоллбэк)
            overrideDescription: null,
            overrideCharacteristics: 'Своя характеристика',
          }),
        }),
      },
      expect.any(Object),
    )
  })

  it('пустое название → ошибка, mutate не зовётся', async () => {
    const mutate = vi.fn()
    changesHooks.useSavePromoChanges.mockReturnValue({ mutate, isPending: false })
    const user = userEvent.setup()
    renderDetail()
    await user.clear(screen.getByDisplayValue('Майский марафон'))
    await user.click(screen.getByTestId('promo-changes-save'))
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
    expect(screen.queryByTestId('promo-changes-save')).not.toBeInTheDocument()
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

describe('PromoDetailPage — T1 (товар, цена read-only, бонус)', () => {
  it('цена из Medusa показана read-only (нет input для price)', () => {
    setPromo(mkPromo({ price: 4990 }))
    renderDetail()
    const cell = screen.getByTestId('detail-price-readonly')
    expect(cell).toBeInTheDocument()
    // read-only — это div, не input
    expect(cell.tagName).not.toBe('INPUT')
    expect(cell.textContent).toMatch(/4\s?990/)
  })

  it('EAN-13 (штрих-код) товара показан в редактируемом поле', () => {
    setPromo(
      mkPromo({ medusaProductId: 'prod_1', productName: 'Аквалор', barcode: '4603423004936' }),
    )
    renderDetail()
    expect(screen.getByTestId('detail-barcode')).toHaveValue('4603423004936')
  })

  it('бонус фармацевту редактируется и попадает в патч', async () => {
    const mutate = vi.fn()
    changesHooks.useSavePromoChanges.mockReturnValue({ mutate, isPending: false })
    setPromo(mkPromo({ pharmacistBonus: 100 }))
    const user = userEvent.setup()
    renderDetail()
    const bonus = screen.getByDisplayValue('100')
    await user.clear(bonus)
    await user.type(bonus, '250')
    await user.click(screen.getByTestId('promo-changes-save'))
    expect(mutate).toHaveBeenCalledWith(
      {
        id: 'pr_1',
        expectedRevision: 4,
        bundle: expect.objectContaining({
          campaign: expect.objectContaining({ pharmacistBonus: 250 }),
        }),
      },
      expect.any(Object),
    )
    // tiers/price НЕ должны отправляться
    expect(mutate.mock.calls[0][0].bundle.campaign).not.toHaveProperty('tiers')
    expect(mutate.mock.calls[0][0].bundle.campaign).not.toHaveProperty('price')
  })
})

describe('PromoDetailPage — T2 (секция правил)', () => {
  it('рендерит секцию «Замены и кросс-селл» со счётчиками', () => {
    promoRulesHooks.usePromoRules.mockReturnValue({
      data: {
        promoId: 'pr_1',
        config: {
          replacements: [],
          crossSells: [],
          script: '',
          advantages: [],
          partnerLabel: null,
          comparison: [],
          goalLabel: null,
          goalTarget: null,
          goalBonus: null,
        },
        ruleCount: 3,
        activeCount: 2,
      },
      isLoading: false,
      isError: false,
      error: null,
      refetch: vi.fn(),
    })
    renderDetail()
    expect(screen.getByTestId('promo-rules-editor')).toBeInTheDocument()
    expect(screen.getByTestId('promo-rules-counts')).toHaveTextContent(
      'Подготовлено пар: 0 · включено: 0',
    )
  })

  it('сохранение правил подготавливает весь bundle, включая несохранённые условия, без legacy публикации', async () => {
    const mutate = vi.fn()
    changesHooks.useSavePromoChanges.mockReturnValue({ mutate, isPending: false })
    const user = userEvent.setup()
    renderDetail()
    await user.type(screen.getByDisplayValue('Майский марафон'), ' подготовленная')
    await user.click(screen.getByTestId('promo-rules-save'))
    expect(mutate).toHaveBeenCalledWith(
      {
        id: 'pr_1',
        expectedRevision: 4,
        bundle: {
          campaign: expect.objectContaining({ title: 'Майский марафон подготовленная' }),
          rules: expect.objectContaining({ replacements: [], crossSells: [] }),
        },
      },
      expect.any(Object),
    )
    expect(mutate.mock.calls[0][0].bundle.campaign).not.toHaveProperty('status')
    expect(promoHooks.useUpdatePromo().mutate).not.toHaveBeenCalled()
    expect(promoRulesHooks.useSavePromoRules).not.toHaveBeenCalled()
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

describe('D28 — подготовка и явное применение', () => {
  function prepared(promo = mkPromo(), patch: Partial<PromoChangesViewDto> = {}) {
    setPromo(promo)
    const data = changesFor(promo, { hasPendingChanges: true, canApply: true, ...patch })
    const refetch = vi.fn().mockResolvedValue({ data })
    changesHooks.usePromoChanges.mockReturnValue({
      data,
      isFetchedAfterMount: true,
      isFetching: false,
      isError: false,
      error: null,
      refetch,
    })
    return { data, refetch }
  }

  it('проверяет именно сохранённый bundle и применяет только его ревизию отдельным запросом', async () => {
    const { data } = prepared()
    data.bundle.campaign.title = 'Подготовленная кампания'
    const apply = vi.fn()
    changesHooks.useApplyPromoChanges.mockReturnValue({ mutate: apply, isPending: false })
    const user = userEvent.setup()
    renderDetail()
    expect(screen.getByDisplayValue('Подготовленная кампания')).toBeInTheDocument()
    expect(screen.getByTestId('promo-publication-state')).toHaveTextContent(
      'Есть подготовленные изменения',
    )
    await user.click(screen.getByRole('button', { name: 'Проверить и применить' }))
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByText('Подготовленная кампания')).toBeInTheDocument()
    expect(within(dialog).getByText('Вся сеть · 100 аптек')).toBeInTheDocument()
    expect(apply).not.toHaveBeenCalled()
    await user.click(within(dialog).getByRole('button', { name: 'Применить изменения' }))
    expect(apply).toHaveBeenCalledWith({ id: 'pr_1', expectedRevision: 4 }, expect.any(Object))
    expect(changesHooks.useSavePromoChanges().mutate).not.toHaveBeenCalled()
    expect(promoHooks.useUpdatePromo().mutate).not.toHaveBeenCalled()
  })

  it('несохранённая правка условия или правила блокирует применение', async () => {
    prepared()
    const user = userEvent.setup()
    renderDetail()
    await user.type(screen.getByTestId('pr-goal-label'), 'Новая цель')
    expect(screen.getByRole('button', { name: 'Проверить и применить' })).toBeDisabled()
    expect(screen.getByText(/Есть несохранённые поля/)).toBeInTheDocument()
    expect(changesHooks.useApplyPromoChanges().mutate).not.toHaveBeenCalled()
  })

  it('первая публикация требует сохранения bundle, а не PATCH status=active', async () => {
    setPromo(mkPromo({ status: 'draft' }))
    const user = userEvent.setup()
    renderDetail()
    expect(screen.getByRole('button', { name: 'Проверить и запустить' })).toBeDisabled()
    await user.click(screen.getByTestId('promo-changes-save'))
    expect(changesHooks.useSavePromoChanges().mutate).toHaveBeenCalledWith(
      { id: 'pr_1', expectedRevision: 4, bundle: expect.any(Object) },
      expect.any(Object),
    )
    expect(promoHooks.useUpdatePromo().mutate).not.toHaveBeenCalled()
    expect(changesHooks.useApplyPromoChanges().mutate).not.toHaveBeenCalled()
  })

  it('первый запуск использует отдельное подтверждение и сохраняет 0 бонус', async () => {
    prepared(mkPromo({ status: 'draft', pharmacistBonus: 0 }))
    const user = userEvent.setup()
    renderDetail()
    await user.click(screen.getByRole('button', { name: 'Проверить и запустить' }))
    expect(screen.getByRole('dialog')).toHaveTextContent('Бонус основного товара: 0 ₸')
    await user.click(screen.getByRole('button', { name: 'Запустить кампанию' }))
    expect(changesHooks.useApplyPromoChanges().mutate).toHaveBeenCalledWith(
      { id: 'pr_1', expectedRevision: 4 },
      expect.any(Object),
    )
    expect(promoHooks.useUpdatePromo().mutate).not.toHaveBeenCalled()
  })

  it('применение на паузе явно не возобновляет кампанию', async () => {
    prepared(mkPromo({ status: 'paused' }))
    const user = userEvent.setup()
    renderDetail()
    await user.click(screen.getByRole('button', { name: 'Проверить и применить' }))
    expect(screen.getByRole('dialog')).toHaveTextContent('Кампания останется на паузе')
    await user.click(screen.getByRole('button', { name: 'Применить изменения' }))
    expect(promoHooks.useUpdatePromo().mutate).not.toHaveBeenCalled()
  })

  it.each([403, 404])(
    'GET changes %i оставляет опубликованные поля только для чтения и не включает legacy сохранение',
    (status) => {
      const error = new AxiosError('Synthetic', undefined, undefined, undefined, {
        status,
        data: { code: status === 403 ? 'FORBIDDEN' : 'NOT_FOUND', message: 'Недоступно' },
        statusText: '',
        headers: {},
        config: {} as never,
      })
      changesHooks.usePromoChanges.mockReturnValue({
        data: undefined,
        isFetchedAfterMount: true,
        isFetching: false,
        isError: true,
        error,
        refetch: vi.fn(),
      })
      renderDetail()
      expect(screen.getByRole('alert')).toHaveTextContent('Подготовка изменений недоступна')
      expect(screen.getByDisplayValue('Майский марафон')).toBeDisabled()
      expect(screen.getByTestId('promo-changes-save')).toBeDisabled()
      expect(screen.queryByTestId('promo-rules-editor')).not.toBeInTheDocument()
      expect(promoHooks.useUpdatePromo().mutate).not.toHaveBeenCalled()
      expect(promoRulesHooks.useSavePromoRules).not.toHaveBeenCalled()
    },
  )

  it('данные из восстановленного кеша не разрешают применение до нового успешного GET', () => {
    const { data } = prepared()
    changesHooks.usePromoChanges.mockReturnValue({
      data,
      isFetchedAfterMount: false,
      isFetching: true,
      isError: false,
      error: null,
      refetch: vi.fn(),
    })
    renderDetail()
    expect(screen.queryByRole('button', { name: 'Проверить и применить' })).not.toBeInTheDocument()
    expect(screen.getByTestId('promo-changes-save')).toBeDisabled()
  })

  it.each([403, 500])(
    'ошибка %i не показывает оставшийся cached pending bundle или его права',
    (status) => {
      const { data } = prepared()
      data.bundle.campaign.title = 'PRIVATE-CACHED-PENDING'
      changesHooks.usePromoChanges.mockReturnValue({
        data,
        isFetchedAfterMount: true,
        isFetching: false,
        isError: true,
        error: new AxiosError('Synthetic', undefined, undefined, undefined, {
          status,
          data: {},
          statusText: '',
          headers: {},
          config: {} as never,
        }),
        refetch: vi.fn(),
      })
      renderDetail()
      expect(screen.queryByDisplayValue('PRIVATE-CACHED-PENDING')).not.toBeInTheDocument()
      expect(screen.getByDisplayValue('Майский марафон')).toBeDisabled()
      expect(screen.queryByTestId('promo-rules-editor')).not.toBeInTheDocument()
      expect(
        screen.queryByRole('button', { name: 'Проверить и применить' }),
      ).not.toBeInTheDocument()
    },
  )

  it('409 блокирует повторную запись и сохраняет несохранённые поля до явного обновления', async () => {
    const { refetch } = prepared()
    const save = vi.fn()
    changesHooks.useSavePromoChanges.mockReturnValue({ mutate: save, isPending: false })
    const user = userEvent.setup()
    renderDetail()
    await user.type(screen.getByDisplayValue('Майский марафон'), ' моя правка')
    await user.click(screen.getByTestId('promo-changes-save'))
    const error = new AxiosError('Synthetic', undefined, undefined, undefined, {
      status: 409,
      data: { code: 'CONFLICT', message: 'Ревизия изменилась' },
      statusText: '',
      headers: {},
      config: {} as never,
    })
    act(() => save.mock.calls[0][1].onError(error))
    expect(screen.getByDisplayValue('Майский марафон моя правка')).toHaveValue(
      'Майский марафон моя правка',
    )
    expect(screen.getByTestId('promo-changes-save')).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Проверить и применить' })).toBeDisabled()
    expect(refetch).not.toHaveBeenCalled()
    expect(save).toHaveBeenCalledTimes(1)
    vi.spyOn(window, 'confirm').mockReturnValueOnce(false)
    await user.click(screen.getByRole('button', { name: 'Загрузить сохранённую версию' }))
    expect(refetch).not.toHaveBeenCalled()
  })

  it('конфликтный pending отменяется только явным подтверждением без изменения опубликованного', async () => {
    prepared(mkPromo(), { conflict: true, canApply: false })
    const user = userEvent.setup()
    renderDetail()
    expect(screen.getByRole('button', { name: 'Проверить и применить' })).toBeDisabled()
    const confirm = vi.spyOn(window, 'confirm').mockReturnValueOnce(false)
    await user.click(screen.getByRole('button', { name: 'Отменить подготовленные изменения' }))
    expect(changesHooks.useSavePromoChanges().mutate).not.toHaveBeenCalled()
    confirm.mockReturnValueOnce(true)
    await user.click(screen.getByRole('button', { name: 'Отменить подготовленные изменения' }))
    expect(changesHooks.useSavePromoChanges().mutate).toHaveBeenCalledWith(
      { id: 'pr_1', expectedRevision: 4, discard: true },
      expect.any(Object),
    )
    expect(promoHooks.useUpdatePromo().mutate).not.toHaveBeenCalled()
    expect(changesHooks.useApplyPromoChanges().mutate).not.toHaveBeenCalled()
  })
})
