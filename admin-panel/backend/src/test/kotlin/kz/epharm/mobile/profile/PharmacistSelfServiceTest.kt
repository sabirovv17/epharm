package kz.epharm.mobile.profile

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.Called
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kz.epharm.catalog.entity.ProductEntity
import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.mobile.profile.service.PharmacistSelfService
import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.entity.PharmacistStatus
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.promo.entity.PromoEntity
import kz.epharm.promo.entity.PromoStatus
import kz.epharm.promo.repository.PromoRepository
import kz.epharm.rules.entity.RuleCard
import kz.epharm.rules.entity.RuleEntity
import kz.epharm.rules.entity.RuleStatus
import kz.epharm.rules.entity.RuleTrigger
import kz.epharm.rules.entity.RuleType
import kz.epharm.rules.repository.RuleRepository
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.time.Instant
import java.time.LocalDate
import java.util.Optional

class PharmacistSelfServiceTest {
    private val pharmacists = mockk<PharmacistRepository>()
    private val rules = mockk<RuleRepository>()
    private val promos = mockk<PromoRepository>()
    private val products = mockk<ProductRepository>()
    private val service = PharmacistSelfService(pharmacists, rules, promos, products)
    private val account = PharmacistEntity(id = PHARMACIST_ID, balance = 4_000_000_050L).also {
        it.status = PharmacistStatus.active
    }

    init {
        every { pharmacists.findById(PHARMACIST_ID) } returns Optional.of(account)
    }

    @Test
    fun `balance uses the current active account without requiring a pharmacy`() {
        assertNull(account.pharmacyId)

        val result = service.balance(PHARMACIST_ID)

        assertEquals(4_000_000_050L, result.balanceKzt)
        assertEquals("KZT", result.currency)
        assertEquals(4_000_000_050L, account.balance)
        verify(exactly = 1) { pharmacists.findById(PHARMACIST_ID) }
        verify { listOf(rules, promos, products) wasNot Called }
        confirmVerified(pharmacists)
    }

    @Test
    fun `pending and blocked accounts cannot read balance or recommendations`() {
        for (status in listOf(PharmacistStatus.pending, PharmacistStatus.blocked)) {
            account.status = status
            val balanceError = assertThrows(AppException::class.java) { service.balance(PHARMACIST_ID) }
            val feedError = assertThrows(AppException::class.java) { feed() }

            for (error in listOf(balanceError, feedError)) {
                assertEquals(ErrorCode.USER_NOT_ACTIVE, error.code)
                assertEquals(HttpStatus.FORBIDDEN, error.status)
            }
        }
        verify { listOf(rules, promos, products) wasNot Called }
    }

    @Test
    fun `deleted account cannot use an otherwise valid pharmacist identity`() {
        every { pharmacists.findById(PHARMACIST_ID) } returns Optional.empty()

        val balanceError = assertThrows(AppException::class.java) { service.balance(PHARMACIST_ID) }
        val feedError = assertThrows(AppException::class.java) { feed() }

        for (error in listOf(balanceError, feedError)) {
            assertEquals(ErrorCode.USER_NOT_FOUND, error.code)
            assertEquals(HttpStatus.UNAUTHORIZED, error.status)
        }
        verify { listOf(rules, promos, products) wasNot Called }
    }

    @Test
    fun `campaign status and inclusive dates gate active rules while legacy rules remain available`() {
        val campaigns = listOf(
            campaign("same-day", start = TODAY, end = TODAY),
            campaign("open-start", end = TODAY),
            campaign("open-end", start = TODAY),
            campaign("unbounded"),
            campaign("future", start = TODAY.plusDays(1)),
            campaign("expired", end = TODAY.minusDays(1)),
            campaign("draft", status = PromoStatus.draft),
            campaign("paused", status = PromoStatus.paused),
            campaign("archived", status = PromoStatus.archived),
        )
        stub(
            configuredRules = campaigns.map { rule(it.id, promoId = it.id) } +
                listOf(rule("missing", promoId = "missing"), rule("legacy")),
            campaigns = campaigns,
            catalog = listOf(product("offer"), product("trigger")),
        )

        val result = feed()

        assertEquals(setOf("same-day", "open-start", "open-end", "unbounded", "legacy"),
            result.items.flatMap { it.offers }.map { it.ruleId }.toSet())
        val sameDay = result.items.single { it.campaign?.id == "same-day" }.campaign!!
        assertEquals(TODAY, sameDay.dateStart)
        assertEquals(TODAY, sameDay.dateEnd)
        assertEquals("Кампания same-day", sameDay.title)
        assertNull(result.items.single { it.offers.single().ruleId == "legacy" }.campaign)
        verify(exactly = 1) { rules.findAllByStatusRawOrderByUpdatedAtDesc("active") }
    }

    @Test
    fun `all four trigger modes and legacy product lists retain their separate trigger and offers`() {
        val triggers = listOf(
            RuleTrigger("product", "trigger"),
            RuleTrigger("acc_group", "group-key", label = "Группа"),
            RuleTrigger("acc_subgroup", "subgroup-key", label = "Подгруппа"),
            RuleTrigger("acc_mnn", "mnn-key", label = "МНН"),
            RuleTrigger("product_any", listOf("trigger", "second-trigger"), exclude = listOf("excluded")),
        )
        stub(
            configuredRules = triggers.mapIndexed { index, trigger -> rule("r$index", trigger = trigger) },
            catalog = listOf(product("offer"), product("trigger"), product("second-trigger")),
        )

        val result = feed()

        assertEquals(5, result.total)
        assertEquals(triggers.toSet(), result.items.map { it.trigger }.toSet())
        assertEquals(listOf("trigger"), result.items.single { it.trigger.kind == "product" }.triggerProducts.map { it.id })
        assertEquals(listOf("trigger", "second-trigger"),
            result.items.single { it.trigger.kind == "product_any" }.triggerProducts.map { it.id })
        assertTrue(result.items.filter { it.trigger.kind.startsWith("acc_") }.all { it.triggerProducts.isEmpty() })
        assertTrue(result.items.all { it.offers.single().product.id == "offer" })
    }

    @Test
    fun `groups distinguish campaign type and complete trigger identity`() {
        val trigger = RuleTrigger("product", "trigger")
        stub(
            configuredRules = listOf(
                rule("a-first", promoId = "a", trigger = trigger),
                rule("a-second", offerId = "second-offer", promoId = "a", trigger = trigger.copy()),
                rule("b", promoId = "b", trigger = trigger),
                rule("cross", promoId = "a", trigger = trigger, type = RuleType.crosssell),
                rule("other-product", promoId = "a", trigger = RuleTrigger("product", "other-trigger")),
                rule("scope", promoId = "a", trigger = RuleTrigger("acc_group", "trigger")),
                rule("legacy", trigger = trigger),
            ),
            campaigns = listOf(campaign("a"), campaign("b")),
            catalog = listOf(product("offer"), product("second-offer"), product("trigger"), product("other-trigger")),
        )

        val result = feed()

        assertEquals(6, result.total)
        assertEquals(6, result.items.map { Triple(it.campaign?.id, it.type, it.trigger) }.distinct().size)
        val merged = result.items.single {
            it.campaign?.id == "a" && it.type == RuleType.substitution && it.trigger == trigger
        }
        assertEquals(setOf("a-first", "a-second"), merged.offers.map { it.ruleId }.toSet())
        assertEquals(7, result.items.sumOf { it.offers.size })
    }

    @Test
    fun `offer rank precedes bonus then rule id and five distinct products are retained`() {
        val configured = listOf(
            rule("legacy-high", offerId = "legacy", bonus = 100_000),
            rule("rank-two", offerId = "two", rank = 2, bonus = 50_000),
            rule("rank-one-b", offerId = "one-b", rank = 1, bonus = 200),
            rule("duplicate-primary", offerId = "primary", rank = 1, bonus = 400),
            rule("rank-one-a", offerId = "one-a", rank = 1, bonus = 200),
            rule("primary", offerId = "primary", rank = 0, bonus = 0),
            rule("rank-one-high", offerId = "one-high", rank = 1, bonus = 300),
        )
        stub(configured, catalog = configured.map { product(it.recommend) })

        val offers = feed().items.single().offers

        assertEquals(listOf("primary", "rank-one-high", "rank-one-a", "rank-one-b", "rank-two"), offers.map { it.ruleId })
        assertEquals(5, offers.map { it.product.id }.distinct().size)
        assertEquals(0, offers.first().bonusKzt)
    }

    @Test
    fun `legacy offers use descending bonus and deterministic rule id`() {
        val configured = listOf(
            rule("low", offerId = "low", bonus = 0),
            rule("b", offerId = "b", bonus = 900),
            rule("a", offerId = "a", bonus = 900),
        )
        stub(configured, catalog = configured.map { product(it.recommend) })

        assertEquals(listOf("a", "b", "low"), feed().items.single().offers.map { it.ruleId })
    }

    @Test
    fun `paging keeps whole groups ordered by latest update then rule id`() {
        val configured = (0..4).map { index ->
            rule("a$index", offerId = "offer-$index", trigger = RuleTrigger("product", "a"),
                rank = index, updatedAt = if (index == 4) UPDATED_AT.plusSeconds(60) else UPDATED_AT)
        } + listOf(
            rule("b", trigger = RuleTrigger("product", "b"), updatedAt = UPDATED_AT.plusSeconds(60)),
            rule("c", trigger = RuleTrigger("product", "c"), updatedAt = UPDATED_AT.minusSeconds(60)),
        )
        stub(configured.reversed(), catalog = configured.map { product(it.recommend) })

        val pages = (0..3).map { feed(page = it, size = 1) }

        assertEquals(listOf("a", "b", "c"), pages.take(3).map { it.items.single().trigger.value })
        assertEquals(listOf("a0", "a1", "a2", "a3", "a4"), pages.first().items.single().offers.map { it.ruleId })
        assertTrue(pages.last().items.isEmpty())
        pages.forEachIndexed { index, result ->
            assertEquals(3, result.total)
            assertEquals(index, result.page)
            assertEquals(1, result.size)
        }
    }

    @Test
    fun `invalid page and size fail before loading recommendations`() {
        for ((page, size) in listOf(-1 to 20, Int.MIN_VALUE to 1, 0 to 0, 0 to -1, 0 to 101, 0 to Int.MAX_VALUE)) {
            val error = assertThrows(AppException::class.java) { feed(page, size) }
            assertEquals(ErrorCode.VALIDATION_FAILED, error.code)
            assertEquals(HttpStatus.BAD_REQUEST, error.status)
        }
        verify { listOf(rules, promos, products) wasNot Called }
    }

    @Test
    fun `maximum size is accepted and a large page cannot overflow into the first page`() {
        stub(listOf(rule("r")), catalog = listOf(product("offer")))

        assertEquals(1, feed(size = 100).items.size)
        val beyondEnd = feed(page = Int.MAX_VALUE, size = 100)

        assertTrue(beyondEnd.items.isEmpty())
        assertEquals(1, beyondEnd.total)
        assertEquals(Int.MAX_VALUE, beyondEnd.page)
        assertEquals(100, beyondEnd.size)
    }

    @Test
    fun `missing offered products are skipped without inventing missing trigger products`() {
        stub(
            configuredRules = listOf(
                rule("available"),
                rule("missing-offer", offerId = "missing"),
                rule("empty-group", offerId = "missing", trigger = RuleTrigger("product", "other-trigger")),
                rule("unknown-trigger", offerId = "second-offer", trigger = RuleTrigger("product", "unknown")),
            ),
            catalog = listOf(product("offer"), product("second-offer"), product("trigger")),
        )

        val result = feed()

        assertEquals(2, result.total)
        assertEquals(setOf("available", "unknown-trigger"), result.items.flatMap { it.offers }.map { it.ruleId }.toSet())
        val unknownTrigger = result.items.single { it.trigger.value == "unknown" }
        assertTrue(unknownTrigger.triggerProducts.isEmpty())
        assertEquals("second-offer", unknownTrigger.offers.single().product.id)
    }

    @Test
    fun `empty active feed returns empty groups and pagination metadata`() {
        stub(emptyList())

        val result = feed()

        assertTrue(result.items.isEmpty())
        assertEquals(0, result.total)
        assertEquals(0, result.page)
        assertEquals(20, result.size)
        assertEquals("KZT", result.currency)
    }

    @Test
    fun `configured feed exposes catalog facts without stock claims or repository mutations`() {
        val configured = rule("r", promoId = "campaign", bonus = 0)
        val campaign = campaign("campaign")
        val offer = product("offer").also {
            it.price = 0
            it.barcode = "4870000000013"
            it.barcodes = listOf("4870000000013", "4870000000020")
            it.ipartId = "80309"
        }
        stub(listOf(configured), campaigns = listOf(campaign), catalog = listOf(offer))

        val result = feed()

        val returnedOffer = result.items.single().offers.single()
        assertEquals(0, returnedOffer.product.catalogPriceKzt)
        assertEquals(UPDATED_AT, returnedOffer.product.catalogUpdatedAt)
        assertEquals(listOf("4870000000013", "4870000000020"), returnedOffer.product.barcodes)
        assertEquals("80309", returnedOffer.product.ipartId)
        assertEquals("Скрипт r", returnedOffer.script)
        assertEquals(listOf("Преимущество r"), returnedOffer.advantages)
        val json = ObjectMapper().findAndRegisterModules().valueToTree<JsonNode>(result)
        for (field in listOf("stock", "availability", "balance", "pharmacyAvailableQty", "networkAvailableQty",
            "warehouseAvailableQty", "eventId", "sessionId", "goalText", "goalBonus")) {
            assertTrue(json.findValues(field).isEmpty(), "Configured feed must not claim $field")
        }
        assertEquals(4_000_000_050L, account.balance)
        assertEquals(PharmacistStatus.active, account.status)
        assertEquals(RuleStatus.active, configured.status)
        assertEquals(PromoStatus.active, campaign.status)
        verify(exactly = 1) { pharmacists.findById(PHARMACIST_ID) }
        verify(exactly = 1) { rules.findAllByStatusRawOrderByUpdatedAtDesc("active") }
        verify(exactly = 1) { promos.findAllById(any<Iterable<String>>()) }
        verify(exactly = 1) { products.findAllById(any<Iterable<String>>()) }
        confirmVerified(pharmacists, rules, promos, products)
    }

    private fun feed(page: Int = 0, size: Int = 20) =
        service.recommendations(PHARMACIST_ID, page, size, today = TODAY)

    private fun stub(
        configuredRules: List<RuleEntity>,
        campaigns: List<PromoEntity> = emptyList(),
        catalog: List<ProductEntity> = emptyList(),
    ) {
        every { rules.findAllByStatusRawOrderByUpdatedAtDesc("active") } returns configuredRules
        every { promos.findAllById(any<Iterable<String>>()) } answers {
            val ids = firstArg<Iterable<String>>().toSet()
            campaigns.filter { it.id in ids }
        }
        every { products.findAllById(any<Iterable<String>>()) } answers {
            val ids = firstArg<Iterable<String>>().toSet()
            catalog.filter { it.id in ids }.distinctBy { it.id }
        }
    }

    private fun rule(
        id: String,
        offerId: String = "offer",
        trigger: RuleTrigger = RuleTrigger("product", "trigger"),
        promoId: String? = null,
        type: RuleType = RuleType.substitution,
        rank: Int? = null,
        bonus: Int = 100,
        updatedAt: Instant = UPDATED_AT,
    ) = RuleEntity(
        id = id, recommend = offerId, trigger = trigger, promoId = promoId, bonus = bonus,
        card = rank?.let { RuleCard(offerRank = it) }, updatedAt = updatedAt,
        script = "Скрипт $id", advantages = listOf("Преимущество $id"),
    ).also { it.type = type; it.status = RuleStatus.active }

    private fun campaign(
        id: String,
        status: PromoStatus = PromoStatus.active,
        start: LocalDate? = null,
        end: LocalDate? = null,
    ) = PromoEntity(id = id, title = "Кампания $id", dateStart = start, dateEnd = end).also { it.status = status }

    private fun product(id: String) = ProductEntity(
        id = id, name = "Товар $id", brand = "Бренд", mnn = "МНН $id", volume = "20 таблеток",
        price = 2_500, updatedAt = UPDATED_AT,
    )

    private companion object {
        const val PHARMACIST_ID = "pharmacist-self"
        val TODAY: LocalDate = LocalDate.of(2026, 10, 21)
        val UPDATED_AT: Instant = Instant.parse("2026-10-20T08:00:00Z")
    }
}
