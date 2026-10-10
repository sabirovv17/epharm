package kz.epharm.eshop

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kz.epharm.medusa.MedusaCatalogCache
import kz.epharm.medusa.client.MedusaClient
import kz.epharm.medusa.dto.MedusaProduct
import kz.epharm.mobile.catalog.service.MobileCatalogService
import kz.epharm.mobile.promotions.service.MobilePromotionsService
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class EshopCatalogReadSwitchTest {
    private val medusa = mockk<MedusaClient>(relaxed = true)
    private val source = mockk<EshopCatalogSnapshotRepository>()
    private val promos = mockk<PromoRepository>(relaxed = true)
    private val rules = mockk<RuleRepository>(relaxed = true)
    private val service = MobileCatalogService(
        medusa, MedusaCatalogCache(0), promos, rules,
        eshop = source, eshopReadEnabled = true,
    )

    @Test
    fun `admin sees complete master and mobile sees only published without Medusa access`() {
        val published = EshopCatalogSnapshotRepository.CatalogItem(
            MedusaProduct(id = "prod_Daribar_1", title = "Первый"), true,
        )
        val draft = EshopCatalogSnapshotRepository.CatalogItem(
            MedusaProduct(id = "prod_Daribar_2", title = "Второй"), false,
        )
        every { source.hasCompleteSnapshot() } returns true
        every { source.search(null, null, 50, 0, false, null) } returns
            EshopCatalogSnapshotRepository.Page(listOf(published, draft), 2)
        every { source.search(null, null, 50, 0, true, true) } returns
            EshopCatalogSnapshotRepository.Page(listOf(published), 1)

        val admin = service.search(null, null, 50, 0, admin = true)
        val mobile = service.search(null, null, 50, 0)

        assertEquals(2, admin.total)
        assertFalse(admin.items[1].published)
        assertEquals(1, mobile.total)
        assertEquals("prod_Daribar_1", mobile.items.single().id)
        verify(exactly = 0) { medusa.listProducts(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `read cutover without complete generation fails closed`() {
        every { source.hasCompleteSnapshot() } returns false
        assertThrows(AppException::class.java) { service.search(null, null, 24, 0) }
        verify(exactly = 0) { medusa.listProducts(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `legacy campaign and rule resolve against canonical published cards`() {
        val canonicalX = "prod_Daribar_X"
        val canonicalY = "prod_Daribar_Y"
        val legacyX = "prod_old_x"
        val legacyX2 = "prod_old_x2"
        val legacyY = "prod_old_y"
        val x = EshopCatalogSnapshotRepository.CatalogItem(MedusaProduct(
            id = canonicalX, title = "Товар X",
            thumbnail = "https://epharm.inkar.kz/api/media/eshop?sku=X",
        ), true)
        val y = EshopCatalogSnapshotRepository.CatalogItem(MedusaProduct(id = canonicalY, title = "Товар Y"), true)
        val promo = PromoEntity(id = "pr_x", title = "Акция X", productName = "Старое имя").also {
            it.status = PromoStatus.active
            it.medusaProductId = legacyX
            it.overrideImage = "/api/media/img?u=https%3A%2F%2Fmedusa.example%2Fold.jpg"
            it.overrideDescription = "Описание акции"
            it.tiers = listOf(PromoTier(1, 100, 500))
        }
        val duplicate = PromoEntity(id = "pr_x2", title = "Акция X2", productName = "Старое имя").also {
            it.status = PromoStatus.active
            it.medusaProductId = legacyX2
            it.tiers = listOf(PromoTier(1, 100, 500))
        }
        val rule = RuleEntity(
            id = "rule_y", recommend = legacyY, bonus = 80, script = "Подсказка",
            trigger = RuleTrigger(kind = "product", value = legacyX),
        ).also { it.type = RuleType.crosssell; it.status = RuleStatus.active }
        every { source.hasCompleteSnapshot() } returns true
        every { source.findById(canonicalX, true) } returns x
        every { source.relatedIds(canonicalX) } returns listOf(canonicalX, legacyX, legacyX2)
        every { promos.findAllByMedusaProductIdIn(listOf(canonicalX, legacyX, legacyX2)) } returns listOf(promo)
        every { rules.findAllByStatusRawOrderByUpdatedAtDesc(RuleStatus.active.name) } returns listOf(rule)
        every { promos.findAllByStatusRawAndMedusaProductIdIsNotNullOrderByUpdatedAtDesc(PromoStatus.active.name) } returns
            listOf(promo, duplicate)
        every { source.canonicalIds(any()) } answers {
            firstArg<Collection<String>>().associateWith { id ->
                when (id) {
                    legacyX, legacyX2, canonicalX -> canonicalX
                    legacyY, canonicalY -> canonicalY
                    else -> id
                }
            }
        }
        every { source.findByIds(any(), true) } answers {
            firstArg<Collection<String>>().mapNotNull { id ->
                when (id) {
                    canonicalX -> x
                    canonicalY -> y
                    else -> null
                }
            }
        }

        val detail = service.detail(canonicalX, includeIncentive = true)
        assertEquals("Описание акции", detail.description)
        assertEquals("https://epharm.inkar.kz/api/media/eshop?sku=X", detail.imageUrl)
        assertEquals("pr_x", detail.promoId)
        assertEquals(500, detail.bonus)

        val recommendations = service.recommendations(canonicalX, includeIncentive = true)
        assertEquals(canonicalY, recommendations.crosssells.single().product.id)
        assertEquals(80, recommendations.crosssells.single().bonus)
        assertTrue(service.recommendations(canonicalY, includeIncentive = false)
            .crosssells.any { it.product.id == canonicalX })

        val feed = MobilePromotionsService(promos, service, eshopReadEnabled = true).activeFeed()
        assertEquals(1, feed.size)
        assertEquals(canonicalX, feed.single().productId)
        assertEquals("Товар X", feed.single().name)
        assertEquals("https://epharm.inkar.kz/api/media/eshop?sku=X", feed.single().imageUrl)
        verify(exactly = 0) { medusa.getProduct(any()) }
        verify(exactly = 0) { medusa.listProducts(any(), any(), any(), any(), any()) }
    }
}
