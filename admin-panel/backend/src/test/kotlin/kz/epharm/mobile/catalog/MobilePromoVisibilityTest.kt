package kz.epharm.mobile.catalog

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kz.epharm.eshop.EshopCatalogSnapshotRepository
import kz.epharm.medusa.MedusaCatalogCache
import kz.epharm.medusa.client.MedusaClient
import kz.epharm.medusa.dto.MedusaCategory
import kz.epharm.medusa.dto.MedusaProduct
import kz.epharm.mobile.catalog.service.MobileCatalogService
import kz.epharm.mobile.promotions.service.MobilePromoEligibility
import kz.epharm.promo.entity.PromoEntity
import kz.epharm.promo.entity.PromoStatus
import kz.epharm.promo.entity.PromoTier
import kz.epharm.promo.repository.PromoRepository
import kz.epharm.rules.entity.RuleEntity
import kz.epharm.rules.entity.RuleStatus
import kz.epharm.rules.entity.RuleTrigger
import kz.epharm.rules.entity.RuleType
import kz.epharm.rules.repository.RuleRepository
import kz.epharm.shared.error.AppException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class MobilePromoVisibilityTest {
    private val medusa = mockk<MedusaClient>(relaxed = true)
    private val source = mockk<EshopCatalogSnapshotRepository>()
    private val promos = mockk<PromoRepository>(relaxed = true)
    private val rules = mockk<RuleRepository>(relaxed = true)
    private val service = MobileCatalogService(
        medusa, MedusaCatalogCache(0), promos, rules,
        eshop = source, eshopReadEnabled = true, adminEshopReadEnabled = true,
    )
    private val today = MobilePromoEligibility.today()

    private fun promo(id: String, productId: String, status: PromoStatus = PromoStatus.active,
                      start: LocalDate? = null, end: LocalDate? = null,
                      tiers: List<PromoTier> = listOf(PromoTier(1, 500, 50))) =
        PromoEntity(id = id, title = id, medusaProductId = productId).also {
            it.status = status; it.dateStart = start; it.dateEnd = end; it.tiers = tiers
        }

    private fun aliases(vararg pairs: Pair<String, String>) {
        val mapping = pairs.toMap()
        every { source.canonicalIds(any()) } answers {
            firstArg<Collection<String>>().mapNotNull { id -> mapping[id]?.let { id to it } }.toMap()
        }
    }

    @Test
    fun `mobile search paginates only current campaign IDs while admin keeps full master`() {
        val live = promo("pr_live", "old-live", start = today, end = today)
        val expired = promo("pr_expired", "old-expired", end = today.minusDays(1))
        val invalid = promo("pr_invalid", "old-invalid", tiers = listOf(PromoTier(1, 0, 50)))
        every { source.hasCompleteSnapshot() } returns true
        every { promos.findAllByStatusRawAndMedusaProductIdIsNotNullOrderByUpdatedAtDesc("active") } returns
            listOf(live, expired, invalid)
        aliases("old-live" to "prod_Daribar_live", "old-expired" to "prod_Daribar_expired")
        val item = EshopCatalogSnapshotRepository.CatalogItem(
            MedusaProduct(id = "prod_Daribar_live", title = "Товар акции"), true,
        )
        every { source.searchWithinIds("товар", null, 1, 1, setOf("prod_Daribar_live")) } returns
            EshopCatalogSnapshotRepository.Page(listOf(item), 2)
        every { source.search(null, null, 50, 0, false, null) } returns
            EshopCatalogSnapshotRepository.Page(listOf(item), 29_326)

        val mobile = service.search("товар", null, 1, 1)
        val admin = service.search(null, null, 50, 0, admin = true)

        assertEquals(2, mobile.total)
        assertEquals("prod_Daribar_live", mobile.items.single().id)
        assertEquals(29_326, admin.total)
        verify(exactly = 0) { medusa.listProducts(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `unpublished campaign product is absent from detail and category list`() {
        every { source.hasCompleteSnapshot() } returns true
        every { promos.findAllByStatusRawAndMedusaProductIdIsNotNullOrderByUpdatedAtDesc("active") } returns
            listOf(promo("pr_hidden", "old-hidden"))
        aliases("old-hidden" to "prod_Daribar_hidden")
        every { source.canonicalId("prod_Daribar_hidden") } returns "prod_Daribar_hidden"
        every { source.findById("prod_Daribar_hidden", true) } returns null
        every { source.categoriesWithinIds(setOf("prod_Daribar_hidden")) } returns emptyList()

        assertThrows(AppException::class.java) { service.detail("prod_Daribar_hidden") }
        assertTrue(service.categories().isEmpty())
        verify(exactly = 0) { medusa.getProduct(any()) }
    }

    @Test
    fun `published product without a current campaign has no mobile detail`() {
        every { source.hasCompleteSnapshot() } returns true
        every { promos.findAllByStatusRawAndMedusaProductIdIsNotNullOrderByUpdatedAtDesc("active") } returns
            emptyList()
        aliases()
        every { source.canonicalId("prod_Daribar_regular") } returns "prod_Daribar_regular"

        assertThrows(AppException::class.java) { service.detail("prod_Daribar_regular") }
        verify(exactly = 0) { source.findById("prod_Daribar_regular", true) }
        verify(exactly = 0) { medusa.getProduct(any()) }
    }

    @Test
    fun `recommendations expose only published products with current campaigns`() {
        val currentA = promo("pr_a", "old-a")
        val currentB = promo("pr_b", "old-b")
        val expiredC = promo("pr_c", "old-c", end = today.minusDays(1))
        every { source.hasCompleteSnapshot() } returns true
        every { promos.findAllByStatusRawAndMedusaProductIdIsNotNullOrderByUpdatedAtDesc("active") } returns
            listOf(currentA, currentB, expiredC)
        aliases(
            "old-a" to "prod_Daribar_a", "old-b" to "prod_Daribar_b",
            "old-c" to "prod_Daribar_c", "prod_Daribar_a" to "prod_Daribar_a",
            "prod_Daribar_b" to "prod_Daribar_b", "prod_Daribar_c" to "prod_Daribar_c",
        )
        every { source.canonicalId("prod_Daribar_a") } returns "prod_Daribar_a"
        every { source.canonicalId("prod_Daribar_c") } returns "prod_Daribar_c"
        every { source.findById("prod_Daribar_a", true) } returns
            EshopCatalogSnapshotRepository.CatalogItem(MedusaProduct(id = "prod_Daribar_a"), true)
        every { rules.findAllByStatusRawOrderByUpdatedAtDesc(RuleStatus.active.name) } returns listOf(
            RuleEntity(id = "r_b", recommend = "old-b", bonus = 50,
                trigger = RuleTrigger("product", "old-a")).also {
                it.type = RuleType.crosssell; it.status = RuleStatus.active
            },
            RuleEntity(id = "r_c", recommend = "old-c", bonus = 60,
                trigger = RuleTrigger("product", "old-a")).also {
                it.type = RuleType.crosssell; it.status = RuleStatus.active
            },
        )
        every { source.findByIds(setOf("prod_Daribar_b"), true) } returns listOf(
            EshopCatalogSnapshotRepository.CatalogItem(MedusaProduct(id = "prod_Daribar_b"), true),
        )

        val recs = service.recommendations("prod_Daribar_a", includeIncentive = true)
        assertEquals(listOf("prod_Daribar_b"), recs.crosssells.map { it.product.id })
        assertThrows(AppException::class.java) {
            service.recommendations("prod_Daribar_c", includeIncentive = false)
        }
    }

    @Test
    fun `admin detail can read unpublished product independently of mobile read switch`() {
        val adminOnly = MobileCatalogService(
            medusa, MedusaCatalogCache(0), promos, rules,
            eshop = source, eshopReadEnabled = false, adminEshopReadEnabled = true,
        )
        every { source.hasCompleteSnapshot() } returns true
        every { source.findById("prod_Daribar_draft", false) } returns
            EshopCatalogSnapshotRepository.CatalogItem(
                MedusaProduct(id = "prod_Daribar_draft", title = "Черновик"), false,
            )
        every { source.relatedIds("prod_Daribar_draft") } returns listOf("prod_Daribar_draft")
        every { promos.findAllByMedusaProductIdIn(listOf("prod_Daribar_draft")) } returns emptyList()

        val detail = adminOnly.detail("prod_Daribar_draft", admin = true)
        assertEquals("Черновик", detail.name)
        assertEquals(false, detail.published)
        verify(exactly = 0) { medusa.getProduct(any()) }
    }

    @Test
    fun `recommendation pools omit published products without a current campaign`() {
        every { source.hasCompleteSnapshot() } returns true
        every { promos.findAllByStatusRawAndMedusaProductIdIsNotNullOrderByUpdatedAtDesc("active") } returns
            listOf(promo("pr_current", "old-current"))
        aliases(
            "old-current" to "prod_Daribar_current",
            "old-regular" to "prod_Daribar_regular",
            "prod_Daribar_current" to "prod_Daribar_current",
            "prod_Daribar_regular" to "prod_Daribar_regular",
        )
        every { rules.findAllByStatusRawOrderByUpdatedAtDesc(RuleStatus.active.name) } returns listOf(
            RuleEntity(id = "r_current", recommend = "old-current", trigger = RuleTrigger("product", "old-regular")).also {
                it.type = RuleType.substitution; it.status = RuleStatus.active
            },
            RuleEntity(id = "r_regular", recommend = "old-regular", trigger = RuleTrigger("product", "old-current")).also {
                it.type = RuleType.crosssell; it.status = RuleStatus.active
            },
        )
        every { source.findByIds(setOf("prod_Daribar_current"), true) } returns listOf(
            EshopCatalogSnapshotRepository.CatalogItem(MedusaProduct(id = "prod_Daribar_current"), true),
        )

        val pools = service.recommendationPools()
        assertEquals(listOf("prod_Daribar_current"), pools.alternatives.map { it.id })
        assertTrue(pools.crosssells.isEmpty())
    }
}
