package kz.epharm.shared

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class IinUtilTest {

    @Test
    fun `валидные ИИН проходят (правильная контрольная сумма)`() {
        // Сгенерированы по алгоритму (формат + дата + контрольная цифра).
        listOf(
            "900115300013",
            "850615400016",
            "990303500014",
            "781122300017",
            "951212500015",
        ).forEach { assertThat(IinUtil.isValid(it)).`as`(it).isTrue() }
    }

    @Test
    fun `неверная контрольная сумма отклоняется`() {
        // Те же первые 11 цифр, но подменённая 12-я.
        assertThat(IinUtil.isValid("900115300010")).isFalse()
        assertThat(IinUtil.isValid("850615400019")).isFalse()
        // Старые seed-значения, которые были невалидны по контрольной сумме.
        assertThat(IinUtil.isValid("990101300123")).isFalse()
        assertThat(IinUtil.isValid("950101000001")).isFalse()
    }

    @Test
    fun `неверный формат отклоняется`() {
        assertThat(IinUtil.isValid(null)).isFalse()
        assertThat(IinUtil.isValid("")).isFalse()
        assertThat(IinUtil.isValid("12345")).isFalse()           // короткий
        assertThat(IinUtil.isValid("9001153000130")).isFalse()   // 13 цифр
        assertThat(IinUtil.isValid("90011530001a")).isFalse()    // буква
        assertThat(IinUtil.isValid("900115 30001")).isFalse()    // пробел
    }

    @Test
    fun `невалидная дата рождения отклоняется`() {
        assertThat(IinUtil.isValid("901315300013")).isFalse()    // месяц 13
        assertThat(IinUtil.isValid("900015300013")).isFalse()    // месяц 00
        assertThat(IinUtil.isValid("900132300013")).isFalse()    // день 32
        assertThat(IinUtil.isValid("900229300013")).isFalse()    // 1990 не високосный → 29 фев нет
    }

    @Test
    fun `невалидная цифра века-пола отклоняется`() {
        // 7-я цифра должна быть 1..6 (0/7/8/9 — не ИИН физлица).
        assertThat(IinUtil.isValid("900115000013")).isFalse()
        assertThat(IinUtil.isValid("900115700013")).isFalse()
    }

    @Test
    fun `пробелы по краям обрезаются`() {
        assertThat(IinUtil.isValid("  900115300013  ")).isTrue()
    }
}
