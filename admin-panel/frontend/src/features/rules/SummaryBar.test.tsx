// Тесты SummaryBar — pure computeMetrics + рендер 4 секций на нулевых данных.
import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { computeMetrics, SummaryBar } from './SummaryBar'
import type { Rule } from '@/mocks/fixtures'

function mkRule(overrides: Partial<Rule> = {}): Rule {
  return {
    id: `r_${Math.random()}`,
    type: 'substitution',
    status: 'active',
    trigger: { kind: 'product', value: 'p_x' },
    recommend: 'p_y',
    bonus: 0,
    impressions: 0,
    accepts: 0,
    convRate: 0,
    revenue: 0,
    payout: 0,
    script: '',
    advantages: [],
    abTest: null,
    createdBy: 'damir',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    spark: [],
    pharmacies: 0,
    ...overrides,
  }
}

describe('computeMetrics — формулы', () => {
  it('пустые коллекции → все нули, conv=0', () => {
    expect(computeMetrics([], [])).toEqual({
      total: 0,
      active: 0,
      impressions: 0,
      accepts: 0,
      payout: 0,
      conv: 0,
    })
  })

  it('total = subst.length + cross.length', () => {
    const subst = [mkRule(), mkRule()]
    const cross = [mkRule()]
    expect(computeMetrics(subst, cross).total).toBe(3)
  })

  it('active считает только rules со status="active"', () => {
    const subst = [mkRule({ status: 'active' }), mkRule({ status: 'paused' })]
    const cross = [mkRule({ status: 'active' })]
    expect(computeMetrics(subst, cross).active).toBe(2)
  })

  it('impressions/accepts суммируются из обеих коллекций', () => {
    const subst = [mkRule({ impressions: 100, accepts: 30 })]
    const cross = [mkRule({ impressions: 200, accepts: 50 })]
    const m = computeMetrics(subst, cross)
    expect(m.impressions).toBe(300)
    expect(m.accepts).toBe(80)
  })

  it('conv = accepts/impressions * 100, рассчитывается корректно', () => {
    const subst = [mkRule({ impressions: 100, accepts: 25 })]
    const m = computeMetrics(subst, [])
    expect(m.conv).toBeCloseTo(25, 4)
  })
})

describe('SummaryBar — рендер', () => {
  it('рендерит 4 секции с правильными лейблами', () => {
    render(<SummaryBar metrics={computeMetrics([], [])} />)
    expect(screen.getByText('Активных правил')).toBeInTheDocument()
    expect(screen.getByText('Показано')).toBeInTheDocument()
    expect(screen.getByText('Принято')).toBeInTheDocument()
    expect(screen.getByText('Выплачено фармацевтам')).toBeInTheDocument()
  })

  it('на пустых данных value=0 и подпись "из 0"', () => {
    render(<SummaryBar metrics={computeMetrics([], [])} />)
    // Первая секция: "0" → "из 0"
    expect(screen.getByText('из 0')).toBeInTheDocument()
  })
})
