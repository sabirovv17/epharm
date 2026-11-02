import axios from 'axios'
import { describe, expect, it } from 'vitest'
import type { PromoRulesConfigDto } from '@/lib/api-types'
import {
  extractPromoRulesFieldErrors,
  normalizePromoRulesConfig,
  validatePromoRulesConfig,
  type PromoRulesValidationMessages,
} from './promoRulesValidation'

const messages: PromoRulesValidationMessages = {
  required: 'required',
  maxLength: (max) => `max:${max}`,
  positiveInteger: 'positive-int',
  nonNegativeInteger: 'non-negative-int',
  invalidProduct: 'invalid-product',
  invalidTrigger: 'invalid-trigger',
  goalLabelRequired: 'goal-label-required',
  goalTargetRequired: 'goal-target-required',
}

function config(over: Partial<PromoRulesConfigDto> = {}): PromoRulesConfigDto {
  return {
    replacements: [],
    crossSells: [],
    script: '',
    advantages: [],
    partnerLabel: null,
    comparison: [],
    goalLabel: null,
    goalTarget: null,
    goalBonus: null,
    ...over,
  }
}

describe('promo rules validation contract', () => {
  it('rejects duplicate conjunctions without silently dropping either pair or its authored content', () => {
    const normalized = normalizePromoRulesConfig(
      config({
        replacements: [
          {
            medusaProductId: '',
            name: 'Группа',
            triggerKind: 'acc_group',
            triggerValue: 'g',
            script: 'Первый',
            triggerConditions: [{ kind: 'acc_mnn', value: 'm' }],
          },
          {
            medusaProductId: '',
            name: 'МНН',
            triggerKind: 'acc_mnn',
            triggerValue: 'm',
            script: 'Второй',
            triggerConditions: [{ kind: 'acc_group', value: 'g' }],
          },
        ],
      }),
    )
    expect(normalized.replacements.map((ref) => ref.script)).toEqual(['Первый', 'Второй'])
    expect(validatePromoRulesConfig(normalized, messages)).toMatchObject({
      'replacements[0].triggerConditions': 'invalid-trigger',
      'replacements[1].triggerConditions': 'invalid-trigger',
    })
  })
  it('normalizes AND conditions without changing their identity or losing existing exact triggers', () => {
    const normalized = normalizePromoRulesConfig(
      config({
        replacements: [
          {
            medusaProductId: 'product-1',
            name: 'Товар',
            triggerConditions: [
              { kind: 'acc_mnn', value: ' mnn-1 ', label: ' МНН ' },
              { kind: 'acc_group', value: ' group-1 ', label: ' Группа ' },
            ],
          },
        ],
      }),
    )
    expect(normalized.replacements[0].triggerConditions).toEqual([
      { kind: 'acc_group', value: 'group-1', label: 'Группа' },
      { kind: 'acc_mnn', value: 'mnn-1', label: 'МНН' },
    ])
    expect(validatePromoRulesConfig(normalized, messages)).toEqual({})
    expect(normalizePromoRulesConfig(normalized)).toEqual(normalized)
  })

  it('rejects missing, duplicate, excessive and malformed compound trigger conditions', () => {
    const errors = validatePromoRulesConfig(
      config({
        replacements: [
          {
            medusaProductId: '',
            name: 'Группа',
            triggerKind: 'acc_group',
            triggerValue: 'group-1',
            triggerConditions: [
              { kind: 'acc_group', value: 'another-group' },
              { kind: 'acc_mnn', value: '' },
              { kind: 'acc_mnn', value: 'x'.repeat(65) },
              { kind: 'acc_subgroup', value: 'subgroup' },
            ],
          },
        ],
      }),
      messages,
    )
    expect(errors).toHaveProperty('replacements[0].triggerConditions')
    expect(errors['replacements[0].triggerConditions[0].kind']).toMatch(/один раз/)
    expect(errors['replacements[0].triggerConditions[1].value']).toBe('invalid-trigger')
    expect(errors['replacements[0].triggerConditions[2].kind']).toMatch(/один раз/)
    expect(errors['replacements[0].triggerConditions[2].value']).toBe('invalid-trigger')
  })

  it('blocks identical active replacement and crosssell while preserving disabled historical pairs', () => {
    const trigger = {
      medusaProductId: '',
      name: 'Группа',
      triggerKind: 'acc_group' as const,
      triggerValue: 'group',
      triggerConditions: [{ kind: 'acc_mnn' as const, value: 'mnn', label: 'МНН' }],
    }
    const input = config({
      replacements: [trigger],
      crossSells: [
        {
          ...trigger,
          triggerConditions: [{ kind: 'acc_mnn', value: 'mnn', label: 'Иное название' }],
        },
      ],
    })
    expect(validatePromoRulesConfig(input, messages)['crossSells[0].triggerValue']).toMatch(
      /замену и кросс-селл/,
    )
    input.crossSells[0].active = false
    expect(validatePromoRulesConfig(input, messages)).toEqual({})
  })

  it('keeps legacy product and distinct ACC scopes with an empty Medusa product id', () => {
    const normalized = normalizePromoRulesConfig(
      config({
        replacements: [
          { medusaProductId: 'product-1', name: 'Товар' },
          {
            medusaProductId: '',
            name: 'Группа',
            triggerKind: 'acc_group',
            triggerValue: 'scope-1',
            triggerLabel: 'Группа',
            active: false,
          },
          {
            medusaProductId: '',
            name: 'Подгруппа',
            triggerKind: 'acc_subgroup',
            triggerValue: 'scope-1',
            triggerLabel: 'Подгруппа',
            active: false,
          },
          {
            medusaProductId: '',
            name: 'МНН',
            triggerKind: 'acc_mnn',
            triggerValue: 'scope-1',
            triggerLabel: 'МНН',
            active: false,
          },
        ],
      }),
    )

    expect(normalized.replacements).toHaveLength(4)
    expect(normalized.replacements[0]).not.toHaveProperty('triggerKind')
    expect(normalized.replacements[1]).toMatchObject({
      medusaProductId: '',
      triggerKind: 'acc_group',
      triggerValue: 'scope-1',
      active: false,
    })
    expect(normalized.replacements[2]).toMatchObject({
      medusaProductId: '',
      triggerKind: 'acc_subgroup',
      triggerValue: 'scope-1',
      active: false,
    })
    expect(normalized.replacements[3]).toMatchObject({
      medusaProductId: '',
      triggerKind: 'acc_mnn',
      triggerValue: 'scope-1',
      active: false,
    })
    expect(validatePromoRulesConfig(normalized, messages)).toEqual({})
  })

  it('round-trips every exact trigger and offer code without losing independent rewards', () => {
    const input = config({
      replacements: [
        {
          medusaProductId: 'trigger',
          name: 'Триггер',
          barcode: '_0012345678905_4601164003157_4870000000002_',
          barcodes: ['4601164003157', 'LOCAL-01'],
          bonus: 0,
          additionalRecommendations: [
            {
              medusaProductId: 'offer',
              name: 'Вариант',
              barcodes: ['00012345', '4870000000003'],
              bonus: 500,
            },
          ],
        },
      ],
    })
    const normalized = normalizePromoRulesConfig(input)
    expect(normalized.replacements[0]).toMatchObject({
      barcode: '0012345678905',
      barcodes: ['0012345678905', '4601164003157', '4870000000002', 'LOCAL-01'],
      bonus: 0,
      additionalRecommendations: [
        { barcode: '00012345', barcodes: ['00012345', '4870000000003'], bonus: 500 },
      ],
    })
    expect(normalizePromoRulesConfig(normalized)).toEqual(normalized)
    expect(validatePromoRulesConfig(normalized, messages)).toEqual({})
  })

  it('does not truncate an overlong manually editable primary barcode into another identity', () => {
    const normalized = normalizePromoRulesConfig(
      config({
        replacements: [{ medusaProductId: 'trigger', name: 'Триггер', barcode: '9'.repeat(33) }],
      }),
    )
    expect(normalized.replacements[0].barcode).toHaveLength(33)
    expect(validatePromoRulesConfig(normalized, messages)['replacements[0].barcode']).toBe('max:32')
  })

  it('rejects a broad scope without a stable taxonomy key', () => {
    const errors = validatePromoRulesConfig(
      config({
        crossSells: [
          { medusaProductId: '', name: 'Категория', triggerKind: 'acc_mnn', triggerValue: '   ' },
        ],
      }),
      messages,
    )
    expect(errors['crossSells[0].triggerValue']).toBe('invalid-trigger')
  })

  it('enforces the backend 64-character ACC scope key limit', () => {
    const errors = validatePromoRulesConfig(
      config({
        replacements: [
          {
            medusaProductId: '',
            name: 'Группа',
            triggerKind: 'acc_group',
            triggerValue: 'x'.repeat(65),
          },
        ],
      }),
      messages,
    )
    expect(errors['replacements[0].triggerValue']).toBe('invalid-trigger')
  })

  it('bounds non-editable Medusa snapshots without changing editable values', () => {
    const normalized = normalizePromoRulesConfig(
      config({
        replacements: [
          {
            medusaProductId: 'prod_a',
            name: 'N'.repeat(300),
            brand: 'B'.repeat(160),
            barcode: '  4600000000001  ',
            additionalRecommendations: [
              {
                medusaProductId: 'prod_alt',
                name: 'A'.repeat(300),
                barcode: '9'.repeat(40),
              },
            ],
          },
        ],
      }),
    )

    expect(normalized.replacements[0].name).toHaveLength(255)
    expect(normalized.replacements[0].brand).toHaveLength(128)
    expect(normalized.replacements[0].barcode).toBe('4600000000001')
    expect(normalized.replacements[0].additionalRecommendations?.[0].name).toHaveLength(255)
    expect(normalized.replacements[0].additionalRecommendations?.[0].barcode).toBeNull()
  })

  it('requires only conditionally meaningful fields and reports nested paths', () => {
    const errors = validatePromoRulesConfig(
      config({
        replacements: [
          {
            medusaProductId: 'prod_a',
            name: 'Товар',
            comparison: [
              {
                label: '',
                triggerValue: '10 мл',
                recommendValue: '20 мл',
                recommendHighlight: false,
              },
            ],
          },
        ],
        goalLabel: 'Продажи',
      }),
      messages,
    )

    expect(errors).toEqual({
      'replacements[0].comparison[0].label': 'required',
      goalTarget: 'goal-target-required',
    })
  })

  it('accepts an entirely empty optional goal', () => {
    expect(validatePromoRulesConfig(config(), messages)).toEqual({})
  })

  it('accepts zero bonus but rejects negative and fractional per-offer bonuses', () => {
    const errors = validatePromoRulesConfig(
      config({
        replacements: [
          {
            medusaProductId: 'prod_a',
            name: 'Товар',
            bonus: 0,
            additionalRecommendations: [{ medusaProductId: 'prod_b', name: 'Аналог', bonus: -1 }],
          },
        ],
        crossSells: [{ medusaProductId: 'prod_c', name: 'Товар', bonus: 1.5 }],
      }),
      messages,
    )
    expect(errors).toEqual({
      'replacements[0].additionalRecommendations[0].bonus': 'non-negative-int',
      'crossSells[0].bonus': 'non-negative-int',
    })
  })

  it('preserves Spring field paths and localizes standard constraint messages', () => {
    const error = new axios.AxiosError('bad request', undefined, undefined, undefined, {
      status: 400,
      statusText: 'Bad Request',
      headers: {},
      config: {} as never,
      data: {
        code: 'VALIDATION_FAILED',
        message: 'Проверьте корректность данных',
        timestamp: '2026-09-19T00:00:00Z',
        fields: {
          'crossSells[0].barcode': 'size must be between 0 and 32',
          'crossSells[0].comparison[0].label': 'must not be blank',
        },
      },
    })

    expect(extractPromoRulesFieldErrors(error, messages)).toEqual({
      'crossSells[0].barcode': 'max:32',
      'crossSells[0].comparison[0].label': 'required',
    })
  })
})
