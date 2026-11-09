package kz.epharm.shared

/**
 * Проверка ИИН: 12 цифр ASCII и контрольная сумма (защита от опечаток).
 *
 * Дата рождения, век и пол не выводятся из цифр ИИН:
 * https://www.gov.kz/org/migration/press/news/details/132237?lang=ru
 *
 * Контрольная сумма: первые 11 цифр × веса [1..11], сумма % 11. Если остаток = 10 —
 * пересчёт с весами [3,4,5,6,7,8,9,10,11,1,2]; если снова 10 — ИИН недействителен.
 *
 * Это математическая проверка (формат + контроль), а не проверка личности.
 * Фактическое существование ИИН в гос-реестре ею не гарантируется — это отдельная
 * интеграция с гос-API (будущая доработка), здесь не делается.
 */
object IinUtil {

    private val WEIGHTS_1 = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)
    private val WEIGHTS_2 = intArrayOf(3, 4, 5, 6, 7, 8, 9, 10, 11, 1, 2)
    private val TWELVE_DIGITS = Regex("[0-9]{12}")

    fun isValid(raw: String?): Boolean {
        val iin = raw?.trim() ?: return false
        // Preserve the existing rejection of the empty identifier, independently of demographics.
        if (!TWELVE_DIGITS.matches(iin) || iin == "000000000000") return false

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

}
