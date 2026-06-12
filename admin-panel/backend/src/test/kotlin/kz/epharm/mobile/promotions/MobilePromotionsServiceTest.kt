package kz.epharm.mobile.promotions

import io.mockk.every
import io.mockk.mockk
import kz.epharm.mobile.catalog.dto.MobileCatalogProductDto
import kz.epharm.mobile.catalog.service.MobileCatalogService
import kz.epharm.mobile.promotions.service.MobilePromotionsService
import kz.epharm.promo.entity.PromoEntity
import kz.epharm.promo.entity.PromoStatus
import kz.epharm.promo.entity.PromoTier
import kz.epharm.promo.repository.PromoRepository
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.time.LocalDate

/**
 * Юнит-тест ленты промо: мёрж активных промо-кампаний с живыми данными Medusa,
 * фильтр окна дат, деградация на снимок при недоступности товара. Репозиторий и
 * каталог замоканы — ни БД, ни сети.
 */
class MobilePromotionsServiceTest {

    private val repo = mockk<PromoRepository>()
    private val catalog = mockk<MobileCatalogService>()
    private val service = MobilePromotionsService(repo, catalog)

    private val today = LocalDate.of(2026, 6, 15)
    private val ACTIVE = PromoStatus.active.name

    private fun promo(
        id: String,
        productId: String?,
        start: LocalDate? = null,
        end: LocalDate? = null,
        tiers: List<PromoTier> = listOf(PromoTier(1, 100, 0)),
        snapName: String = "Снимок",
        snapBrand: String = "BrandSnap",
    ) = PromoEntity(id = id, title = "Акция $id", brand = snapBrand, productName = snapName).also {
        it.status = PromoStatus.active
        it.medusaProductId = productId
        it.dateStart = start
        it.dateEnd = end
        it.tiers = tiers
    }

    private fun card(id: String) = MobileCatalogProductDto(
        id = id,
        name = "Лифта 10мг",
        brand = "Abdi Ibrahim",
        mnn = "Tadalafil",
        rxOtc = "Rx",
        price = null,
        currency = "KZT",
        imageUrl = "http://img/lifta.jpg",
        barcode = "123",
        category = "Урология",
        categories = listOf("Урология"),
    )

    @Test
    fun `активное промо в окне дат мёржится с живым товаром Medusa`() {
        val p = promo(
            "pr_1", "prod_lifta", start = today.minusDays(5), end = today.plusDays(5),
            tiers = listOf(PromoTier(1, 500, 0), PromoTier(10, 600, 900), PromoTier(20, 700, 1900)),
        )
        every { repo.findAllByStatusRawAndMedusaProductIdIsNotNullOrderByUpdatedAtDesc(ACTIVE) } returns listOf(p)
        every { catalog.cardsByIds(listOf("prod_lifta")) } returns mapOf("prod_lifta" to card("prod_lifta"))

        val feed = service.activeFeed(today)
        assertEquals(1, feed.size)
        val d = feed[0]
        assertEquals("pr_1", d.id)              // id кампании — шлём с чеком
        assertEquals("prod_lifta", d.productId) // medusa id — для детали/SKU
        assertEquals("Лифта 10мг", d.name)      // из живого Medusa, не снимок
        assertEquals("Abdi Ibrahim", d.brand)
        assertEquals("Урология", d.category)
        assertEquals(3, d.tiers.size)
        assertEquals(900, d.tiers[1].bonus)     // бонус 2-го порога из админ-кампании
        assertEquals(20, d.tiers[2].minQty)
        assertEquals(today.minusDays(5), d.dateStart)
    }

    @Test
    fun `товар недоступен в Medusa — деградация на снимок промо`() {
        val p = promo("pr_2", "prod_gone", snapName = "Везилют (снимок)", snapBrand = "Access")
        every { repo.findAllByStatusRawAndMedusaProductIdIsNotNullOrderByUpdatedAtDesc(ACTIVE) } returns listOf(p)
        every { catalog.cardsByIds(any()) } returns emptyMap() // Medusa не вернул товар

        val d = service.activeFeed(today).single()
        assertEquals("Везилют (снимок)", d.name)
        assertEquals("Access", d.brand)
        assertEquals("prod_gone", d.productId)
    }

    @Test
    fun `промо вне окна дат отфильтровано (истёкшее и будущее)`() {
        val expired = promo("pr_exp", "prod_a", end = today.minusDays(1))
        val future = promo("pr_fut", "prod_b", start = today.plusDays(1))
        every { repo.findAllByStatusRawAndMedusaProductIdIsNotNullOrderByUpdatedAtDesc(ACTIVE) } returns
            listOf(expired, future)
        // оба отфильтрованы → promos пусто → catalog.cardsByIds НЕ зовётся (не застаблен)
        assertTrue(service.activeFeed(today).isEmpty())
    }

    @Test
    fun `без активных промо — пустая лента, Medusa не дёргается`() {
        every { repo.findAllByStatusRawAndMedusaProductIdIsNotNullOrderByUpdatedAtDesc(ACTIVE) } returns emptyList()
        assertTrue(service.activeFeed(today).isEmpty())
        // catalog.cardsByIds НЕ застаблен — если бы вызвался, mockk бросил бы
    }

    @Test
    fun `сбой витрины Medusa (AppException) — лента деградирует на снимок, не падает`() {
        val p = promo("pr_err", "prod_x", snapName = "Снимок-товар", snapBrand = "SnapBrand")
        every { repo.findAllByStatusRawAndMedusaProductIdIsNotNullOrderByUpdatedAtDesc(ACTIVE) } returns listOf(p)
        every { catalog.cardsByIds(any()) } throws
            AppException(ErrorCode.UPSTREAM_UNAVAILABLE, "Каталог недоступен", HttpStatus.BAD_GATEWAY)

        val feed = service.activeFeed(today) // не должно бросить
        assertEquals(1, feed.size)
        assertEquals("Снимок-товар", feed[0].name) // из снимка промо
        assertEquals("SnapBrand", feed[0].brand)
    }

    @Test
    fun `активная акция без ценовых порогов в ленту не попадает`() {
        val p = promo("pr_notiers", "prod_y", tiers = emptyList())
        every { repo.findAllByStatusRawAndMedusaProductIdIsNotNullOrderByUpdatedAtDesc(ACTIVE) } returns listOf(p)
        // пустые tiers отфильтрованы ДО Medusa → cardsByIds не зовётся (не застаблен)
        assertTrue(service.activeFeed(today).isEmpty())
    }
}
