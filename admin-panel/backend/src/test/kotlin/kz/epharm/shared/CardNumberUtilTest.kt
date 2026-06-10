package kz.epharm.shared

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CardNumberUtilTest {

    @Test
    fun `валидные по Луну номера проходят (с форматированием и без)`() {
        assertTrue(CardNumberUtil.isValid("4242424242424242"))      // Visa 16
        assertTrue(CardNumberUtil.isValid("5555 5555 5555 4444"))   // Mastercard с пробелами
        assertTrue(CardNumberUtil.isValid("4111-1111-1111-1111"))   // Visa с дефисами
        assertTrue(CardNumberUtil.isValid("378282246310005"))       // Amex 15
        assertTrue(CardNumberUtil.isValid("4222222222222"))         // Visa 13 (нижняя граница длины)
    }

    @Test
    fun `неверная контрольная сумма не проходит`() {
        assertFalse(CardNumberUtil.isValid("4242424242424241"))
        assertFalse(CardNumberUtil.isValid("1234567812345678"))
    }

    @Test
    fun `длина вне диапазона 13-19 не проходит`() {
        assertFalse(CardNumberUtil.isValid("424242424242"))            // 12 цифр
        assertFalse(CardNumberUtil.isValid("42424242424242424242"))    // 20 цифр
    }

    @Test
    fun `null, пусто и нецифры не проходят`() {
        assertFalse(CardNumberUtil.isValid(null))
        assertFalse(CardNumberUtil.isValid(""))
        assertFalse(CardNumberUtil.isValid("   "))
        assertFalse(CardNumberUtil.isValid("abcd efgh ijkl mnop"))
    }

    @Test
    fun `last4 возвращает последние 4 цифры либо null`() {
        assertEquals("3456", CardNumberUtil.last4("1234 5678 9012 3456"))
        assertEquals("0008", CardNumberUtil.last4("4000-0056-0000-0008"))
        assertNull(CardNumberUtil.last4("12"))
        assertNull(CardNumberUtil.last4(null))
    }
}
