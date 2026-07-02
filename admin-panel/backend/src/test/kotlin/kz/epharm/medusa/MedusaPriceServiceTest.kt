package kz.epharm.medusa

import io.mockk.every
import io.mockk.mockk
import kz.epharm.medusa.client.MedusaClient
import kz.epharm.medusa.dto.MedusaPharmacyPrice
import kz.epharm.medusa.dto.MedusaProduct
import kz.epharm.medusa.dto.MedusaProductPharmaciesResponse
import kz.epharm.medusa.dto.MedusaVariant
import kz.epharm.medusa.service.MedusaPriceService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MedusaPriceServiceTest {
    private val medusa = mockk<MedusaClient>()
    private val service = MedusaPriceService(medusa)

    @Test
    fun `priceOf fallback берет медианную retail-цену из pharmacy pricing`() {
        every { medusa.active } returns true
        every { medusa.getProduct("prod_1") } returns MedusaProduct(
            id = "prod_1",
            variants = listOf(MedusaVariant(id = "v1", calculatedPrice = null)),
        )
        every { medusa.getProductPharmacies("prod_1") } returns MedusaProductPharmaciesResponse(
            productId = "prod_1",
            currency = "KZT",
            pharmacyCount = 3,
            pharmacies = listOf(
                MedusaPharmacyPrice(2060.0),
                MedusaPharmacyPrice(5775.0),
                MedusaPharmacyPrice(6670.0),
            ),
        )

        assertEquals(5775L, service.priceOf("prod_1"))
    }
}
