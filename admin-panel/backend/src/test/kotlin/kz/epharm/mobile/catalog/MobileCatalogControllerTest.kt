package kz.epharm.mobile.catalog

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kz.epharm.mobile.catalog.controller.MobileCatalogController
import kz.epharm.mobile.catalog.dto.MobileCatalogDetailDto
import kz.epharm.mobile.catalog.dto.MobileCatalogPageDto
import kz.epharm.mobile.catalog.service.MobileCatalogService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MobileCatalogControllerTest {
    private val service = mockk<MobileCatalogService>()
    private val controller = MobileCatalogController(service)

    @Test
    fun `product list loads retail pharmacy prices`() {
        val expected = MobileCatalogPageDto(items = emptyList(), total = 0, limit = 48, offset = 0)
        every {
            service.search(
                q = null,
                category = null,
                limit = 48,
                offset = 0,
                includeRetailFallbackPrices = true,
            )
        } returns expected

        val actual = controller.products(q = null, category = null, limit = 48, offset = 0)

        assertThat(actual).isSameAs(expected)
        verify(exactly = 1) {
            service.search(null, null, 48, 0, includeRetailFallbackPrices = true)
        }
    }

    @Test
    fun `product detail loads retail pharmacy price`() {
        val expected = mockk<MobileCatalogDetailDto>()
        every {
            service.detail(
                id = "prod-1",
                includeIncentive = false,
                includeRetailFallbackPrices = true,
            )
        } returns expected

        val actual = controller.product(id = "prod-1", principal = null)

        assertThat(actual).isSameAs(expected)
        verify(exactly = 1) {
            service.detail("prod-1", includeIncentive = false, includeRetailFallbackPrices = true)
        }
    }
}
