package kz.epharm.medusa

import io.mockk.every
import io.mockk.mockk
import kz.epharm.medusa.client.MedusaClient
import kz.epharm.medusa.dto.MedusaCalculatedPrice
import kz.epharm.medusa.dto.MedusaImage
import kz.epharm.medusa.dto.MedusaProduct
import kz.epharm.medusa.dto.MedusaProductListResponse
import kz.epharm.medusa.dto.MedusaVariant
import kz.epharm.mobile.catalog.service.MobileCatalogService
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Юнит-тест маппинга Medusa → мобильный каталог. Ключевая ценность — устойчивость к
 * РЕАЛЬНЫМ неполным данным: плейсхолдеры 1С ("_"/"none"), пустые цена/фото/категории,
 * бренд только в metadata. Клиент замокан — сеть не трогаем; кэш выключен (ttl=0).
 */
class MobileCatalogServiceTest {

    private val medusa = mockk<MedusaClient>()
    private val service = MobileCatalogService(medusa, MedusaCatalogCache(0))

    private fun stubList(vararg products: MedusaProduct, count: Int = products.size) {
        every { medusa.listProducts(any(), any(), any(), any(), any()) } returns
            MedusaProductListResponse(products = products.toList(), count = count, limit = 24, offset = 0)
    }

    @Test
    fun `неполный товар (нет цены, фото, категорий) маппится с fallback из metadata`() {
        stubList(
            MedusaProduct(
                id = "prod_1",
                title = "Везилют капс. №30",
                metadata = mapOf(
                    "mnn" to "_",                       // плейсхолдер → отбрасываем
                    "rx_otc" to "OTC",
                    "corporation" to "Эксесс Байосаинс/Access Bioscience",
                    "manufacturer" to "ТД Пептид Био",
                    "category" to "Биологически активные добавки",
                    "full_title" to "Везилют Пептид Био/Капсулы/_/30",
                ),
                variants = listOf(MedusaVariant(id = "v1", sku = "X", barcode = "4603423004936", calculatedPrice = null)),
            ),
            count = 77,
        )

        val page = service.search(q = null, category = null, limit = 24, offset = 0)
        assertEquals(1, page.items.size)
        assertEquals(77, page.total)
        val c = page.items[0]
        assertEquals("Везилют капс. №30", c.name)
        assertEquals("Эксесс Байосаинс/Access Bioscience", c.brand) // brand_name нет → corporation
        assertNull(c.mnn)                  // "_" отброшен
        assertEquals("OTC", c.rxOtc)
        assertNull(c.price)                // calculated_price = null
        assertEquals("KZT", c.currency)    // дефолт
        assertNull(c.imageUrl)             // ни thumbnail, ни images
        assertEquals("4603423004936", c.barcode)
        assertEquals("Биологически активные добавки", c.category) // из metadata.category
    }

    @Test
    fun `brand_name приоритетнее corporation, цена и валюта берутся из варианта`() {
        stubList(
            MedusaProduct(
                id = "prod_2",
                title = "Аспирин 500мг №30",
                thumbnail = "http://cdn/aspirin.jpg",
                metadata = mapOf("brand_name" to "Bayer", "corporation" to "Bayer AG", "rx_otc" to "OTC"),
                variants = listOf(
                    MedusaVariant(
                        id = "v2",
                        calculatedPrice = MedusaCalculatedPrice(calculatedAmount = 1990.4, currencyCode = "kzt"),
                    ),
                ),
            ),
        )
        val c = service.search(null, null, 24, 0).items[0]
        assertEquals("Bayer", c.brand)
        assertEquals(1990, c.price)        // округление 1990.4 → 1990
        assertEquals("KZT", c.currency)    // currency_code апперкейсится
        assertEquals("http://cdn/aspirin.jpg", c.imageUrl)
    }

    @Test
    fun `imageUrl исключает gallery=marketplace`() {
        stubList(
            MedusaProduct(
                id = "prod_3",
                title = "Товар с галереями",
                images = listOf(
                    MedusaImage(url = "http://cdn/mp.jpg", metadata = mapOf("gallery" to "marketplace")),
                    MedusaImage(url = "http://cdn/site.jpg", metadata = mapOf("gallery" to "site")),
                ),
            ),
        )
        val c = service.search(null, null, 24, 0).items[0]
        assertEquals("http://cdn/site.jpg", c.imageUrl) // marketplace-фото пропущено
    }

    @Test
    fun `limit и offset нормализуются (диапазон 1-50, offset не меньше 0)`() {
        stubList(MedusaProduct(id = "p", title = "t"))
        val tooBig = service.search(null, null, limit = 999, offset = -10)
        assertEquals(50, tooBig.limit)
        assertEquals(0, tooBig.offset)

        val tooSmall = service.search(null, null, limit = 0, offset = 5)
        assertEquals(1, tooSmall.limit)
        assertEquals(5, tooSmall.offset)
    }

    @Test
    fun `пустой ответ Medusa → пустая страница без ошибок`() {
        stubList(count = 0)
        val page = service.search(null, null, 24, 0)
        assertTrue(page.items.isEmpty())
        assertEquals(0, page.total)
    }

    @Test
    fun `detail несуществующего товара → NOT_FOUND`() {
        every { medusa.getProduct("missing") } returns null
        val ex = assertThrows(AppException::class.java) { service.detail("missing") }
        assertEquals(ErrorCode.NOT_FOUND, ex.code)
    }

    @Test
    fun `detail маппит описание, страну, производителя, key_facts и marketplace_links`() {
        every { medusa.getProduct("prod_d") } returns MedusaProduct(
            id = "prod_d",
            title = "Детальный товар",
            description = "Развёрнутое описание",
            metadata = mapOf(
                "atc" to "B01AC06",
                "country_official" to "Германия",
                "manufacturer_official" to "Bayer AG",
                "key_facts" to listOf("Факт 1", "Факт 2"),
                "marketplace_links" to listOf(
                    mapOf("platform" to "kaspi", "url" to "http://kaspi/1", "price" to 11990),
                    mapOf("platform" to "" ), // без платформы — отбрасываем
                ),
            ),
        )
        val d = service.detail("prod_d")
        assertEquals("Развёрнутое описание", d.description)
        assertEquals("B01AC06", d.atc)
        assertEquals("Германия", d.country)
        assertEquals("Bayer AG", d.manufacturer)
        assertEquals(listOf("Факт 1", "Факт 2"), d.keyFacts)
        assertEquals(1, d.marketplaceLinks.size)
        assertEquals("kaspi", d.marketplaceLinks[0].platform)
        assertEquals(11990, d.marketplaceLinks[0].price)
    }
}
