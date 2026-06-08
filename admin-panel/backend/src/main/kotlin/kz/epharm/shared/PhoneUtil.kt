package kz.epharm.shared

/**
 * Нормализация телефонов в канонический E.164 для Казахстана: `+7XXXXXXXXXX`.
 *
 * Зачем: мобильное приложение шлёт номер в маске `+7 (XXX) XXX-XX-XX`, сидер пишет в том же
 * формате, человек может ввести через 8 или без скобок. Чтобы `findByPhone` находил запись
 * вне зависимости от форматирования — приводим ОБА конца (запись в БД и lookup) к одному виду.
 */
object PhoneUtil {

    /**
     * Возвращает `+7` + 10 цифр для валидных KZ-номеров. Для нераспознанных — `+` + все цифры
     * (best-effort, не теряем данные). Бросать исключение здесь не нужно: формат проверяет
     * DTO-валидация на входе.
     */
    fun normalize(raw: String): String {
        val digits = raw.filter(Char::isDigit)
        val ten = when {
            // 11 цифр, ведущая 7 (например +7 700…) или 8 (8 700…) → берём последние 10.
            digits.length == 11 && (digits.startsWith("7") || digits.startsWith("8")) -> digits.substring(1)
            digits.length == 10 -> digits
            else -> return "+$digits"
        }
        return "+7$ten"
    }

    /** Маскированный вид для ответов/логов: `+7 (700) ***-**-NN`. Не светим весь номер. */
    fun mask(normalized: String): String {
        val digits = normalized.filter(Char::isDigit)
        if (digits.length != 11) return normalized
        val area = digits.substring(1, 4)
        val last = digits.substring(9, 11)
        return "+7 ($area) ***-**-$last"
    }
}
