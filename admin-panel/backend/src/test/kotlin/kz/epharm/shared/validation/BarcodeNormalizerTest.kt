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
}
