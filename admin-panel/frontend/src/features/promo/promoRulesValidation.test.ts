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

  it('preserves Spring field paths and localizes standard constraint messages', () => {
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
          fields: {
            'crossSells[0].barcode': 'size must be between 0 and 32',
            'crossSells[0].comparison[0].label': 'must not be blank',
          },
        },
      },
    )

    expect(extractPromoRulesFieldErrors(error, messages)).toEqual({
      'crossSells[0].barcode': 'max:32',
      'crossSells[0].comparison[0].label': 'required',
    })
  })
})
