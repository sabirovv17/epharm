package kz.epharm.shared

/**
 * Валидация номера банковской карты — длина (13–19 цифр) + алгоритм Луна
 * (контрольная сумма, защита от опечаток). Это математическая проверка: отсекает
 * мусор и описки. Действующую/активную карту она не гарантирует — это уже эквайринг.
 *
 * Зеркало мобильной `lib/core/validation/card.dart` — один и тот же контракт на
 * клиенте и сервере, чтобы при будущем сохранении реквизитов выплаты данные не
 * расходились. PAN целиком НЕ храним (PCI) — только маска [last4].
 */
object CardNumberUtil {

    fun isValid(raw: String?): Boolean {
        val digits = raw?.filter { it.isDigit() } ?: return false
        if (digits.length < 13 || digits.length > 19) return false
        return luhnValid(digits)
    }

    /** Последние 4 цифры для маскированного отображения «•••• 1234». */
    fun last4(raw: String?): String? {
        val digits = raw?.filter { it.isDigit() } ?: return null
        return if (digits.length >= 4) digits.takeLast(4) else null
    }

    private fun luhnValid(digits: String): Boolean {
        var sum = 0
        var alternate = false
        for (i in digits.indices.reversed()) {
            var d = digits[i] - '0'
            if (alternate) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
            alternate = !alternate
        }
        return sum % 10 == 0
    }
}
