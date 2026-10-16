package kz.epharm.mobile.catalog

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kz.epharm.medusa.MedusaCatalogCache
import kz.epharm.medusa.MedusaCatalogSnapshotRepository
import kz.epharm.medusa.client.MedusaClient
import kz.epharm.medusa.dto.MedusaCalculatedPrice
import kz.epharm.medusa.dto.MedusaProduct
import kz.epharm.medusa.dto.MedusaProductListResponse
import kz.epharm.medusa.dto.MedusaVariant
import kz.epharm.mobile.catalog.service.MobileCatalogService
import kz.epharm.promo.repository.PromoRepository
import kz.epharm.rules.repository.RuleRepository
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import kz.epharm.storefront.client.StorefrontCatalogClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class MobileCatalogSourcePriorityTest {
    private val medusa = mockk<MedusaClient>()
    private val promos = mockk<PromoRepository>(relaxed = true)
    private val rules = mockk<RuleRepository>(relaxed = true)
    private val snapshot = mockk<MedusaCatalogSnapshotRepository>()
    private val website = mockk<StorefrontCatalogClient>()

    @Test
    fun `uses website catalogue before local snapshot and retired medusa`() {
        every { website.active } returns true
        every { website.listProducts(any(), any(), any(), any()) } returns MedusaProductListResponse(
            products = listOf(product("site", 2_500.0)),
            count = 28_503,
            limit = 48,
            offset = 0,
        )
        val service = service()

        val page = service.search(null, null, 48, 0)

        assertThat(page.total).isEqualTo(28_503)
        assertThat(page.items.single().id).isEqualTo("site")
        assertThat(page.items.single().price).isEqualTo(2_500)
        verify(exactly = 0) { snapshot.hasCompleteSnapshot() }
        verify(exactly = 0) { medusa.listProducts(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `website outage falls back to complete snapshot without calling retired medusa`() {
        every { website.active } returns true
        every { website.listProducts(any(), any(), any(), any()) } throws AppException(
            ErrorCode.UPSTREAM_UNAVAILABLE,
            "site unavailable",
            HttpStatus.BAD_GATEWAY,
        )
        every { snapshot.hasCompleteSnapshot() } returns true
        every { snapshot.search(any(), any(), any(), any()) } returns
            MedusaCatalogSnapshotRepository.Page(listOf(product("snapshot", null)), 28_503)
        val service = service()

        val page = service.search("парацетамол", null, 48, 0)

        assertThat(page.total).isEqualTo(28_503)
        assertThat(page.items.single().id).isEqualTo("snapshot")
        verify(exactly = 0) { medusa.listProducts(any(), any(), any(), any(), any()) }
    }

    private fun service() = MobileCatalogService(
        medusa = medusa,
        cache = MedusaCatalogCache(0),
        promoRepository = promos,
        ruleRepository = rules,
        snapshot = snapshot,
        storefront = website,
    )

    private fun product(id: String, price: Double?) = MedusaProduct(
        id = id,
        title = "Товар $id",
        variants = price?.let {
            listOf(MedusaVariant(id = "variant-$id", calculatedPrice = MedusaCalculatedPrice(it, "KZT")))
        }.orEmpty(),
    )
}
