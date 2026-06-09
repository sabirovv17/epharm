package kz.epharm.shared

/**
 * Валидация ИИН РК (физлицо) — структура + контрольная сумма (защита от опечаток).
 *
 * Формат (12 цифр):
 *  - [0..5]  дата рождения ГГММДД;
 *  - [6]     век + пол: 1,2 → XIX век; 3,4 → XX; 5,6 → XXI (нечёт — муж, чёт — жен);
 *  - [7..10] порядковый номер;
 *  - [11]    контрольная цифра.
 *
 * Контрольная сумма: первые 11 цифр × веса [1..11], сумма % 11. Если остаток = 10 —
 * пересчёт с весами [3,4,5,6,7,8,9,10,11,1,2]; если снова 10 — ИИН недействителен.
 *
 * Это математическая проверка (формат + контроль): отсекает опечатки и явный мусор.
 * Фактическое существование ИИН в гос-реестре ею не гарантируется — это отдельная
 * интеграция с гос-API (будущая доработка), здесь не делается.
 */
object IinUtil {

    private val WEIGHTS_1 = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)
    private val WEIGHTS_2 = intArrayOf(3, 4, 5, 6, 7, 8, 9, 10, 11, 1, 2)
    private val TWELVE_DIGITS = Regex("\\d{12}")

    fun isValid(raw: String?): Boolean {
        val iin = raw?.trim() ?: return false
        if (!TWELVE_DIGITS.matches(iin)) return false
        if (!hasValidBirthDate(iin)) return false

        var control = checksum(iin, WEIGHTS_1)
        if (control == 10) control = checksum(iin, WEIGHTS_2)
        if (control == 10) return false
        return control == (iin[11] - '0')
    }

    private fun checksum(iin: String, weights: IntArray): Int {
        var sum = 0
        for (i in 0 until 11) sum += (iin[i] - '0') * weights[i]
        return sum % 11
    }

    private fun hasValidBirthDate(iin: String): Boolean {
        val yy = iin.substring(0, 2).toInt()
        val mm = iin.substring(2, 4).toInt()
        val dd = iin.substring(4, 6).toInt()
        val centuryDigit = iin[6] - '0'

        val century = when (centuryDigit) {
            1, 2 -> 1800
            3, 4 -> 1900
            5, 6 -> 2000
            else -> return false // 0/7/8/9 в 7-й позиции — невалидный ИИН физлица
        }
        if (mm !in 1..12) return false
        val daysInMonth = when (mm) {
            1, 3, 5, 7, 8, 10, 12 -> 31
            4, 6, 9, 11 -> 30
            2 -> if (isLeapYear(century + yy)) 29 else 28
            else -> return false
        }
        return dd in 1..daysInMonth
    }

    private fun isLeapYear(year: Int): Boolean =
        (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
}
