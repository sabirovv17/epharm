// Тесты RuleBuilder — Toggle/Status поведение + dirty-flag.
// Регрессия: до фикса Toggle читал из local.status, не обновлялся после mutation.

import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import type { RuleDto } from '@/lib/api-types'
import { RuleBuilder } from './RuleBuilder'

// Мокаем catalog queries — продукты вернут пустоту, селектам неважно
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

describe('RuleBuilder — Toggle «Активно» / «Пауза»', () => {
  it('active rule → toggle ON, текст «Активно»', () => {
    render(<RuleBuilder rule={mkRule({ status: 'active' })} onSave={vi.fn()} onToggle={vi.fn()} />)
    const toggle = screen.getByRole('switch')
    expect(toggle).toHaveAttribute('aria-checked', 'true')
    expect(screen.getByText('Активно')).toBeInTheDocument()
  })

  it('paused rule → toggle OFF, текст «Пауза»', () => {
    render(<RuleBuilder rule={mkRule({ status: 'paused' })} onSave={vi.fn()} onToggle={vi.fn()} />)
    const toggle = screen.getByRole('switch')
    expect(toggle).toHaveAttribute('aria-checked', 'false')
    expect(screen.getByText('Пауза')).toBeInTheDocument()
  })

  it('archived rule → toggle вообще не рендерится', () => {
    render(
      <RuleBuilder rule={mkRule({ status: 'archived' })} onSave={vi.fn()} onToggle={vi.fn()} />,
    )
    expect(screen.queryByRole('switch')).not.toBeInTheDocument()
  })

  it('клик по toggle вызывает onToggle', () => {
    const onToggle = vi.fn()
    render(<RuleBuilder rule={mkRule({ status: 'active' })} onSave={vi.fn()} onToggle={onToggle} />)
    fireEvent.click(screen.getByRole('switch'))
    expect(onToggle).toHaveBeenCalledTimes(1)
  })

  it('REGRESSION: после mutation rule.status меняется → toggle обновляется', () => {
    const { rerender } = render(
      <RuleBuilder rule={mkRule({ status: 'active' })} onSave={vi.fn()} onToggle={vi.fn()} />,
    )
    expect(screen.getByRole('switch')).toHaveAttribute('aria-checked', 'true')

    // Имитация: server вернул paused-rule после mutation, query инвалидирована,
    // parent шлёт обновлённый rule с тем же id но новым updatedAt.
    rerender(
      <RuleBuilder
        rule={mkRule({ status: 'paused', updatedAt: '2026-05-02T00:00:00Z' })}
        onSave={vi.fn()}
        onToggle={vi.fn()}
      />,
    )
    expect(screen.getByRole('switch')).toHaveAttribute('aria-checked', 'false')
    expect(screen.getByText('Пауза')).toBeInTheDocument()
  })
})

describe('RuleBuilder — Save / Dirty состояние', () => {
  it('изначально dirty=false → кнопка «Сохранить» disabled', () => {
    render(<RuleBuilder rule={mkRule()} onSave={vi.fn()} onToggle={vi.fn()} />)
    expect(screen.getByRole('button', { name: /Сохранить/ })).toBeDisabled()
    expect(screen.queryByText(/Есть несохранённые изменения/)).not.toBeInTheDocument()
  })

  it('REGRESSION: после save (rule.updatedAt меняется) dirty-flag сбрасывается', () => {
    // 1. Рендерим
    const { rerender } = render(
      <RuleBuilder rule={mkRule({ bonus: 520 })} onSave={vi.fn()} onToggle={vi.fn()} />,
    )

    // 2. Имитация save: backend вернул тот же rule, но с новым updatedAt.
    // Локальный bonus уже совпадает с серверным (он не редактировался) — dirty должен
    // остаться false (раньше JSON.stringify сравнивал createdAt/updatedAt и dirty был true).
    rerender(
      <RuleBuilder
        rule={mkRule({ bonus: 520, updatedAt: '2026-05-15T12:00:00Z' })}
        onSave={vi.fn()}
        onToggle={vi.fn()}
      />,
    )
    expect(screen.getByRole('button', { name: /Сохранить/ })).toBeDisabled()
  })
})

describe('RuleBuilder — Header', () => {
  it('показывает тип + id + количество аптек + дату', () => {
    render(<RuleBuilder rule={mkRule({ pharmacies: 247 })} onSave={vi.fn()} onToggle={vi.fn()} />)
    expect(screen.getByText(/Замена · r_test · 247 аптек/i)).toBeInTheDocument()
    expect(screen.getByText(/обновлено 2026-05-01/i)).toBeInTheDocument()
  })

  it('crosssell rule → префикс «Кросс-селл»', () => {
    render(<RuleBuilder rule={mkRule({ type: 'crosssell' })} onSave={vi.fn()} onToggle={vi.fn()} />)
    expect(screen.getByText(/Кросс-селл/i)).toBeInTheDocument()
  })
})
