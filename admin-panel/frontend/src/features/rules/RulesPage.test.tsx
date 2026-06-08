// Тесты RulesPage — поведение на пустом + полном списке + create flow.
// Мокаем @/lib/queries/{rules,catalog} напрямую (vi.mock). Это даёт детерминированные данные
// без MSW. QueryClientProvider обязателен (внутри хуки TanStack Query).

import { describe, expect, it, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { ToastHost } from '@/ui'
import type { ProductDto, RuleDto } from '@/lib/api-types'
import RulesPage from './RulesPage'

// ── Мокаем хуки api-слоя ──────────────────────────────────────────────────
const rulesHooks = vi.hoisted(() => ({
  useRules: vi.fn(),
  useRule: vi.fn(),
  useCreateRule: vi.fn(),
  useUpdateRule: vi.fn(),
  useArchiveRule: vi.fn(),
  useDuplicateRule: vi.fn(),
}))

const catalogHooks = vi.hoisted(() => ({
  useProducts: vi.fn(),
  useBrands: vi.fn(),
  useMnnGroups: vi.fn(),
  useProductLookup: vi.fn(),
  buildProductIndex: vi.fn(),
}))

vi.mock('@/lib/queries/rules', () => rulesHooks)
vi.mock('@/lib/queries/catalog', () => catalogHooks)

// ── Helpers ──────────────────────────────────────────────────────────────
function mkProduct(over: Partial<ProductDto> = {}): ProductDto {
  return {
    id: 'p_x',
    name: 'Test product',
    brand: 'Brand',
    vendor: 'V',
    mnn: 'MNN',
    price: 100,
    ...over,
  }
}

function mkRule(over: Partial<RuleDto> = {}): RuleDto {
  return {
    id: 'r_1',
    type: 'substitution',
    status: 'active',
    trigger: { kind: 'product', value: 'p_aql' },
    recommend: 'p_aqm',
    bonus: 520,
    script: '',
    advantages: [],
    abTest: null,
    pharmacies: 0,
    impressions: 0,
    accepts: 0,
    convRate: 0,
    revenue: 0,
    payout: 0,
    createdBy: '',
    createdAt: '2026-05-01T00:00:00Z',
    updatedAt: '2026-05-01T00:00:00Z',
    ...over,
  }
}

function setRulesResponse(rules: RuleDto[] = []) {
  rulesHooks.useRules.mockReturnValue({
    data: rules,
    isLoading: false,
    isError: false,
    refetch: vi.fn(),
  })
}

function setProductsResponse(products: ProductDto[] = []) {
  catalogHooks.useProducts.mockReturnValue({ data: products, isLoading: false, isError: false })
  catalogHooks.useProductLookup.mockReturnValue((id: string | undefined) =>
    products.find((p) => p.id === id),
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  setRulesResponse([])
  setProductsResponse([])
  rulesHooks.useCreateRule.mockReturnValue({
    mutateAsync: vi.fn(),
    reset: vi.fn(),
    isPending: false,
  })
  rulesHooks.useUpdateRule.mockReturnValue({ mutate: vi.fn(), isPending: false })
  rulesHooks.useArchiveRule.mockReturnValue({ mutate: vi.fn(), isPending: false })
  rulesHooks.useDuplicateRule.mockReturnValue({ mutate: vi.fn(), isPending: false })
  catalogHooks.useBrands.mockReturnValue({ data: [], isLoading: false })
  catalogHooks.useMnnGroups.mockReturnValue({ data: [], isLoading: false })
})

function renderRules() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <ToastHost>
          <RulesPage />
        </ToastHost>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

// ── Тесты ────────────────────────────────────────────────────────────────

describe('RulesPage — базовый рендер (пустой список)', () => {
  it('показывает H1 «Rules Engine» и подзаголовок', () => {
    renderRules()
    expect(screen.getByRole('heading', { level: 1, name: /Rules Engine/i })).toBeInTheDocument()
    expect(screen.getByText(/Правила замены и кросс-сейл/i)).toBeInTheDocument()
  })

  it('рендерит SummaryBar с 4 секциями (все значения на нуле)', () => {
    renderRules()
    expect(screen.getByText('Активных правил')).toBeInTheDocument()
    expect(screen.getByText('Показано')).toBeInTheDocument()
    expect(screen.getByText('Принято')).toBeInTheDocument()
    expect(screen.getByText('Выплачено фармацевтам')).toBeInTheDocument()
    expect(screen.getByText('из 0')).toBeInTheDocument()
  })

  it('рендерит 3 таба, у каждого count = 0', () => {
    renderRules()
    expect(screen.getByRole('button', { name: /Замены/ })).toHaveTextContent('0')
    expect(screen.getByRole('button', { name: /Кросс-сейл/ })).toHaveTextContent('0')
    expect(screen.getByRole('button', { name: /Архив/ })).toHaveTextContent('0')
  })

  it('на пустом списке показывает Empty state с CTA «Новое правило»', () => {
    renderRules()
    expect(screen.getByText(/Правил пока нет/i)).toBeInTheDocument()
    expect(screen.getByText(/Создайте первое правило/i)).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: /Новое правило/ }).length).toBeGreaterThan(0)
  })

  it('правая панель — Empty state «Выберите правило»', () => {
    renderRules()
    expect(screen.getByText(/Выберите правило/i)).toBeInTheDocument()
  })
})

describe('RulesPage — Bug E regression: переключение таба на пустой', () => {
  it('selectedId сбрасывается когда new tab пуст → builder показывает Empty', async () => {
    // На substitution есть правило, на crosssell — нет.
    setRulesResponse([mkRule({ id: 'r_s1', type: 'substitution', status: 'active' })])
    setProductsResponse([mkProduct({ id: 'p_aql' }), mkProduct({ id: 'p_aqm' })])
    const user = userEvent.setup()
    renderRules()

    // Кликаем на правило — оно выбрано, builder показывает его
    await user.click(screen.getByTestId('rule-row-r_s1'))

    // Переключаемся на пустой crosssell-таб
    await user.click(screen.getByRole('button', { name: /Кросс-сейл/ }))

    // Левый список — Empty state «Правил пока нет»; правый builder — «Выберите правило»
    expect(screen.getByText(/Выберите правило/i)).toBeInTheDocument()
    // Не должно быть rule-builder-panel с заполненной информацией о r_s1
    expect(screen.queryByText(/Замена · r_s1/i)).not.toBeInTheDocument()
  })
})

describe('RulesPage — табы и фильтры (пустой список)', () => {
  it('переключение между Замены / Кросс-сейл / Архив — каждый показывает Empty', async () => {
    const user = userEvent.setup()
    renderRules()
    await user.click(screen.getByRole('button', { name: /Кросс-сейл/ }))
    expect(screen.getByText(/Правил пока нет/i)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /Архив/ }))
    expect(screen.getByText(/Здесь окажутся правила, отправленные в архив/i)).toBeInTheDocument()
  })

  it('в архиве status-filter скрыт', async () => {
    const user = userEvent.setup()
    renderRules()
    expect(screen.getByDisplayValue('Все статусы')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /Архив/ }))
    expect(screen.queryByDisplayValue('Все статусы')).not.toBeInTheDocument()
  })
})

describe('RulesPage — загрузка / ошибка состояний', () => {
  it('isLoading → показывает loading state', () => {
    rulesHooks.useRules.mockReturnValue({
      data: undefined,
      isLoading: true,
      isError: false,
      refetch: vi.fn(),
    })
    renderRules()
    expect(screen.getByText(/Загружаем правила…/i)).toBeInTheDocument()
  })

  it('isError → показывает ошибку + кнопку «Повторить»', async () => {
    const refetch = vi.fn()
    rulesHooks.useRules.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: true,
      refetch,
    })
    const user = userEvent.setup()
    renderRules()
    expect(screen.getByText(/Не удалось загрузить правила/i)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /Повторить/ }))
    expect(refetch).toHaveBeenCalled()
  })
})

describe('RulesPage — отображение списка', () => {
  it('substitution-правила появляются в табе «Замены» с count=1', () => {
    setRulesResponse([mkRule({ id: 'r_001', type: 'substitution', status: 'active' })])
    setProductsResponse([
      mkProduct({ id: 'p_aql', name: 'Аквалор' }),
      mkProduct({ id: 'p_aqm', name: 'Аквамарис' }),
    ])
    renderRules()
    expect(screen.getByTestId('rule-row-r_001')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Замены/ })).toHaveTextContent('1')
  })

  it('archived правила уходят в архивный таб', async () => {
    setRulesResponse([mkRule({ id: 'r_x', status: 'archived', type: 'substitution' })])
    setProductsResponse([mkProduct({ id: 'p_aql' }), mkProduct({ id: 'p_aqm' })])
    const user = userEvent.setup()
    renderRules()
    expect(screen.getByRole('button', { name: /Архив/ })).toHaveTextContent('1')
    await user.click(screen.getByRole('button', { name: /Архив/ }))
    expect(screen.getByTestId('rule-row-r_x')).toBeInTheDocument()
  })

  it('mixed: 2 subst + 1 cross + 1 archive → корректные counts', () => {
    setRulesResponse([
      mkRule({ id: 'r_s1', type: 'substitution' }),
      mkRule({ id: 'r_s2', type: 'substitution', status: 'paused' }),
      mkRule({ id: 'r_c1', type: 'crosssell' }),
      mkRule({ id: 'r_a1', type: 'substitution', status: 'archived' }),
    ])
    setProductsResponse([mkProduct({ id: 'p_aql' }), mkProduct({ id: 'p_aqm' })])
    renderRules()
    expect(screen.getByRole('button', { name: /Замены/ })).toHaveTextContent('2')
    expect(screen.getByRole('button', { name: /Кросс-сейл/ })).toHaveTextContent('1')
    expect(screen.getByRole('button', { name: /Архив/ })).toHaveTextContent('1')
  })
})

describe('RulesPage — Create rule modal', () => {
  it('клик «Новое правило» открывает 3-step wizard', async () => {
    const user = userEvent.setup()
    renderRules()
    const buttons = screen.getAllByRole('button', { name: /Новое правило/ })
    await user.click(buttons[0])
    expect(screen.getByText(/Шаг за шагом/i)).toBeInTheDocument()
    expect(screen.getByText('Тип правила')).toBeInTheDocument()
  })

  it('закрытие wizard через Esc', async () => {
    const user = userEvent.setup()
    renderRules()
    const buttons = screen.getAllByRole('button', { name: /Новое правило/ })
    await user.click(buttons[0])
    await waitFor(() => expect(screen.getByText(/Шаг за шагом/i)).toBeInTheDocument())
    await user.keyboard('{Escape}')
    expect(screen.queryByText(/Шаг за шагом/i)).not.toBeInTheDocument()
  })
})

describe('RulesPage — mutation actions через row-action menu', () => {
  it('duplicate из ⋯-меню вызывает useDuplicateRule.mutate с id', async () => {
    const dupMutate = vi.fn((id: string, opts: { onSuccess?: (r: unknown) => void }) => {
      opts?.onSuccess?.(mkRule({ id: `${id}_copy`, status: 'draft' }))
    })
    rulesHooks.useDuplicateRule.mockReturnValue({ mutate: dupMutate, isPending: false })

    setRulesResponse([mkRule({ id: 'r_001', type: 'substitution', status: 'active' })])
    setProductsResponse([mkProduct({ id: 'p_aql' }), mkProduct({ id: 'p_aqm' })])

    const user = userEvent.setup()
    renderRules()
    await user.click(screen.getByTestId('row-menu-trigger-r_001'))
    await user.click(screen.getByRole('menuitem', { name: /Дублировать/ }))
    expect(dupMutate).toHaveBeenCalledWith('r_001', expect.any(Object))
  })

  it('archive из ⋯-меню → открывает confirm-modal', async () => {
    setRulesResponse([mkRule({ id: 'r_001', type: 'substitution', status: 'active' })])
    setProductsResponse([mkProduct({ id: 'p_aql' }), mkProduct({ id: 'p_aqm' })])

    const user = userEvent.setup()
    renderRules()
    await user.click(screen.getByTestId('row-menu-trigger-r_001'))
    await user.click(screen.getByRole('menuitem', { name: /В архив/ }))
    expect(screen.getByText(/Отправить правило в архив\?/i)).toBeInTheDocument()
  })

  it('подтверждение confirm-modal вызывает useArchiveRule.mutate', async () => {
    const archMutate = vi.fn((_id, opts: { onSuccess?: () => void }) => opts?.onSuccess?.())
    rulesHooks.useArchiveRule.mockReturnValue({ mutate: archMutate, isPending: false })

    setRulesResponse([mkRule({ id: 'r_001', type: 'substitution', status: 'active' })])
    setProductsResponse([mkProduct({ id: 'p_aql' }), mkProduct({ id: 'p_aqm' })])

    const user = userEvent.setup()
    renderRules()
    await user.click(screen.getByTestId('row-menu-trigger-r_001'))
    await user.click(screen.getByRole('menuitem', { name: /В архив/ }))
    // Кнопка В архив внутри Modal (вторая в DOM — первая в row-menu)
    const archiveButtons = screen.getAllByRole('button', { name: /В архив/ })
    await user.click(archiveButtons[archiveButtons.length - 1])
    expect(archMutate).toHaveBeenCalledWith('r_001', expect.any(Object))
  })
})
