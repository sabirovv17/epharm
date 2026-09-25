import axios from 'axios'
import type {
  ApiErrorResponse,
  PromoOfferProductRef,
  PromoRuleProductRef,
  PromoRulesConfigDto,
  RuleComparisonRowDto,
} from '@/lib/api-types'

export type PromoRulesValidationErrors = Record<string, string>

export const PROMO_RULE_LIMITS = {
  productId: 64,
  name: 255,
  brand: 128,
  mnn: 128,
  volume: 64,
  barcode: 32,
  ipartId: 64,
  script: 2000,
  partnerLabel: 64,
  comparisonLabel: 120,
  comparisonValue: 200,
  goalLabel: 120,
  maxInt: 2_147_483_647,
} as const

export type PromoRulesValidationMessages = {
  required: string
  maxLength: (max: number) => string
  positiveInteger: string
  nonNegativeInteger: string
  invalidProduct: string
  goalLabelRequired: string
  goalTargetRequired: string
}

const trimOrNull = (value: string | null | undefined): string | null =>
  value?.trim() || null

const boundedSnapshot = (
  value: string | null | undefined,
  max: number,
): string | null => {
  const trimmed = trimOrNull(value)
  return trimmed == null ? null : trimmed.slice(0, max)
}

function normalizeComparison(row: RuleComparisonRowDto): RuleComparisonRowDto {
  return {
    ...row,
    label: row.label.trim(),
    triggerValue: row.triggerValue.trim(),
    recommendValue: row.recommendValue.trim(),
  }
}

function comparisonHasContent(row: RuleComparisonRowDto): boolean {
  return Boolean(row.label || row.triggerValue || row.recommendValue)
}

function normalizeOffer(offer: PromoOfferProductRef): PromoOfferProductRef {
  const barcode = trimOrNull(offer.barcode)
  const ipartId = trimOrNull(offer.ipartId)
  return {
    ...offer,
    name: boundedSnapshot(offer.name, PROMO_RULE_LIMITS.name) ?? '',
    brand: boundedSnapshot(offer.brand, PROMO_RULE_LIMITS.brand),
    mnn: boundedSnapshot(offer.mnn, PROMO_RULE_LIMITS.mnn),
    volume: boundedSnapshot(offer.volume, PROMO_RULE_LIMITS.volume),
    // These values come from Medusa and are not editable in the alternative
    // offer row. Discard malformed identifiers instead of blocking the form or
    // fabricating a truncated identifier that could match the wrong product.
    barcode: barcode && barcode.length <= PROMO_RULE_LIMITS.barcode ? barcode : null,
    ipartId: ipartId && ipartId.length <= PROMO_RULE_LIMITS.ipartId ? ipartId : null,
  }
}

function normalizeRef(
  ref: PromoRuleProductRef,
  promotedProductId?: string,
): PromoRuleProductRef {
  return {
    ...ref,
    name: boundedSnapshot(ref.name, PROMO_RULE_LIMITS.name) ?? '',
    brand: boundedSnapshot(ref.brand, PROMO_RULE_LIMITS.brand),
    mnn: boundedSnapshot(ref.mnn, PROMO_RULE_LIMITS.mnn),
    volume: boundedSnapshot(ref.volume, PROMO_RULE_LIMITS.volume),
    script: (ref.script ?? '').trim(),
    barcode: trimOrNull(ref.barcode),
    ipartId: trimOrNull(ref.ipartId),
    advantages: (ref.advantages ?? []).map((item) => item.trim()).filter(Boolean),
    comparison: (ref.comparison ?? [])
      .map(normalizeComparison)
      .filter(comparisonHasContent),
    partnerLabel: trimOrNull(ref.partnerLabel),
    additionalRecommendations: [
      ...new Map(
        (ref.additionalRecommendations ?? []).map((offer) => [offer.medusaProductId, offer]),
      ).values(),
    ]
      .filter(
        (offer) =>
          offer.medusaProductId !== promotedProductId &&
          offer.medusaProductId !== ref.medusaProductId,
      )
      .slice(0, 4)
      .map(normalizeOffer),
    active: ref.active !== false,
  }
}

/**
 * Produces the exact request contract sent to the backend. Medusa-owned display
 * snapshots are bounded here because the user cannot edit them in this form;
 * editable values are never silently truncated and are validated separately.
 */
export function normalizePromoRulesConfig(
  config: PromoRulesConfigDto,
  promotedProductId?: string,
): PromoRulesConfigDto {
  const dedupe = (list: PromoRuleProductRef[]) => [
    ...new Map(list.map((ref) => [ref.medusaProductId, ref])).values(),
  ]

  return {
    ...config,
    script: '',
    advantages: [],
    partnerLabel: null,
    comparison: [],
    goalLabel: trimOrNull(config.goalLabel),
    goalTarget: config.goalTarget ?? null,
    goalBonus: config.goalBonus ?? null,
    replacements: dedupe(config.replacements).map((ref) =>
      normalizeRef(ref, promotedProductId),
    ),
    crossSells: dedupe(config.crossSells).map((ref) =>
      normalizeRef(ref, promotedProductId),
    ),
  }
}

function validateLength(
  errors: PromoRulesValidationErrors,
  path: string,
  value: string | null | undefined,
  max: number,
  messages: PromoRulesValidationMessages,
) {
  if ((value?.length ?? 0) > max) errors[path] = messages.maxLength(max)
}

function validateInteger(
  errors: PromoRulesValidationErrors,
  path: string,
  value: number | null | undefined,
  minimum: number,
  message: string,
) {
  if (
    value != null &&
    (!Number.isInteger(value) || value < minimum || value > PROMO_RULE_LIMITS.maxInt)
  ) {
    errors[path] = message
  }
}

function validateOffer(
  offer: PromoOfferProductRef,
  path: string,
  errors: PromoRulesValidationErrors,
  messages: PromoRulesValidationMessages,
) {
  if (!offer.medusaProductId.trim() || offer.medusaProductId.length > PROMO_RULE_LIMITS.productId) {
    errors[`${path}.medusaProductId`] = messages.invalidProduct
  }
  validateLength(errors, `${path}.barcode`, offer.barcode, PROMO_RULE_LIMITS.barcode, messages)
  validateLength(errors, `${path}.ipartId`, offer.ipartId, PROMO_RULE_LIMITS.ipartId, messages)
  validateInteger(errors, `${path}.bonus`, offer.bonus, 0, messages.nonNegativeInteger)
}

function validateRef(
  ref: PromoRuleProductRef,
  path: string,
  errors: PromoRulesValidationErrors,
  messages: PromoRulesValidationMessages,
) {
  if (!ref.medusaProductId.trim() || ref.medusaProductId.length > PROMO_RULE_LIMITS.productId) {
    errors[`${path}.medusaProductId`] = messages.invalidProduct
  }
  validateLength(errors, `${path}.barcode`, ref.barcode, PROMO_RULE_LIMITS.barcode, messages)
  validateLength(errors, `${path}.ipartId`, ref.ipartId, PROMO_RULE_LIMITS.ipartId, messages)
  validateLength(errors, `${path}.script`, ref.script, PROMO_RULE_LIMITS.script, messages)
  validateInteger(errors, `${path}.bonus`, ref.bonus, 0, messages.nonNegativeInteger)
  validateLength(
    errors,
    `${path}.partnerLabel`,
    ref.partnerLabel,
    PROMO_RULE_LIMITS.partnerLabel,
    messages,
  )

  ;(ref.comparison ?? []).forEach((row, index) => {
    const rowPath = `${path}.comparison[${index}]`
    if (comparisonHasContent(row) && !row.label.trim()) {
      errors[`${rowPath}.label`] = messages.required
    }
    validateLength(
      errors,
      `${rowPath}.label`,
      row.label,
      PROMO_RULE_LIMITS.comparisonLabel,
      messages,
    )
    validateLength(
      errors,
      `${rowPath}.triggerValue`,
      row.triggerValue,
      PROMO_RULE_LIMITS.comparisonValue,
      messages,
    )
    validateLength(
      errors,
      `${rowPath}.recommendValue`,
      row.recommendValue,
      PROMO_RULE_LIMITS.comparisonValue,
      messages,
    )
  })

  ;(ref.additionalRecommendations ?? []).forEach((offer, index) =>
    validateOffer(offer, `${path}.additionalRecommendations[${index}]`, errors, messages),
  )
}

export function validatePromoRulesConfig(
  config: PromoRulesConfigDto,
  messages: PromoRulesValidationMessages,
): PromoRulesValidationErrors {
  const errors: PromoRulesValidationErrors = {}

  config.replacements.forEach((ref, index) =>
    validateRef(ref, `replacements[${index}]`, errors, messages),
  )
  config.crossSells.forEach((ref, index) =>
    validateRef(ref, `crossSells[${index}]`, errors, messages),
  )

  validateLength(
    errors,
    'goalLabel',
    config.goalLabel,
    PROMO_RULE_LIMITS.goalLabel,
    messages,
  )
  validateInteger(
    errors,
    'goalTarget',
    config.goalTarget,
    1,
    messages.positiveInteger,
  )
  validateInteger(
    errors,
    'goalBonus',
    config.goalBonus,
    0,
    messages.nonNegativeInteger,
  )

  const goalStarted = Boolean(
    config.goalLabel || config.goalTarget != null || config.goalBonus != null,
  )
  if (goalStarted && !config.goalLabel) errors.goalLabel = messages.goalLabelRequired
  if (goalStarted && config.goalTarget == null) errors.goalTarget = messages.goalTargetRequired

  return errors
}

function humanizeServerMessage(
  path: string,
  message: string,
  messages: PromoRulesValidationMessages,
): string {
  const max = message.match(/size must be between \d+ and (\d+)/i)?.[1]
  if (path.endsWith('medusaProductId')) return messages.invalidProduct
  if (max) return messages.maxLength(Number(max))
  if (/must not be blank/i.test(message)) return messages.required
  if (/greater than or equal to 0/i.test(message)) return messages.nonNegativeInteger
  return message
}

/** Extracts Spring's nested `fields` map without losing the general API error. */
export function extractPromoRulesFieldErrors(
  error: unknown,
  messages: PromoRulesValidationMessages,
): PromoRulesValidationErrors {
  if (!axios.isAxiosError<ApiErrorResponse>(error)) return {}
  const fields = error.response?.data?.fields ?? {}
  return Object.fromEntries(
    Object.entries(fields).map(([path, message]) => {
      const rendered = humanizeServerMessage(path, message, messages)
      return [path, rendered]
    }),
  )
}
