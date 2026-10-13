package kz.epharm.medusa

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kz.epharm.medusa.client.MedusaClient
import kz.epharm.medusa.dto.MedusaCalculatedPrice
import kz.epharm.medusa.dto.MedusaImage
import kz.epharm.medusa.dto.MedusaPharmacyPrice
import kz.epharm.medusa.dto.MedusaProduct
import kz.epharm.medusa.dto.MedusaProductPharmaciesResponse
import kz.epharm.medusa.dto.MedusaVariant
import kz.epharm.medusa.service.MedusaPriceService
import kz.epharm.storefront.client.StorefrontCatalogClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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

    @Test
    fun `snapshot normalizes compound Medusa barcode before promo persistence`() {
        every { medusa.active } returns true
        every { medusa.getProduct("prod_teraflu") } returns MedusaProduct(
            id = "prod_teraflu",
            variants = listOf(
                MedusaVariant(
                    id = "v1",
                    barcode = "4607045191357_4870223140649_4870223140694",
                ),
            ),
        )
        every { medusa.getProductPharmacies("prod_teraflu") } returns null

        assertEquals("4607045191357", service.snapshotOf("prod_teraflu")?.barcode)
    }

    @Test
    fun `website-only product supplies campaign price cover and barcode without legacy Medusa`() {
        val website = mockk<StorefrontCatalogClient>()
        every { website.active } returns true
        every { website.getProduct("prod_Daribar_1", null) } returns pricedProduct(
            "prod_Daribar_1", 485.0, "4871234567890",
        ).copy(thumbnail = "https://catalog.example/image.jpg")
        val service = MedusaPriceService(medusa, website)

        assertTrue(service.active)
        assertEquals(485L, service.priceOf("prod_Daribar_1"))
        assertEquals(
            MedusaPriceService.MedusaSnapshot(485L, "https://catalog.example/image.jpg", "4871234567890"),
            service.snapshotOf("prod_Daribar_1"),
        )
        verify(exactly = 0) { medusa.getProduct(any()) }
        verify(exactly = 0) { medusa.getProductPharmacies(any()) }
    }

    @Test
    fun `website price wins over historical snapshot and refresh uses the known handle`() {
        val website = mockk<StorefrontCatalogClient>()
        val snapshot = mockk<MedusaCatalogSnapshotRepository>()
        every { website.active } returns true
        every { snapshot.hasCompleteSnapshot() } returns true
        every { snapshot.findById("prod_1") } returns pricedProduct("prod_1", 400.0).copy(handle = "coal")
        every { website.getProduct("prod_1", "coal") } returns pricedProduct("prod_1", 485.0)

        assertEquals(485L, MedusaPriceService(medusa, website, snapshot).priceOf("prod_1"))
        verify(exactly = 0) { medusa.getProduct(any()) }
        verify(exactly = 0) { medusa.getProductPharmacies(any()) }
    }

    @Test
    fun `website outage without website reference does not write historical snapshot values`() {
        val website = mockk<StorefrontCatalogClient>()
        val snapshot = mockk<MedusaCatalogSnapshotRepository>()
        every { website.active } returns true
        every { snapshot.hasCompleteSnapshot() } returns true
        every { snapshot.findById("prod_1") } returns pricedProduct("prod_1", 400.0, "4871234567890")
        every { website.getProduct("prod_1", null) } throws IllegalStateException("website down")
        every { website.cachedProduct("prod_1") } returns null
        val service = MedusaPriceService(medusa, website, snapshot)

        assertNull(service.priceOf("prod_1"))
        assertNull(service.snapshotOf("prod_1"))
        verify(exactly = 0) { medusa.getProduct(any()) }
        verify(exactly = 0) { medusa.getProductPharmacies(any()) }
    }

    @Test
    fun `website outage prefers cached website product over old snapshot`() {
        val website = mockk<StorefrontCatalogClient>()
        val snapshot = mockk<MedusaCatalogSnapshotRepository>()
        every { website.active } returns true
        every { snapshot.hasCompleteSnapshot() } returns true
        every { snapshot.findById("prod_1") } returns pricedProduct("prod_1", 400.0)
        every { website.getProduct("prod_1", null) } throws IllegalStateException("website down")
        every { website.cachedProduct("prod_1") } returns pricedProduct("prod_1", 485.0)

        assertEquals(485L, MedusaPriceService(medusa, website, snapshot).priceOf("prod_1"))
        verify(exactly = 0) { medusa.getProduct(any()) }
        verify(exactly = 0) { medusa.getProductPharmacies(any()) }
    }

    @Test
    fun `unavailable website-only lookup returns null to retain previous campaign price`() {
        val website = mockk<StorefrontCatalogClient>()
        every { website.active } returns true
        every { website.getProduct("prod_Daribar_1", null) } returns null
        every { website.cachedProduct("prod_Daribar_1") } returns null
        val service = MedusaPriceService(medusa, website)

        assertNull(service.priceOf("prod_Daribar_1"))
        assertNull(service.snapshotOf("prod_Daribar_1"))
        verify(exactly = 0) { medusa.getProduct(any()) }
        verify(exactly = 0) { medusa.getProductPharmacies(any()) }
    }

    @Test
    fun `website metadata barcode is used when product has no variants or price`() {
        val website = mockk<StorefrontCatalogClient>()
        every { website.active } returns true
        every { website.getProduct("prod_Daribar_1", null) } returns MedusaProduct(
            id = "prod_Daribar_1",
            images = listOf(MedusaImage(url = "https://catalog.example/image.jpg")),
            metadata = mapOf("barcode" to "4607045191357_4870223140649"),
        )

        assertEquals(
            MedusaPriceService.MedusaSnapshot(null, "https://catalog.example/image.jpg", "4607045191357"),
            MedusaPriceService(medusa, website).snapshotOf("prod_Daribar_1"),
        )
        verify(exactly = 0) { medusa.getProductPharmacies(any()) }
    }

    @Test
    fun `website missing barcode is preserved as unknown even for familiar product name`() {
        val website = mockk<StorefrontCatalogClient>()
        every { website.active } returns true
        every { website.getProduct("prod_Daribar_1", null) } returns
            pricedProduct("prod_Daribar_1", 485.0).copy(title = "Активированный уголь")

        assertNull(MedusaPriceService(medusa, website).snapshotOf("prod_Daribar_1")?.barcode)
    }

    @Test
    fun `complete local snapshot supplies data while all remote sources are disabled`() {
        val website = mockk<StorefrontCatalogClient>()
        val snapshot = mockk<MedusaCatalogSnapshotRepository>()
        every { website.active } returns false
        every { medusa.active } returns false
        every { snapshot.hasCompleteSnapshot() } returns true
        every { snapshot.findById("prod_1") } returns pricedProduct("prod_1", 400.0)
        val service = MedusaPriceService(medusa, website, snapshot)

        assertTrue(service.active)
        assertEquals(400L, service.priceOf("prod_1"))
        verify(exactly = 0) { website.getProduct(any(), any()) }
        verify(exactly = 0) { medusa.getProduct(any()) }
        verify(exactly = 0) { medusa.getProductPharmacies(any()) }
    }

    @Test
    fun `disabled website preserves legacy retail price fallback`() {
        val website = mockk<StorefrontCatalogClient>()
        every { website.active } returns false
        every { medusa.active } returns true
        every { medusa.getProduct("prod_1") } returns MedusaProduct(id = "prod_1")
        every { medusa.getProductPharmacies("prod_1") } returns MedusaProductPharmaciesResponse(
            productId = "prod_1", pharmacies = listOf(MedusaPharmacyPrice(5775.0)),
        )

        assertEquals(5775L, MedusaPriceService(medusa, website).priceOf("prod_1"))
        verify(exactly = 0) { website.getProduct(any(), any()) }
    }

    private fun pricedProduct(id: String, price: Double, barcode: String? = null) = MedusaProduct(
        id = id,
        variants = listOf(MedusaVariant(id = "v-$id", barcode = barcode,
            calculatedPrice = MedusaCalculatedPrice(calculatedAmount = price, currencyCode = "KZT"))),
    )
}
