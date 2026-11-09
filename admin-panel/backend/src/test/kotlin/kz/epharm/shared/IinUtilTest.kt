package kz.epharm.shared

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class IinUtilTest {

    @Test
    fun `валидные ИИН проходят (правильная контрольная сумма)`() {
        // Synthetic checksum-valid identifiers; not proof of government issuance.
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
    fun `цифры ИИН не интерпретируются как дата век или пол`() {
        // Deliberately non-date prefix and every seventh digit, all with valid checksums.
        listOf("123456012344", "123456112340", "123456212347", "123456312343",
            "123456412343", "123456512346", "123456612342", "123456712349",
            "123456812345", "123456912341")
            .forEach { assertThat(IinUtil.isValid(it)).`as`(it).isTrue() }
    }

    @Test
    fun `сохраняется запрет пустого идентификатора`() {
        assertThat(IinUtil.isValid("000000000000")).isFalse()
        assertThat(IinUtil.isValid(" 000000000000 ")).isFalse()
    }

    @Test
    fun `второй набор весов используется только при остатке десять`() {
        assertThat(IinUtil.isValid("123456000405")).isTrue()
        assertThat(IinUtil.isValid("123456000400")).isFalse()
        (0..9).forEach { assertThat(IinUtil.isValid("12345600048$it")).isFalse() }
    }

    @Test
    fun `принимаются только цифры ASCII`() {
        assertThat(IinUtil.isValid("１２３４５６０１２３４４")).isFalse()
        assertThat(IinUtil.isValid("١٢٣٤٥٦٠١٢٣٤٤")).isFalse()
    }

    @Test
    fun `пробелы по краям обрезаются`() {
        assertThat(IinUtil.isValid("  900115300013  ")).isTrue()
    }
}
