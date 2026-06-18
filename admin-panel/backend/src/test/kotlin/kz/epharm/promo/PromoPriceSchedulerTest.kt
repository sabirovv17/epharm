package kz.epharm.promo

import io.mockk.every
import io.mockk.mockk
import kz.epharm.catalog.entity.ProductEntity
import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.medusa.service.MedusaPriceService
import kz.epharm.medusa.service.MedusaPriceService.MedusaSnapshot
import kz.epharm.promo.entity.PromoEntity
import kz.epharm.promo.entity.PromoTier
import kz.epharm.promo.repository.PromoRepository
import kz.epharm.promo.service.PromoPriceScheduler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Ежедневный рефреш цен из Medusa (T1): цена промо-порога и товара обновляются, бонус сохраняется. */
class PromoPriceSchedulerTest {

    private val promoRepo = mockk<PromoRepository>()
    private val productRepo = mockk<ProductRepository>()
    private val priceService = mockk<MedusaPriceService>()
    private val scheduler = PromoPriceScheduler(promoRepo, productRepo, priceService)

    @Test
    fun `рефреш обновляет цену порога и обложку промо + цену товара, бонус не трогает`() {
        val promo = PromoEntity(id = "pr_1", title = "Акция", medusaProductId = "m1").also {
            it.tiers = listOf(PromoTier(minQty = 1, price = 100, bonus = 500))
            it.productImage = "http://medusa/old.jpg"
        }
        val product = ProductEntity(id = "m1", name = "Товар", price = 100).also { it.medusaProductId = "m1" }

        every { priceService.active } returns true
        every { promoRepo.findAllByMedusaProductIdIsNotNull() } returns listOf(promo)
        every { productRepo.findAllByMedusaProductIdIsNotNull() } returns listOf(product)
        // Промо рефрешится снимком (цена + обложка), товар — ценой.
        every { priceService.snapshotOf("m1") } returns MedusaSnapshot(price = 250L, cover = "http://medusa/new.jpg")
        every { priceService.priceOf("m1") } returns 250L
        every { promoRepo.save(promo) } returns promo
        every { productRepo.save(product) } returns product

        val summary = scheduler.refreshNow()

        assertEquals(250, promo.tiers[0].price.toInt())
        assertEquals(500, promo.tiers[0].bonus.toInt(), "бонус фармацевту сохранён")
        assertEquals("http://medusa/new.jpg", promo.productImage, "обложка обновлена из Medusa")
        assertEquals(250, product.price)
        assertEquals(1, summary.promosUpdated)
        assertEquals(1, summary.productsUpdated)
    }

    @Test
    fun `Medusa выключена — рефреш пропускается`() {
        every { priceService.active } returns false
        val summary = scheduler.refreshNow()
        assertEquals(0, summary.promosUpdated)
        assertEquals(0, summary.productsUpdated)
    }
}
