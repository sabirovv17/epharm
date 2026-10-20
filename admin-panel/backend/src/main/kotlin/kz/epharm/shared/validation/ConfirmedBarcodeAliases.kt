package kz.epharm.shared.validation

import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus

/**
 * Plural keys must belong to the selected source product or its saved identity.
 * The legacy editable primary field remains supported; a browser-provided alias
 * array alone is never evidence that unrelated catalogue items are identical.
 */
object ConfirmedBarcodeAliases {
    fun merge(
        requested: List<String> = emptyList(),
        primary: String? = null,
        source: List<String> = emptyList(),
        existing: List<String> = emptyList(),
        sourceComplete: Boolean = false,
    ): List<String> {
        if (requested.size > BarcodeNormalizer.MAX_CODES ||
            requested.any { it.trim().length > BarcodeNormalizer.MAX_STORED_LENGTH || it.isBlank() }
        ) invalid("Не более 100 непустых штрихкодов длиной до 32 символов")
        val primaryCode = BarcodeNormalizer.first(primary)
        val sourceCodes = BarcodeNormalizer.all(source)
        val authoritative = sourceComplete || sourceCodes.isNotEmpty()
        val confirmed = if (authoritative) sourceCodes
            else BarcodeNormalizer.all(existing + listOf(primaryCode))
        val clean = BarcodeNormalizer.all(requested)
        if (clean.any { it !in confirmed } ||
            (authoritative && primaryCode != null && primaryCode !in confirmed)
        ) {
            invalid("Штрихкоды не подтверждены каталогом выбранного товара. Обновите выбор товара.")
        }
        val result = BarcodeNormalizer.all(listOf(primaryCode) + clean + confirmed)
        if (result.size > BarcodeNormalizer.MAX_CODES) invalid("У товара слишком много штрихкодов")
        return result
    }

    private fun invalid(message: String): Nothing =
        throw AppException(ErrorCode.VALIDATION_FAILED, message, HttpStatus.BAD_REQUEST)
}
