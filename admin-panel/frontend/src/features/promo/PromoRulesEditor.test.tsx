// Тесты редизайна редактора правил (T2 UX): per-pair скрипт + кнопка «Добавить»
// (модалка-поиск вместо вечного списка) + сохранение per-pair скриптов.

import { describe, expect, it, beforeEach, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import axios from 'axios'
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
  mutate.mockReset()
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
        {
          id: 'prod_c',
          name: 'Аналог 2',
          brand: 'Inkar',
          mnn: null,
          rxOtc: null,
          price: 1250,
          currency: 'KZT',
          imageUrl: null,
          barcode: '4870000000002',
          ipartId: null,
          category: null,
        },
      ],
      total: 2,
      limit: 50,
      offset: 0,
    },
    isFetching: false,
  })
})

function renderEditor(campaignBonus = 0) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <ToastHost>
        <PromoRulesEditor
          promoId="pr_1"
          bonus={campaignBonus}
          promotedProductId="prod_promoted"
          promotedName="Эпигам спрей"
          promotedPrice={1990}
        />
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

  it('добавляет альтернативу в ту же пару и сохраняет её в payload', async () => {
    const user = userEvent.setup()
    renderEditor()

    await user.click(screen.getByTestId('pr-add-offer-prod_a'))
    await user.click(await screen.findByTestId('promo-product-option-prod_b'))
    await user.keyboard('{Escape}')
    await user.click(screen.getByTestId('promo-rules-save'))

    const replacement = mutate.mock.calls[0][0].config.replacements[0]
    expect(replacement.additionalRecommendations).toEqual([
      expect.objectContaining({
        medusaProductId: 'prod_b',
        name: 'Платочки',
        price: 500,
        barcode: '4604249789012',
      }),
    ])
    expect(replacement.additionalRecommendations[0].bonus).toBe(0)
  })

  it('задаёт бонус индивидуально для каждой позиции и подсвечивает только оплачиваемую', async () => {
    const user = userEvent.setup()
    rulesHooks.usePromoRules.mockReturnValue({
      data: mkView({
        replacements: [{
          medusaProductId: 'prod_a', name: 'Аквалор Норм', bonus: 0,
          additionalRecommendations: [{ medusaProductId: 'prod_b', name: 'Платочки', bonus: 250 }],
        }],
      }),
      isLoading: false, isError: false, error: null, refetch: vi.fn(),
    })
    renderEditor(400)

    const primary = screen.getByTestId('pr-preview-card-prod_a-primary')
    const alternative = screen.getByTestId('pr-preview-card-prod_a-prod_b')
    expect(primary).not.toHaveClass('bg-recommendation-green')
    expect(alternative).toHaveClass('bg-recommendation-green')
    expect(screen.getByTestId('pr-bonus-primary-prod_a')).toHaveValue(0)
    expect(screen.getByTestId('pr-bonus-prod_a-prod_b')).toHaveValue(250)

    await user.clear(screen.getByTestId('pr-bonus-primary-prod_a'))
    await user.type(screen.getByTestId('pr-bonus-primary-prod_a'), '150')
    await user.clear(screen.getByTestId('pr-bonus-prod_a-prod_b'))
    await user.type(screen.getByTestId('pr-bonus-prod_a-prod_b'), '0')
    expect(primary).toHaveClass('bg-recommendation-green')
    expect(alternative).not.toHaveClass('bg-recommendation-green')

    await user.click(screen.getByTestId('promo-rules-save'))
    const ref = mutate.mock.calls[0][0].config.replacements[0]
    expect(ref.bonus).toBe(150)
    expect(ref.additionalRecommendations[0].bonus).toBe(0)
  })

  it('сохраняет совместимость: старые позиции без bonus наследуют бонус кампании', () => {
    renderEditor(200)
    expect(screen.getByTestId('pr-bonus-primary-prod_a')).toHaveValue(200)
    expect(screen.getByTestId('pr-preview-card-prod_a-primary')).toHaveClass('bg-recommendation-green')
  })

  it('строго ограничивает пару пятью предложениями вместе с основным', () => {
    rulesHooks.usePromoRules.mockReturnValue({
      data: mkView({
        replacements: [
          {
            medusaProductId: 'prod_a',
            name: 'Аквалор Норм',
            additionalRecommendations: [1, 2, 3, 4].map((n) => ({
              medusaProductId: `alt_${n}`,
              name: `Аналог ${n}`,
              price: 1000 + n * 100,
            })),
          },
        ],
      }),
      isLoading: false,
      isError: false,
      error: null,
      refetch: vi.fn(),
    })

    renderEditor()

    expect(screen.getByTestId('pr-add-offer-prod_a')).toBeDisabled()
    const preview = screen.getByTestId('pr-preview-offer-prod_a')
    expect(preview.children).toHaveLength(5)
    expect(preview).toHaveClass('overflow-y-auto')
    expect(within(preview).getByText('1 100 ₸')).toBeInTheDocument()
  })

  it('не отправляет форму и показывает ошибку прямо у слишком длинного поля', async () => {
    const user = userEvent.setup()
    rulesHooks.usePromoRules.mockReturnValue({
      data: mkView({
        replacements: [
          {
            medusaProductId: 'prod_a',
            name: 'Аквалор Норм',
            script: 'Я'.repeat(2001),
          },
        ],
      }),
      isLoading: false,
      isError: false,
      error: null,
      refetch: vi.fn(),
    })

    renderEditor()
    await user.click(screen.getByTestId('promo-rules-save'))

    expect(mutate).not.toHaveBeenCalled()
    expect(screen.getByTestId('pr-script-prod_a')).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByText('Не больше 2000 символов')).toBeInTheDocument()
    expect(screen.getByTestId('promo-rules-validation-summary')).toHaveTextContent(
      'Найдено ошибок: 1',
    )
  })

  it('показывает условно обязательное количество, если заполнена цель', async () => {
    const user = userEvent.setup()
    rulesHooks.usePromoRules.mockReturnValue({
      data: mkView({ goalLabel: 'Продажи', goalTarget: null }),
      isLoading: false,
      isError: false,
      error: null,
      refetch: vi.fn(),
    })

    renderEditor()
    await user.click(screen.getByTestId('promo-rules-save'))

    expect(mutate).not.toHaveBeenCalled()
    expect(screen.getByTestId('pr-goal-target')).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByText('Укажите количество для цели')).toBeInTheDocument()
  })

  it('показывает вложенную серверную ошибку у нужного поля', async () => {
    const user = userEvent.setup()
    const error = new axios.AxiosError(
      'bad request',
      undefined,
      undefined,
      undefined,
      {
        status: 400,
        statusText: 'Bad Request',
        headers: {},
        config: {} as never,
        data: {
          code: 'VALIDATION_FAILED',
          message: 'Проверьте корректность данных',
          timestamp: '2026-09-19T00:00:00Z',
          fields: { 'replacements[0].barcode': 'size must be between 0 and 32' },
        },
      },
    )
    mutate.mockImplementation((_payload, options) => options.onError(error))

    renderEditor()
    await user.click(screen.getByTestId('promo-rules-save'))

    expect(await screen.findByText('Не больше 32 символов')).toBeInTheDocument()
    expect(screen.getByTestId('pr-barcode-prod_a')).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByTestId('promo-rules-validation-summary')).toBeInTheDocument()
  })

  it('раскрывает необязательную секцию, если сервер нашёл ошибку внутри неё', async () => {
    const user = userEvent.setup()
    const error = new axios.AxiosError(
      'bad request',
      undefined,
      undefined,
      undefined,
      {
        status: 400,
        statusText: 'Bad Request',
        headers: {},
        config: {} as never,
        data: {
          code: 'VALIDATION_FAILED',
          message: 'Проверьте корректность данных',
          timestamp: '2026-09-19T00:00:00Z',
          fields: { 'replacements[0].partnerLabel': 'size must be between 0 and 64' },
        },
      },
    )
    mutate.mockImplementation((_payload, options) => options.onError(error))

    renderEditor()
    await user.click(screen.getByTestId('promo-rules-save'))

    expect(await screen.findByPlaceholderText('ПАРТНЁР EPHARM')).toHaveAttribute(
      'aria-invalid',
      'true',
    )
    expect(screen.getByTestId('pr-card-toggle-prod_a')).toHaveAttribute('aria-expanded', 'true')
  })

  it('нормализует длинные снимки Medusa, которые нельзя исправить в форме', async () => {
    const user = userEvent.setup()
    rulesHooks.usePromoRules.mockReturnValue({
      data: mkView({
        replacements: [
          {
            medusaProductId: 'prod_a',
            name: 'Н'.repeat(300),
            brand: 'Б'.repeat(150),
          },
        ],
      }),
      isLoading: false,
      isError: false,
      error: null,
      refetch: vi.fn(),
    })

    renderEditor()
    await user.click(screen.getByTestId('promo-rules-save'))

    expect(mutate).toHaveBeenCalledOnce()
    const replacement = mutate.mock.calls[0][0].config.replacements[0]
    expect(replacement.name).toHaveLength(255)
    expect(replacement.brand).toHaveLength(128)
  })
})

describe('PromoRulesEditor — превью кассы (структура как на реальной кассе)', () => {
  it('замена: триггер = заменяемый товар, предложение = товар кампании', () => {
    renderEditor()
    // По семантике backend: для замены покупатель попросил заменяемый товар (r),
    // а в секции замены показан продвигаемый товар кампании.
    expect(screen.getByText('ПОКУПАТЕЛЬ ПОПРОСИЛ')).toBeInTheDocument()
    expect(screen.getByTestId('pr-preview-section-prod_a')).toHaveTextContent('Замена · 1')
    // Заменяемый товар (триггер) — в превью.
    expect(screen.getAllByText('Аквалор Норм').length).toBeGreaterThanOrEqual(1)
    // Предлагаемый = товар кампании.
    const preview = screen.getByTestId('pr-preview-offer-prod_a')
    expect(preview.children).toHaveLength(1)
    expect(within(preview).getByText('Эпигам спрей')).toBeInTheDocument()
    expect(within(preview).getByText('1 990 ₸')).toBeInTheDocument()
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
    expect(screen.getByTestId('pr-preview-section-prod_c')).toHaveTextContent('Кросс-селл · 1')
    // Триггер = выбранный товар, предложение = товар кампании.
    expect(
      within(screen.getByTestId('pr-preview-trigger-prod_c')).getByText('Платочки Zewa'),
    ).toBeInTheDocument()
    expect(
      within(screen.getByTestId('pr-preview-offer-prod_c')).getByText('Эпигам спрей'),
    ).toBeInTheDocument()
  })
})
