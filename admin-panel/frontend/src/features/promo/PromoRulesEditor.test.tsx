// Тесты редизайна редактора правил (T2 UX): per-pair скрипт + кнопка «Добавить»
// (модалка-поиск вместо вечного списка) + сохранение per-pair скриптов.

import { describe, expect, it, beforeEach, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ToastHost } from '@/ui'
import type { PromoRulesViewDto } from '@/lib/api-types'
import { PromoRulesEditor } from './PromoRulesEditor'

const rulesHooks = vi.hoisted(() => ({
  usePromoRules: vi.fn(),
  useSavePromoRules: vi.fn(),
}))
vi.mock('@/lib/queries/promoRules', () => rulesHooks)

const storefrontHooks = vi.hoisted(() => ({ useStorefront: vi.fn() }))
vi.mock('@/lib/queries/storefront', () => ({
  storefrontKeys: { all: ['storefront'], list: () => ['storefront', 'list'] },
  useStorefront: storefrontHooks.useStorefront,
}))

function mkView(over: Partial<PromoRulesViewDto['config']> = {}): PromoRulesViewDto {
  return {
    promoId: 'pr_1',
    ruleCount: 1,
    activeCount: 1,
    config: {
      replacements: [
        {
          medusaProductId: 'prod_a',
          name: 'Аквалор Норм',
          brand: 'Stada',
          barcode: '4603423004936',
          ipartId: '80309',
          script: 'Замени — мягче',
        },
      ],
      crossSells: [],
      script: '',
      advantages: [],
      partnerLabel: null,
      comparison: [],
      goalLabel: null,
      goalTarget: null,
      goalBonus: null,
      ...over,
    },
  }
}

const mutate = vi.fn()

beforeEach(() => {
  vi.clearAllMocks()
  rulesHooks.usePromoRules.mockReturnValue({
    data: mkView(),
    isLoading: false,
    isError: false,
    error: null,
    refetch: vi.fn(),
  })
  rulesHooks.useSavePromoRules.mockReturnValue({ mutate, isPending: false })
  storefrontHooks.useStorefront.mockReturnValue({
    data: {
      items: [
        {
          id: 'prod_b',
          name: 'Платочки',
          brand: 'Zewa',
          mnn: null,
          rxOtc: null,
          price: 500,
          currency: 'KZT',
          imageUrl: null,
          barcode: '4604249789012',
          ipartId: null,
          category: null,
        },
      ],
      total: 1,
      limit: 50,
      offset: 0,
    },
    isFetching: false,
  })
})

function renderEditor() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <ToastHost>
        <PromoRulesEditor promoId="pr_1" promotedName="Эпигам спрей" promotedPrice={1990} />
      </ToastHost>
    </QueryClientProvider>,
  )
}

describe('PromoRulesEditor — per-pair скрипт + «Добавить»', () => {
  it('показывает выбранную пару с её per-pair скриптом', () => {
    renderEditor()
    expect(screen.getByTestId('pr-chosen-prod_a')).toBeInTheDocument()
    // Имя встречается дважды: в форме пары и в превью карточки кассы.
    expect(screen.getAllByText('Аквалор Норм').length).toBeGreaterThanOrEqual(1)
    expect((screen.getByTestId('pr-script-prod_a') as HTMLTextAreaElement).value).toBe(
      'Замени — мягче',
    )
  })

  it('EAN-13 и iPartID редактируются у выбранной пары', async () => {
    const user = userEvent.setup()
    renderEditor()
    const barcode = screen.getByTestId('pr-barcode-prod_a')
    const ipart = screen.getByTestId('pr-ipart-prod_a')
    expect(barcode).toHaveValue('4603423004936')
    expect(ipart).toHaveValue('80309')

    await user.clear(barcode)
    await user.type(barcode, '4600000000001')
    await user.clear(ipart)
    await user.type(ipart, '99901')
    await user.click(screen.getByTestId('promo-rules-save'))

    const arg = mutate.mock.calls[0][0]
    expect(arg.config.replacements[0]).toEqual(
      expect.objectContaining({ barcode: '4600000000001', ipartId: '99901' }),
    )
  })

  it('статус пары — switch, а не pill-кнопка', async () => {
    const user = userEvent.setup()
    renderEditor()
    const status = screen.getByTestId('pr-status-prod_a')
    const toggle = within(status).getByRole('switch')

    expect(toggle).toHaveAttribute('aria-checked', 'true')
    expect(within(status).getByText('Активно')).toBeInTheDocument()

    await user.click(toggle)
    expect(toggle).toHaveAttribute('aria-checked', 'false')
    expect(within(status).getByText('Неактивно')).toBeInTheDocument()

    await user.click(screen.getByTestId('promo-rules-save'))
    const arg = mutate.mock.calls[0][0]
    expect(arg.config.replacements[0].active).toBe(false)
  })

  it('добавленный товар несёт штрих-код в config при сохранении', async () => {
    const user = userEvent.setup()
    renderEditor()
    // Открываем модалку «Добавить кросс-селл», кликаем найденный товар (с EAN).
    await user.click(screen.getByTestId('pr-add-crossSells'))
    await user.click(screen.getByText('Платочки'))
    // Закрываем модалку (Esc) и сохраняем.
    await user.keyboard('{Escape}')
    await user.click(screen.getByTestId('promo-rules-save'))
    const arg = mutate.mock.calls[0][0]
    expect(arg.config.crossSells[0]).toEqual(
      expect.objectContaining({ medusaProductId: 'prod_b', barcode: '4604249789012' }),
    )
  })

  it('полный список товаров НЕ висит на странице — только кнопка «Добавить»', () => {
    renderEditor()
    // До клика по «Добавить» поиска витрины на странице нет.
    expect(screen.queryByPlaceholderText(/Поиск товара/i)).not.toBeInTheDocument()
    expect(screen.getByTestId('pr-add-replacements')).toBeInTheDocument()
    expect(screen.getByTestId('pr-add-crossSells')).toBeInTheDocument()
  })

  it('«Добавить замену» открывает модалку с поиском', async () => {
    const user = userEvent.setup()
    renderEditor()
    await user.click(screen.getByTestId('pr-add-replacements'))
    expect(screen.getByPlaceholderText(/Поиск товара/i)).toBeInTheDocument()
  })

  it('редактирование per-pair скрипта + Сохранить → mutate с обновлённым script', async () => {
    const user = userEvent.setup()
    renderEditor()
    const ta = screen.getByTestId('pr-script-prod_a')
    await user.clear(ta)
    await user.type(ta, 'Новый скрипт')
    await user.click(screen.getByTestId('promo-rules-save'))
    expect(mutate).toHaveBeenCalled()
    const arg = mutate.mock.calls[0][0]
    expect(arg.promoId).toBe('pr_1')
    expect(arg.config.replacements[0].script).toBe('Новый скрипт')
    // Общий скрипт больше не используется — пустой.
    expect(arg.config.script).toBe('')
  })
})

describe('PromoRulesEditor — превью кассы (структура как на реальной кассе)', () => {
  it('замена: триггер = заменяемый товар, предложение = товар кампании', () => {
    renderEditor()
    // По семантике backend: для замены ПОКУПАТЕЛЬ ПОПРОСИЛ заменяемый (r),
    // а ПРЕДЛОЖИТЕ ВМЕСТО — продвигаемый товар кампании.
    expect(screen.getByText('ПОКУПАТЕЛЬ ПОПРОСИЛ')).toBeInTheDocument()
    expect(screen.getByText('ПРЕДЛОЖИТЕ ВМЕСТО')).toBeInTheDocument()
    // Заменяемый товар (триггер) — в превью.
    expect(screen.getAllByText('Аквалор Норм').length).toBeGreaterThanOrEqual(1)
    // Предлагаемый = товар кампании.
    expect(screen.getByText('Эпигам спрей')).toBeInTheDocument()
  })

  it('кросс-селл: триггер = выбранный товар в чеке, предложение = товар кампании', () => {
    rulesHooks.usePromoRules.mockReturnValue({
      data: mkView({
        replacements: [],
        crossSells: [{ medusaProductId: 'prod_c', name: 'Платочки Zewa', brand: 'Zewa' }],
      }),
      isLoading: false,
      isError: false,
      error: null,
      refetch: vi.fn(),
    })
    renderEditor()
    expect(screen.getByText('УЖЕ В ЧЕКЕ')).toBeInTheDocument()
    expect(screen.getByText('ДОБАВЬТЕ К ПОКУПКЕ')).toBeInTheDocument()
    // Триггер = выбранный товар, предложение = товар кампании.
    expect(within(screen.getByTestId('pr-preview-trigger-prod_c')).getByText('Платочки Zewa'))
      .toBeInTheDocument()
    expect(within(screen.getByTestId('pr-preview-offer-prod_c')).getByText('Эпигам спрей'))
      .toBeInTheDocument()
  })
})
