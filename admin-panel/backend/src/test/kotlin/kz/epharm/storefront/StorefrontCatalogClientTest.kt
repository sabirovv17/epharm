package kz.epharm.storefront

import kz.epharm.storefront.client.StorefrontCatalogClient
import kz.epharm.storefront.dto.StorefrontProduct
import kz.epharm.storefront.dto.StorefrontVariant
import kz.epharm.storefront.dto.toMedusaProduct
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test

class StorefrontCatalogClientTest {
    @Test
    fun `maps website price metadata and relative media to mobile catalogue model`() {
        val mapped = StorefrontProduct(
            id = "prod_123",
            slug = "paracetamol-500",
            name = "Парацетамол 500 мг",
            brand = "Inkar",
            categorySlug = "pain-relief",
            categoryHandles = listOf("medicines", "pain-relief"),
            price = 1_290.0,
            image = "/api/media/medusa?path=%2Fstatic%2Fparacetamol.jpg",
            prescription = false,
            manufacturer = "Test Pharma",
            country = "Казахстан",
            mnn = "Парацетамол",
            barcode = "4870000000001",
            variants = listOf(
                StorefrontVariant(id = "variant_123", sku = "SKU-123", barcode = "4870000000001"),
            ),
        ).toMedusaProduct("https://shop.example.kz")

        assertThat(mapped.id).isEqualTo("prod_123")
        assertThat(mapped.thumbnail).isEqualTo(
            "https://shop.example.kz/api/media/medusa?path=%2Fstatic%2Fparacetamol.jpg",
        )
        assertThat(mapped.variants.single().calculatedPrice?.calculatedAmount).isEqualTo(1_290.0)
        assertThat(mapped.variants.single().calculatedPrice?.currencyCode).isEqualTo("KZT")
        assertThat(mapped.metadata).containsEntry("brand_name", "Inkar")
        assertThat(mapped.metadata).containsEntry("rx_otc", "otc")
        assertThat(mapped.categories.map { it.handle }).containsExactly("pain-relief", "medicines")
    }

    @Test
    fun `does not publish placeholder website price`() {
        val mapped = StorefrontProduct(
            id = "prod_no_price",
            slug = "unknown-price",
            name = "Товар без цены",
            price = 0.0,
            priceTbd = true,
        ).toMedusaProduct("https://shop.example.kz")

        assertThat(mapped.variants).isEmpty()
    }

    @Test
    fun `allows https production origin and loopback http only`() {
        assertThat(
            StorefrontCatalogClient.validateBaseUrl(true, "https://shop.example.kz/", 2_000, 8_000),
        ).isEqualTo("https://shop.example.kz")
        assertThat(
            StorefrontCatalogClient.validateBaseUrl(true, "http://127.0.0.1:3000", 2_000, 8_000),
        ).isEqualTo("http://127.0.0.1:3000")
        assertThat(
            StorefrontCatalogClient.validateBaseUrl(true, "http://www.aptekasosklada.kz", 2_000, 8_000),
        ).isEqualTo("http://www.aptekasosklada.kz")
        assertThatIllegalArgumentException().isThrownBy {
            StorefrontCatalogClient.validateBaseUrl(true, "http://10.10.1.80", 2_000, 8_000)
        }
        assertThatIllegalArgumentException().isThrownBy {
            StorefrontCatalogClient.validateBaseUrl(true, "https://shop.example.kz/api", 2_000, 8_000)
        }
    }

    @Test
    fun `disabled website source does not require a url`() {
        assertThat(StorefrontCatalogClient.validateBaseUrl(false, "", 2_000, 8_000)).isNull()
    }
}
