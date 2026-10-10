package kz.epharm.eshop

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.catalog.service.AccCatalogTaxonomy
import kz.epharm.medusa.service.MedusaPriceService
import kz.epharm.promo.dto.PromoRuleProductRefDto
import kz.epharm.promo.dto.PromoRulesConfigDto
import kz.epharm.promo.dto.UpdatePromoRequest
import kz.epharm.promo.entity.PromoEntity
import kz.epharm.promo.entity.PromoStatus
import kz.epharm.promo.repository.PromoRepository
import kz.epharm.promo.service.PromoRulesService
import kz.epharm.promo.service.PromoService
import kz.epharm.pharmacies.repository.PharmacyRepository
import kz.epharm.rules.entity.RuleEntity
import kz.epharm.rules.entity.RuleTrigger
import kz.epharm.rules.repository.RuleRepository
import kz.epharm.shared.error.AppException
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.Optional

class EshopPromoRuleGuardTest {
    @Test
    fun `admin-only stage cannot activate site campaign before mobile cutover`() {
        val promos = mockk<PromoRepository>(relaxed = true)
        val rules = mockk<RuleRepository>(relaxed = true)
        val source = mockk<EshopCatalogSnapshotRepository>(relaxed = true)
        val campaign = PromoEntity(id = "pr_site", title = "Акция", medusaProductId = "prod_Daribar_1")
        every { promos.findById("pr_site") } returns Optional.of(campaign)
        every { source.isCanonicalId("prod_Daribar_1") } returns true
        val service = PromoService(
            promos, mockk<MedusaPriceService>(relaxed = true), rules,
            mockk<PharmacyRepository>(relaxed = true), source,
            eshopReadEnabled = false, eshopAdminReadEnabled = true,
        )

        val failure = assertThrows(AppException::class.java) {
            service.update("pr_site", UpdatePromoRequest(status = PromoStatus.active))
        }
        assertTrue(failure.message.orEmpty().contains("мобильный каталог"))
        verify(exactly = 0) { promos.save(any()) }
    }

    @Test
    fun `active new exact pair without cashier key is rejected before replacing rules`() {
        val promos = mockk<PromoRepository>(relaxed = true)
        val rules = mockk<RuleRepository>(relaxed = true)
        val products = mockk<ProductRepository>(relaxed = true)
        val source = mockk<EshopCatalogSnapshotRepository>(relaxed = true)
        val promo = PromoEntity(id = "pr_test", title = "Акция", medusaProductId = "prod_legacy")
            .also { it.status = PromoStatus.active }
        every { promos.findById("pr_test") } returns Optional.of(promo)
        every { products.findById(any()) } returns Optional.empty()
        every { source.isCanonicalId(any()) } answers { firstArg<String>().startsWith("prod_Daribar") }
        val service = PromoRulesService(
            promos, rules, products, mockk<MedusaPriceService>(relaxed = true),
            mockk<AccCatalogTaxonomy>(relaxed = true), source, true,
        )

        val failure = assertThrows(AppException::class.java) {
            service.replace(
                "pr_test",
                PromoRulesConfigDto(replacements = listOf(
                    PromoRuleProductRefDto(medusaProductId = "prod_Daribar_trigger", name = "Новый товар"),
                )),
                "admin",
            )
        }
        assertTrue(failure.message.orEmpty().contains("replacements[0].barcode/ipartId"))
        verify(exactly = 0) { rules.deleteByPromoId(any()) }
    }

    @Test
    fun `activating draft with product any trigger checks every new source product`() {
        val promos = mockk<PromoRepository>(relaxed = true)
        val rules = mockk<RuleRepository>(relaxed = true)
        val products = mockk<ProductRepository>(relaxed = true)
        val source = mockk<EshopCatalogSnapshotRepository>(relaxed = true)
        val price = mockk<MedusaPriceService>(relaxed = true)
        val promo = PromoEntity(id = "pr_any", title = "Акция", medusaProductId = "prod_legacy")
        val rule = RuleEntity(
            id = "rule_any", recommend = "prod_legacy",
            trigger = RuleTrigger(kind = "product_any", value = listOf("prod_legacy_trigger", "prod_Daribar_new")),
        )
        every { promos.findById("pr_any") } returns Optional.of(promo)
        every { promos.findAllByMedusaProductIdIsNotNull() } returns emptyList()
        every { rules.findAllByPromoIdOrderByUpdatedAtDesc("pr_any") } returns listOf(rule)
        every { products.findById("prod_Daribar_new") } returns Optional.empty()
        every { source.isCanonicalId(any()) } answers { firstArg<String>().startsWith("prod_Daribar") }
        val service = PromoService(
            promos, price, rules, mockk<PharmacyRepository>(relaxed = true),
            source, true, products,
        )

        val failure = assertThrows(AppException::class.java) {
            service.update("pr_any", UpdatePromoRequest(status = PromoStatus.active))
        }
        assertTrue(failure.message.orEmpty().contains("rule_any"))
        assertTrue(failure.message.orEmpty().contains("prod_Daribar_new"))
        verify(exactly = 0) { promos.save(any()) }
    }
}
