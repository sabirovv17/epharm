package kz.epharm.shared.validation

/**
 * Normalizes barcode values coming from PIM/Medusa.
 *
 * Some real products contain several GTINs in a single `variant.barcode` value
 * separated by `_` (for example `460..._487..._487...`). POSM and the promo
 * schema store one exact cash-register key, so the first GTIN is the canonical
 * value. A short non-standard value is preserved for backwards compatibility
 * with pharmacy-local identifiers entered manually.
 */
object BarcodeNormalizer {
    private val gtinToken = Regex("(?<!\\d)\\d{8,14}(?!\\d)")

    fun first(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return gtinToken.find(value)?.value ?: value.takeIf { it.length <= MAX_STORED_LENGTH }
    }

    const val MAX_INPUT_LENGTH = 255
    const val MAX_STORED_LENGTH = 32
}
