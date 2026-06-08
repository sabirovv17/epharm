package kz.epharm.shared

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Чистый unit-тест нормализации телефонов — без Spring/БД. */
class PhoneUtilTest {

    @Test
    fun `маска +7 (XXX) XXX-XX-XX нормализуется в E164`() {
        assertThat(PhoneUtil.normalize("+7 (701) 234-56-78")).isEqualTo("+77012345678")
    }

    @Test
    fun `формат сидера нормализуется одинаково`() {
        // Так пишет DevDataSeeder для i!=0.
        assertThat(PhoneUtil.normalize("+7 (710) 100-10-20")).isEqualTo("+77101001020")
    }

    @Test
    fun `ведущая 8 трактуется как 7`() {
        assertThat(PhoneUtil.normalize("8 701 234 56 78")).isEqualTo("+77012345678")
    }

    @Test
    fun `десять цифр без кода получают +7`() {
        assertThat(PhoneUtil.normalize("7012345678")).isEqualTo("+77012345678")
    }

    @Test
    fun `уже нормализованный остаётся прежним`() {
        assertThat(PhoneUtil.normalize("+77012345678")).isEqualTo("+77012345678")
    }

    @Test
    fun `разное форматирование одного номера даёт один результат`() {
        val a = PhoneUtil.normalize("+7 (700) 000-00-01")
        val b = PhoneUtil.normalize("8-700-000-00-01")
        val c = PhoneUtil.normalize("+77000000001")
        assertThat(a).isEqualTo(b)
        assertThat(b).isEqualTo(c)
    }

    @Test
    fun `mask прячет середину номера`() {
        assertThat(PhoneUtil.mask("+77012345678")).isEqualTo("+7 (701) ***-**-78")
    }
}
