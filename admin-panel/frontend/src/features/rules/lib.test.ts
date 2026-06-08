// Тесты helpers — ruleSummary + vendorColor.
// Используем локальные mock-правила (не зависим от пустых fixtures).

import { describe, expect, it } from 'vitest'
import { ruleSummary, vendorColor, VENDOR_PALETTE } from './lib'
import type { Rule } from '@/mocks/fixtures'

function mkRule(overrides: Partial<Rule> = {}): Rule {
  return {
    id: 'r_test',
    type: 'substitution',
    status: 'active',
    trigger: { kind: 'product', value: 'p_x' },
    recommend: 'p_y',
    bonus: 100,
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

describe('ruleSummary', () => {
  it('product trigger без матча в PRODUCT_LIBRARY → «—» (фикстуры пусты на этапе MVP)', () => {
    const r = mkRule({ trigger: { kind: 'product', value: 'p_unknown' } })
    const s = ruleSummary(r)
    expect(s).toContain('→')
    expect(s).toContain('—')
  })

  it('mnn trigger → «МНН: <value>» → name', () => {
    const r = mkRule({
      trigger: { kind: 'mnn', value: 'Морская вода' },
      recommend: 'p_anything',
    })
    expect(ruleSummary(r)).toMatch(/^МНН: Морская вода → —$/)
  })

  it('product_any trigger → пытается склеить имена, но без фикстур → пустой prefix', () => {
    const r = mkRule({
      trigger: { kind: 'product_any', value: ['p_a', 'p_b'] },
    })
    const s = ruleSummary(r)
    expect(s).toContain('→')
  })
})

describe('vendorColor', () => {
  it.each(Object.entries(VENDOR_PALETTE))('бренд "%s" → %s', (brand, color) => {
    expect(vendorColor(brand)).toBe(color)
  })

  it('неизвестный бренд → дефолтный серый #5A6173', () => {
    expect(vendorColor('Random LLC')).toBe('#5A6173')
  })
})
