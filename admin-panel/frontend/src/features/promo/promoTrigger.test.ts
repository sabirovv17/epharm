import { describe, expect, it } from 'vitest'
import { promoTriggerKey } from './promoTrigger'
import { ruleTriggerDisplay } from '../rules/lib'

describe('compound trigger identity', () => {
  const base = {
    medusaProductId: '',
    name: 'Группа',
    triggerKind: 'acc_group' as const,
    triggerValue: 'g',
  }
  it('preserves legacy keys, distinguishes narrowing and ignores order/labels', () => {
    const a = { kind: 'acc_mnn' as const, value: 'm', label: 'МНН' }
    const b = { kind: 'acc_subgroup' as const, value: 's' }
    expect(promoTriggerKey(base)).toBe('acc_group:g')
    expect(promoTriggerKey({ ...base, triggerConditions: [a] })).not.toBe(promoTriggerKey(base))
    expect(promoTriggerKey({ ...base, triggerConditions: [a, b] })).toBe(
      promoTriggerKey({ ...base, triggerConditions: [b, { ...a, label: 'Переименовано' }] }),
    )
  })
  it('makes all factors visible in the read-only rules list', () => {
    expect(
      ruleTriggerDisplay({
        kind: 'acc_group',
        value: 'g',
        label: 'Группа',
        conditions: [{ kind: 'acc_mnn', value: 'm', label: 'МНН' }],
      }),
    ).toBe('Группа ACC: Группа ∩ МНН ACC: МНН')
  })
  it('recognizes the same conjunction even when the primary factor is swapped', () => {
    expect(promoTriggerKey({ ...base, triggerConditions: [{ kind: 'acc_mnn', value: 'm' }] })).toBe(
      promoTriggerKey({
        ...base,
        triggerKind: 'acc_mnn',
        triggerValue: 'm',
        triggerConditions: [{ kind: 'acc_group', value: 'g' }],
      }),
    )
  })
})
