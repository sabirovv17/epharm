package kz.epharm.shared.validation

/**
 * Normalizes barcode values coming from PIM/Medusa.
 *
 * Some real products contain several GTINs in a single `variant.barcode` value
 * separated by `_` (for example `460..._487..._487...`). POSM and the promo
 * schema keep the first GTIN as a legacy display value, and [all] retains every
 * exact key for matching. A short non-standard value is preserved for backwards compatibility
 * with pharmacy-local identifiers entered manually.
 */
object BarcodeNormalizer {
    private val gtinToken = Regex("\\d{8,14}")
    private val separators = Regex("[_;,|\\s]+")

    fun first(raw: String?): String? {
        return all(raw).firstOrNull()
    }

    /** Only explicit barcode fields are accepted here; never product names or marketplace SKUs. */
    fun all(raw: String?): List<String> {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val tokens = value.split(separators).filter(String::isNotEmpty)
        return if (tokens.isNotEmpty() && tokens.all { gtinToken.matches(it) }) tokens.distinct()
        else listOfNotNull(value.takeIf { it.length <= MAX_STORED_LENGTH && it.none(Char::isISOControl) })
    }

    fun all(values: Iterable<String?>): List<String> = values.flatMap { all(it) }.distinct()

    const val MAX_INPUT_LENGTH = 255
    const val MAX_STORED_LENGTH = 32
    const val MAX_CODES = 100
}
