package kz.epharm.shared.validation

import kz.epharm.shared.error.AppException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ConfirmedBarcodeAliasesTest {
    @Test
    fun `complete source replaces former aliases rather than making them permanent`() {
        assertEquals(
            listOf("0000000012345", "4870223140649"),
            ConfirmedBarcodeAliases.merge(
                source = listOf("0000000012345", "4870223140649"),
                existing = listOf("LOCAL-42"),
            ),
        )
    }

    @Test
    fun `browser alias alone cannot join a different product`() {
        assertThrows(AppException::class.java) {
            ConfirmedBarcodeAliases.merge(
                requested = listOf("4870223140694"), source = listOf("4870223140649"),
            )
        }
    }

    @Test
    fun `legacy compound scalar cannot assert unverified secondary aliases`() {
        assertEquals(
            listOf("4607045191357"),
            ConfirmedBarcodeAliases.merge(primary = "4607045191357_4870223140649_4870223140694"),
        )
        assertThrows(AppException::class.java) {
            ConfirmedBarcodeAliases.merge(
                primary = "4607045191357_4870223140649", requested = listOf("4870223140649"),
            )
        }
    }

    @Test
    fun `outage preserves saved keys but a complete empty source explicitly revokes them`() {
        val saved = listOf("0000000012345", "LOCAL-42")
        assertEquals(saved, ConfirmedBarcodeAliases.merge(existing = saved))
        assertEquals(emptyList<String>(), ConfirmedBarcodeAliases.merge(existing = saved, sourceComplete = true))
    }

    @Test
    fun `malformed plural field and oversized lists fail rather than truncate`() {
        for (codes in listOf(listOf(""), listOf("x".repeat(33)), List(101) { "LOCAL-$it" })) {
            assertThrows(AppException::class.java) { ConfirmedBarcodeAliases.merge(requested = codes) }
        }
    }
}
