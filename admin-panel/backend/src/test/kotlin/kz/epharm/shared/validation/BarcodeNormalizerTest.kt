package kz.epharm.shared.validation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BarcodeNormalizerTest {
    @Test
    fun `compound Medusa barcode resolves to first GTIN`() {
        assertEquals(
            "4607045191357",
            BarcodeNormalizer.first("4607045191357_4870223140649_4870223140694"),
        )
    }

    @Test
    fun `single barcode and empty values remain compatible`() {
        assertEquals("4603423004936", BarcodeNormalizer.first(" 4603423004936 "))
        assertEquals("LOCAL-42", BarcodeNormalizer.first("LOCAL-42"))
        assertNull(BarcodeNormalizer.first("   "))
    }

    @Test
    fun `oversized non barcode value is rejected`() {
        assertNull(BarcodeNormalizer.first("x".repeat(BarcodeNormalizer.MAX_STORED_LENGTH + 1)))
    }

    @Test
    fun `all retains every alias with leading zeros and stable deduplication`() {
        assertEquals(
            listOf("0000000012345", "4870223140649", "4870223140694"),
            BarcodeNormalizer.all(listOf(" 0000000012345_4870223140649 ", "4870223140694", "4870223140649")),
        )
    }

    @Test
    fun `a longer local identifier is not truncated to an embedded GTIN`() {
        assertEquals(listOf("123456789012345"), BarcodeNormalizer.all("123456789012345"))
        assertEquals(listOf("ABC12345678XYZ"), BarcodeNormalizer.all("ABC12345678XYZ"))
        assertEquals(emptyList<String>(), BarcodeNormalizer.all(null))
    }
}
