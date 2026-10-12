package kz.epharm.mobile.catalog

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kz.epharm.medusa.MedusaCatalogCache
import kz.epharm.medusa.MedusaCatalogSnapshotRepository
import kz.epharm.medusa.client.MedusaClient
import kz.epharm.medusa.dto.MedusaCategory
import kz.epharm.medusa.dto.MedusaProduct
import kz.epharm.medusa.dto.MedusaProductListResponse
import kz.epharm.mobile.catalog.service.MobileCatalogService
import kz.epharm.promo.repository.PromoRepository
import kz.epharm.rules.repository.RuleRepository
import kz.epharm.storefront.client.StorefrontCatalogClient
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MobileCatalogSourcePriorityTest {
    private val medusa = mockk<MedusaClient>()
    private val promos = mockk<PromoRepository>(relaxed = true)
    private val rules = mockk<RuleRepository>(relaxed = true)
    private val snapshot = mockk<MedusaCatalogSnapshotRepository>()
    private val website = mockk<StorefrontCatalogClient>()

    @Test
    fun `website list is preferred only while enabled`() {
        every { website.active } returns true
        every { website.listProducts(any(), any(), any(), any()) } returns MedusaProductListResponse(
            products = listOf(product("site")), count = 28_503, limit = 48, offset = 0,
        )
        val page = service().search(null, null, 48, 0)
        assertThat(page.total).isEqualTo(28_503)
        assertThat(page.items.single().id).isEqualTo("site")
        verify(exactly = 0) { snapshot.hasCompleteSnapshot() }
        verify(exactly = 0) { medusa.listProducts(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `website product without a price does not call retired Medusa for a fallback price`() {
        every { website.active } returns true
        every { website.listProducts(any(), any(), any(), any()) } returns MedusaProductListResponse(
            products = listOf(product("site-only")), count = 1, limit = 48, offset = 0,
        )

        assertThat(service().search(null, null, 48, 0, includeRetailFallbackPrices = true).items.single().price)
            .isNull()
        verify(exactly = 0) { medusa.getProductPharmacies(any()) }
    }

    @Test
    fun `website outage retains filtered snapshot search and categories`() {
        every { website.active } returns true
        every { website.listProducts(any(), any(), any(), any()) } throws IllegalStateException("site down")
        every { website.listCategories() } throws IllegalStateException("site down")
        every { snapshot.hasCompleteSnapshot() } returns true
        every { snapshot.search(any(), any(), any(), any()) } returns
            MedusaCatalogSnapshotRepository.Page(listOf(product("snapshot")), 1)
        every { snapshot.categories() } returns listOf(MedusaCategory(id = "cat_1", name = "Категория"))

        val service = service()
        val page = service.search("уголь", "cat_1", 48, 0)
        val categories = service.categories()

        assertThat(page.items.single().id).isEqualTo("snapshot")
        assertThat(categories.single().id).isEqualTo("cat_1")
        verify(exactly = 1) { snapshot.search("уголь", "cat_1", 48, 0) }
        verify(exactly = 0) { medusa.listProducts(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { medusa.listCategories() }
    }

    @Test
    fun `website detail with different id never replaces requested snapshot product`() {
        every { website.active } returns true
        every { snapshot.hasCompleteSnapshot() } returns true
        every { snapshot.findById("prod_1") } returns product("prod_1")
        every { website.getProduct("prod_1", any()) } returns product("wrong_id")

        val detail = service().detail("prod_1")

        assertThat(detail.id).isEqualTo("prod_1")
        verify(exactly = 0) { medusa.getProduct(any()) }
    }

    @Test
    fun `promotion cards retain complete snapshot during website outage`() {
        every { website.active } returns true
        every { website.cachedProduct(any()) } returns null
        every { snapshot.hasCompleteSnapshot() } returns true
        every { snapshot.findByIds(listOf("prod_1")) } returns listOf(product("prod_1"))

        val cards = service().cardsByIds(listOf("prod_1"))

        assertThat(cards.keys).containsExactly("prod_1")
        verify(exactly = 0) { medusa.listProducts(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `disabled website source keeps current snapshot behavior`() {
        every { website.active } returns false
        every { snapshot.hasCompleteSnapshot() } returns true
        every { snapshot.search(any(), any(), any(), any()) } returns
            MedusaCatalogSnapshotRepository.Page(listOf(product("snapshot")), 1)

        assertThat(service().search(null, null, 48, 0).items.single().id).isEqualTo("snapshot")
        verify(exactly = 0) { website.listProducts(any(), any(), any(), any()) }
    }

    @Test
    fun `website category handle resolves to snapshot category id during outage`() {
        every { website.active } returns true
        every { website.listProducts(any(), any(), any(), any()) } throws IllegalStateException("site down")
        every { snapshot.hasCompleteSnapshot() } returns true
        every { snapshot.categories() } returns listOf(
            MedusaCategory(id = "pcat_old", name = "Лекарства", handle = "medicines"),
        )
        every { snapshot.search(any(), any(), any(), any()) } returns
            MedusaCatalogSnapshotRepository.Page(listOf(product("snapshot")), 1)

        assertThat(service().search(null, "medicines", 48, 0).items.single().id).isEqualTo("snapshot")
        verify(exactly = 1) { snapshot.search(null, "pcat_old", 48, 0) }
    }

    @Test
    fun `unknown site category does not silently return an empty snapshot page`() {
        every { website.active } returns true
        every { website.listProducts(any(), any(), any(), any()) } throws IllegalStateException("site down")
        every { snapshot.hasCompleteSnapshot() } returns true
        every { snapshot.categories() } returns listOf(
            MedusaCategory(id = "pcat_old", name = "Лекарства", handle = "medicines"),
        )

        assertThatThrownBy { service().search(null, "site-only-category", 48, 0) }
            .hasMessageContaining("резервном каталоге")
        verify(exactly = 0) { snapshot.search(any(), any(), any(), any()) }
        verify(exactly = 0) { medusa.listProducts(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `enabled website without snapshot fails promptly rather than calling retired Medusa`() {
        every { website.active } returns true
        every { website.listProducts(any(), any(), any(), any()) } throws IllegalStateException("site down")
        every { snapshot.hasCompleteSnapshot() } returns false

        assertThatThrownBy { service().search(null, null, 48, 0) }
            .hasMessageContaining("временно недоступен")
        verify(exactly = 0) { medusa.listProducts(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `cold website-only detail without a known slug does not call retired Medusa`() {
        every { website.active } returns true
        every { snapshot.hasCompleteSnapshot() } returns true
        every { snapshot.findById("site-only") } returns null
        every { website.getProduct("site-only", null) } returns null

        assertThatThrownBy { service().detail("site-only") }
            .hasMessageContaining("временно недоступен")
        verify(exactly = 0) { medusa.getProduct(any()) }
    }

    @Test
    fun `website categories without snapshot fail promptly rather than calling retired Medusa`() {
        every { website.active } returns true
        every { website.listCategories() } throws IllegalStateException("site down")
        every { snapshot.hasCompleteSnapshot() } returns false

        assertThatThrownBy { service().categories() }
            .hasMessageContaining("временно недоступны")
        verify(exactly = 0) { medusa.listCategories() }
    }

    private fun service() = MobileCatalogService(
        medusa = medusa,
        cache = MedusaCatalogCache(0),
        promoRepository = promos,
        ruleRepository = rules,
        snapshot = snapshot,
        storefront = website,
    )

    private fun product(id: String) = MedusaProduct(id = id, title = "Товар $id")
}
