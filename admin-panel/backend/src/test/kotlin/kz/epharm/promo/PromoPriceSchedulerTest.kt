package kz.epharm.promo

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kz.epharm.catalog.entity.ProductEntity
import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.medusa.MedusaCatalogSnapshotRepository
import kz.epharm.medusa.client.MedusaClient
import kz.epharm.medusa.dto.MedusaCalculatedPrice
import kz.epharm.medusa.dto.MedusaProduct
import kz.epharm.medusa.dto.MedusaVariant
import kz.epharm.medusa.service.MedusaPriceService
import kz.epharm.medusa.service.MedusaPriceService.MedusaSnapshot
import kz.epharm.promo.entity.PromoEntity
import kz.epharm.promo.entity.PromoTier
import kz.epharm.promo.repository.PromoRepository
import kz.epharm.promo.service.PromoPriceScheduler
import kz.epharm.storefront.client.StorefrontCatalogClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Регулярный рефреш из Medusa (T1): цена промо-порога и товара обновляются, бонус сохраняется. */
class PromoPriceSchedulerTest {

    private val promoRepo = mockk<PromoRepository>()
    private val productRepo = mockk<ProductRepository>()
    private val priceService = mockk<MedusaPriceService>()
    private val scheduler = PromoPriceScheduler(promoRepo, productRepo, priceService)

    @Test
    fun `рефреш обновляет цену порога, обложку и штрих-код промо + цену и штрих-код товара, бонус не трогает`() {
        val promo = PromoEntity(id = "pr_1", title = "Акция", medusaProductId = "m1").also {
            it.tiers = listOf(PromoTier(minQty = 1, price = 100, bonus = 500))
            it.productImage = "http://medusa/old.jpg"
        }
        val product = ProductEntity(id = "m1", name = "Товар", price = 100).also { it.medusaProductId = "m1" }

        every { priceService.active } returns true
        every { promoRepo.findAllByMedusaProductIdIsNotNull() } returns listOf(promo)
        every { productRepo.findAllByMedusaProductIdIsNotNull() } returns listOf(product)
        // И промо, и товар рефрешатся одним снимком (цена + обложка + штрих-код).
        every { priceService.snapshotOf("m1") } returns
            MedusaSnapshot(price = 250L, cover = "http://medusa/new.jpg", barcode = "4603423004936")
        every { promoRepo.save(promo) } returns promo
        every { productRepo.save(product) } returns product

        val summary = scheduler.refreshNow()

        assertEquals(250, promo.tiers[0].price.toInt())
        assertEquals(500, promo.tiers[0].bonus.toInt(), "бонус фармацевту сохранён")
        assertEquals("http://medusa/new.jpg", promo.productImage, "обложка обновлена из Medusa")
        assertEquals("4603423004936", promo.barcode, "штрих-код промо обновлён из Medusa")
        assertEquals(250, product.price)
        assertEquals("4603423004936", product.barcode, "штрих-код товара обновлён из Medusa")
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

    @Test
    fun `website outage cannot replace stored campaign values with historical Medusa snapshot`() {
        val id = "prod_website"
        val website = mockk<StorefrontCatalogClient>()
        val snapshot = mockk<MedusaCatalogSnapshotRepository>()
        val medusa = mockk<MedusaClient>()
        every { website.active } returns true
        every { website.getProduct(id, "old-handle") } throws IllegalStateException("website down")
        every { website.cachedProduct(id) } returns null
        every { snapshot.hasCompleteSnapshot() } returns true
        every { snapshot.findById(id) } returns MedusaProduct(
            id = id, handle = "old-handle", thumbnail = "https://old.example/image.jpg",
            variants = listOf(MedusaVariant(id = "old-variant", barcode = "4871234567890",
                calculatedPrice = MedusaCalculatedPrice(calculatedAmount = 100.0))),
        )
        val promo = PromoEntity(id = "pr_website", title = "Акция сайта", medusaProductId = id).also {
            it.tiers = listOf(PromoTier(minQty = 1, price = 485, bonus = 50))
            it.productImage = "https://website.example/image.jpg"
            it.barcode = "4871234567883"
        }
        val product = ProductEntity(id = id, name = "Товар сайта", price = 485).also {
            it.medusaProductId = id
            it.barcode = promo.barcode
        }
        every { promoRepo.findAllByMedusaProductIdIsNotNull() } returns listOf(promo)
        every { productRepo.findAllByMedusaProductIdIsNotNull() } returns listOf(product)
        val scheduler = PromoPriceScheduler(promoRepo, productRepo, MedusaPriceService(medusa, website, snapshot))

        val result = scheduler.refreshNow()

        assertEquals(485L, promo.price)
        assertEquals(50L, promo.pharmacistBonus)
        assertEquals("https://website.example/image.jpg", promo.productImage)
        assertEquals("4871234567883", promo.barcode)
        assertEquals(485, product.price)
        assertEquals("4871234567883", product.barcode)
        assertEquals(0, result.promosUpdated)
        assertEquals(0, result.productsUpdated)
        verify(exactly = 0) { promoRepo.save(any()) }
        verify(exactly = 0) { productRepo.save(any()) }
        verify(exactly = 0) { medusa.getProduct(any()) }
        verify(exactly = 0) { medusa.getProductPharmacies(any()) }
    }
}
