// Тесты RuleBuilder — READ-ONLY панель Rules Engine.
// Правила создаются из кампаний; здесь только просмотр: статус-чип, параметры,
// никаких переключателей/Save/мутаций.

import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import type { RuleDto } from '@/lib/api-types'
import { RuleBuilder } from './RuleBuilder'

const catalogHooks = vi.hoisted(() => ({
  useProducts: vi.fn(),
  useBrands: vi.fn(),
  useMnnGroups: vi.fn(),
  useProductLookup: vi.fn(),
  buildProductIndex: vi.fn(),
}))

vi.mock('@/lib/queries/catalog', () => catalogHooks)

beforeEach(() => {
  vi.clearAllMocks()
  catalogHooks.useProducts.mockReturnValue({ data: [], isLoading: false })
  catalogHooks.useProductLookup.mockReturnValue(() => undefined)
})

function mkRule(over: Partial<RuleDto> = {}): RuleDto {
  return {
    id: 'r_test',
    type: 'substitution',
    status: 'active',
    trigger: { kind: 'product', value: 'p_x' },
    recommend: 'p_y',
    bonus: 520,
    script: '',
    advantages: [],
    abTest: null,
    pharmacies: 12,
    impressions: 0,
    accepts: 0,
    convRate: 36.8,
    revenue: 0,
    payout: 0,
    createdBy: '',
    createdAt: '2026-05-01T00:00:00Z',
    updatedAt: '2026-05-01T00:00:00Z',
    ...over,
  }
}

describe('RuleBuilder — read-only статус (чип, без переключателя)', () => {
  it('active → чип «Активно», переключателя нет', () => {
    render(<RuleBuilder rule={mkRule({ status: 'active' })} />)
    expect(screen.getByText('Активно')).toBeInTheDocument()
    expect(screen.queryByRole('switch')).not.toBeInTheDocument()
  })

  it('paused → чип «Пауза», переключателя нет', () => {
    render(<RuleBuilder rule={mkRule({ status: 'paused' })} />)
    expect(screen.getByText('Пауза')).toBeInTheDocument()
    expect(screen.queryByRole('switch')).not.toBeInTheDocument()
  })

  it('archived → чип «В архиве»', () => {
    render(<RuleBuilder rule={mkRule({ status: 'archived' })} />)
    expect(screen.getByText('В архиве')).toBeInTheDocument()
    expect(screen.queryByRole('switch')).not.toBeInTheDocument()
  })
})

describe('RuleBuilder — никаких мутаций (read-only)', () => {
  it('нет кнопки «Сохранить» и индикатора несохранённых изменений', () => {
    render(<RuleBuilder rule={mkRule()} />)
    expect(screen.queryByRole('button', { name: /Сохранить/ })).not.toBeInTheDocument()
    expect(screen.queryByText(/Есть несохранённые изменения/)).not.toBeInTheDocument()
  })

  it('вкладка «Параметры» показывает конфигурацию read-only + подсказку', () => {
    render(<RuleBuilder rule={mkRule({ bonus: 520 })} />)
    expect(screen.getByTestId('rule-config-view')).toBeInTheDocument()
    expect(screen.getByText('Триггер')).toBeInTheDocument()
    expect(screen.getByText('Рекомендуем')).toBeInTheDocument()
    expect(screen.getByText(/Правила создаются в разделе/)).toBeInTheDocument()
  })
})

describe('RuleBuilder — Header', () => {
  it('показывает тип + id + количество аптек + дату', () => {
    render(<RuleBuilder rule={mkRule({ pharmacies: 247 })} />)
    expect(screen.getByText(/Замена · r_test · 247 аптек/i)).toBeInTheDocument()
    expect(screen.getByText(/обновлено 2026-05-01/i)).toBeInTheDocument()
  })

  it('crosssell rule → префикс «Кросс-селл»', () => {
    render(<RuleBuilder rule={mkRule({ type: 'crosssell' })} />)
    expect(screen.getByText(/Кросс-селл/i)).toBeInTheDocument()
  })
})
