package kz.epharm.posm

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kz.epharm.catalog.entity.ProductEntity
import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.pharmacies.entity.ChainEntity
import kz.epharm.pharmacies.entity.PharmacyEntity
import kz.epharm.pharmacies.repository.ChainRepository
import kz.epharm.pharmacies.repository.PharmacyRepository
import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.entity.PharmacistStatus
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.posm.dto.MarkShownRequest
import kz.epharm.posm.dto.PosSaleItemDto
import kz.epharm.posm.dto.PosSaleRequest
import kz.epharm.posm.entity.RecommendationEventEntity
import kz.epharm.posm.entity.RecommendationOutcome
import kz.epharm.posm.repository.PosSaleRepository
import kz.epharm.posm.repository.RecommendationEventRepository
import kz.epharm.receipts.entity.PendingBonusStatus
import kz.epharm.receipts.entity.ReceiptStatus
import kz.epharm.receipts.repository.PendingBonusRepository
import kz.epharm.receipts.repository.ReceiptRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.annotation.Propagation
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/** Financial contract: an informational popup needs actual display evidence AND a sold exact SKU.
 * These tests exercise the existing POSM endpoints, not an invented accepted-click event. */
@SpringBootTest(properties = [
    "app.posm.sale-bonus.enabled=true",
    "app.posm.sale-bonus.eligible-after=2026-01-01T00:00:00Z",
])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Transactional
class RecommendationSaleBonusIntegrationTest {
    companion object {
        private const val POSM_KEY = "dev-posm-key"
        private const val BARCODE = "4600000000017"
        private val CUTOFF = Instant.parse("2026-01-01T00:00:00Z")

        @Container @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("epharm_test").withUsername("epharm").withPassword("epharm_test")
            .apply { start() }

        @JvmStatic @DynamicPropertySource
        fun props(reg: DynamicPropertyRegistry) {
            reg.add("spring.datasource.url") { postgres.jdbcUrl }
            reg.add("spring.datasource.username") { postgres.username }
            reg.add("spring.datasource.password") { postgres.password }
        }
    }

    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var mapper: ObjectMapper
    @Autowired private lateinit var products: ProductRepository
    @Autowired private lateinit var pharmacists: PharmacistRepository
    @Autowired private lateinit var pharmacies: PharmacyRepository
    @Autowired private lateinit var chains: ChainRepository
    @Autowired private lateinit var events: RecommendationEventRepository
    @Autowired private lateinit var sales: PosSaleRepository
    @Autowired private lateinit var pending: PendingBonusRepository
    @Autowired private lateinit var receipts: ReceiptRepository

    private val soldAt: Instant get() = Instant.now().minusSeconds(30)

    @BeforeEach
    fun seed() {
        events.deleteAll()
        receipts.deleteAll()
        pending.deleteAll()
        sales.deleteAll()
        products.deleteAll()
        pharmacists.deleteAll()
        pharmacies.deleteAll()
        chains.deleteAll()
        chains.save(ChainEntity(id = "ch_money", name = "Тестовая сеть"))
        listOf("ph_money", "ph_other").forEach { id ->
            pharmacies.save(PharmacyEntity(id = id, name = id, chainId = "ch_money", chainName = "Тестовая сеть"))
        }
        listOf("u_money", "u_other").forEachIndexed { index, id ->
            pharmacists.save(PharmacistEntity(
                id = id, name = id, iin = "90011530001$index", phone = "+7700123456$index",
                pharmacyId = "ph_money", pharmacyName = "Тестовая аптека",
            ).also { it.status = PharmacistStatus.active })
        }
        products.save(ProductEntity(id = "p_offer", name = "Товар 20 мг капсулы №30", price = 1000,
            barcode = BARCODE, ipartId = "SN-123"))
    }

    @Test
    fun `shown ACK and sold exact SKU create pending without inventing accepted or credit`() {
        val event = event()
        shown(event)
        sale()

        val bonus = pending.findAll().single()
        assertEquals(250L, bonus.bonus)
        assertEquals("u_money", bonus.pharmacistId)
        assertEquals("ph_money", bonus.pharmacyId)
        assertEquals("p_offer", bonus.sku)
        assertEquals("rule_money", bonus.ruleId)
        assertEquals(bonus.id, events.findById(event.id).orElseThrow().pendingBonusId)
        assertEquals(RecommendationOutcome.shown, events.findById(event.id).orElseThrow().outcome)
        val receipt = receipts.findFirstByPendingBonusId(bonus.id)!!
        assertEquals(ReceiptStatus.moderation_required, receipt.status)
        assertEquals(true, receipt.confirmedByLog)
        assertEquals(false, receipt.confirmedByExcel)
        assertEquals(0L, receipt.bonusCredited)
        assertEquals(0L, pharmacists.findById("u_money").orElseThrow().balance)
    }

    @Test
    fun `generated API event without actual shown ACK never earns pending`() {
        event()
        sale()
        assertEquals(0L, pending.count())
    }

    @Test
    fun `late ACK after delivered offline sale completes pending once`() {
        val at = soldAt.minusSeconds(7200)
        val event = event(at = at.minusSeconds(60))
        sale(at = at)
        assertEquals(0L, pending.count())
        shown(event, at.minusSeconds(30))
        shown(event, at.minusSeconds(20))
        sale(at = at)
        val bonus = pending.findAll().single()
        assertEquals(ReceiptStatus.moderation_required, receipts.findFirstByPendingBonusId(bonus.id)!!.status)
        assertEquals(0L, pharmacists.findById("u_money").orElseThrow().balance)
    }

    @Test
    fun `repeated ACK and repeated sale do not create another pending`() {
        val event = event()
        shown(event)
        sale()
        shown(event)
        sale()
        assertEquals(1L, pending.count())
        assertEquals(1L, receipts.count())
    }

    @Test
    fun `two ACC trigger events offering same SKU reserve only one receipt bonus`() {
        val older = event("rec_group", at = soldAt.minusSeconds(100), trigger = "acc_first")
        val newer = event("rec_subgroup", at = soldAt.minusSeconds(90), trigger = "acc_second")
        shown(older, soldAt.minusSeconds(80))
        shown(newer, soldAt.minusSeconds(70))
        sale()
        assertEquals(1L, pending.count())
        assertEquals(1, events.findAll().count { it.pendingBonusId != null })
        assertNotNull(events.findById(newer.id).orElseThrow().pendingBonusId)
    }

    @Test
    fun `existing accepted pending is reused and never counted twice`() {
        val event = event()
        shown(event)
        mvc.perform(post("/api/posm/recommendations/${event.id}/outcome").header("X-Posm-Key", POSM_KEY)
            .contentType(MediaType.APPLICATION_JSON).content("""{"outcome":"accepted"}"""))
            .andExpect(status().isOk)
        val acceptedPending = events.findById(event.id).orElseThrow().pendingBonusId
        sale()
        shown(event)
        assertEquals(1L, pending.count())
        assertEquals(acceptedPending, pending.findAll().single().id)
        assertEquals(RecommendationOutcome.accepted, events.findById(event.id).orElseThrow().outcome)
    }

    @Test
    fun `explicit acceptance after informational sale reuses the same pending`() {
        val event = event()
        shown(event)
        sale()
        val bonus = pending.findAll().single()
        mvc.perform(post("/api/posm/recommendations/${event.id}/outcome").header("X-Posm-Key", POSM_KEY)
            .contentType(MediaType.APPLICATION_JSON).content("""{"outcome":"accepted"}"""))
            .andExpect(status().isOk)
        assertEquals(1L, pending.count())
        assertEquals(bonus.id, events.findById(event.id).orElseThrow().pendingBonusId)
    }

    @Test
    fun `another displayed group event cannot prove a different existing bonus snapshot`() {
        val accepted = event().also { it.bonus = 650; events.save(it) }
        mvc.perform(post("/api/posm/recommendations/${accepted.id}/outcome").header("X-Posm-Key", POSM_KEY)
            .contentType(MediaType.APPLICATION_JSON).content("""{"outcome":"accepted"}"""))
            .andExpect(status().isOk)
        val actuallyShown = event("rec_other_rule", trigger = "acc_second").also {
            it.ruleId = "rule_other"; events.save(it)
        }
        shown(actuallyShown)
        sale()
        val bonus = pending.findAll().single()
        assertEquals(650L, bonus.bonus)
        assertNull(bonus.sourceSaleId, "A different displayed bonus is not proof for this reservation")
        assertNull(events.findById(actuallyShown.id).orElseThrow().pendingBonusId)
    }

    @Test
    fun `rejected offer is never resurrected by late ACK or sale`() {
        val event = event().also { it.outcome = RecommendationOutcome.rejected; events.save(it) }
        shown(event)
        sale()
        shown(event)
        assertEquals(0L, pending.count())
        assertEquals(RecommendationOutcome.rejected, events.findById(event.id).orElseThrow().outcome)
    }

    @Test
    fun `manual receipt rejection is not reversed by duplicate evidence`() {
        val event = event()
        shown(event)
        sale()
        val receipt = receipts.findFirstByPendingBonusId(pending.findAll().single().id)!!
        receipt.status = ReceiptStatus.rejected
        receipts.save(receipt)
        shown(event)
        sale()
        assertEquals(ReceiptStatus.rejected, receipts.findById(receipt.id).orElseThrow().status)
        assertEquals(1L, pending.count())
        assertEquals(0L, pharmacists.findById("u_money").orElseThrow().balance)
    }

    @Test
    fun `expired pending is never replaced by a retry`() {
        val event = event()
        shown(event)
        sale()
        val bonus = pending.findAll().single()
        bonus.status = PendingBonusStatus.expired
        pending.save(bonus)
        shown(event)
        sale()
        assertEquals(1L, pending.count())
        assertEquals(PendingBonusStatus.expired, pending.findById(bonus.id).orElseThrow().status)
    }

    @Test
    fun `same session ID in another pharmacy cannot earn this event`() {
        val event = event(pharmacy = "ph_other")
        shown(event)
        sale()
        assertEquals(0L, pending.count())
    }

    @Test
    fun `different receipt session cannot earn this event`() {
        val event = event(session = "another-session")
        shown(event)
        sale()
        assertEquals(0L, pending.count())
    }

    @Test
    fun `different or unmapped seller cannot earn another pharmacist event`() {
        val event = event()
        shown(event)
        sale(pharmacist = "u_other")
        sale(id = "sale_unmapped", pharmacist = "unknown-standardn-user")
        assertEquals(0L, pending.count())
    }

    @Test
    fun `a missing event pharmacist is not filled from the later sale`() {
        val event = event().also { it.pharmacistId = ""; events.save(it) }
        shown(event)
        sale()
        assertEquals(0L, pending.count())
    }

    @Test
    fun `name-only sale cannot create monetary reward even when names match exactly`() {
        val event = event()
        shown(event)
        sale(item = PosSaleItemDto(name = "Товар 20 мг капсулы №30", qty = 1.0, price = 1000, total = 1000))
        assertEquals(0L, pending.count())
    }

    @Test
    fun `unknown EAN cannot fall back to a matching name or local SKU for money`() {
        val event = event()
        shown(event)
        sale(item = PosSaleItemDto(sku = "SN-123", barcode = "4870000000000", name = "Товар 20 мг капсулы №30", qty = 1.0, price = 1000, total = 1000))
        assertEquals(0L, pending.count())
    }

    @Test
    fun `ambiguous EAN cannot fall back to matching local SKU for money`() {
        products.save(ProductEntity(id = "p_collision", name = "Другая форма", barcode = BARCODE))
        val event = event()
        shown(event)
        sale(item = PosSaleItemDto(sku = "SN-123", barcode = BARCODE, qty = 1.0, price = 1000, total = 1000))
        assertEquals(0L, pending.count())
    }

    @Test
    fun `conflicting known EAN and local SKU cannot prove a monetary product match`() {
        products.save(ProductEntity(id = "p_different_dose", name = "Товар 40 мг", ipartId = "SN-999"))
        val event = event()
        shown(event)
        sale(item = PosSaleItemDto(sku = "SN-999", barcode = BARCODE, qty = 1.0, price = 1000, total = 1000))
        assertEquals(0L, pending.count())
    }

    @Test
    fun `unique exact local SKU can confirm a sale only when no EAN was supplied`() {
        val event = event()
        shown(event)
        sale(item = PosSaleItemDto(sku = "SN-123", qty = 1.0, price = 1000, total = 1000))
        assertEquals(1L, pending.count())
    }

    @Test
    fun `zero and negative sale quantity or total never count as a sale reward`() {
        val event = event()
        shown(event)
        sale(id = "sale_zero", item = PosSaleItemDto(barcode = BARCODE, qty = 0.0, price = 1000, total = 0))
        sale(id = "sale_return", item = PosSaleItemDto(barcode = BARCODE, qty = -1.0, price = 1000, total = -1000))
        assertEquals(0L, pending.count())
    }

    @Test
    fun `old event and delayed old sale before activation cutoff never earn a new pending`() {
        val event = event(at = CUTOFF.minusSeconds(60))
        shown(event, CUTOFF.minusSeconds(30))
        sale(at = CUTOFF.minusSeconds(10))
        assertEquals(0L, pending.count())
    }

    @Test
    fun `a new ACK and sale cannot monetize a pre-activation generated event`() {
        val event = event(at = CUTOFF.minusSeconds(60))
        shown(event)
        sale()
        assertEquals(0L, pending.count())
    }

    @Test
    fun `a missing or disappearing Firebird document is not proof of a printed sale`() {
        val event = event()
        shown(event)
        sale(id = "sale_firebird", captureSource = "standardn-firebird-close")
        sale(id = "sale_unknown", captureSource = "unknown")
        assertEquals(0L, pending.count())
    }

    @Test
    fun `a shown recommendation with no bonus does not reserve money`() {
        val event = event().also { it.bonus = 0; events.save(it) }
        shown(event)
        sale()
        assertEquals(0L, pending.count())
    }

    @Test
    fun `raw cashier name alone is not trusted financial identity`() {
        val event = event()
        shown(event)
        sale(pharmacist = "unknown-user", pharmacistName = "u_money")
        assertEquals("standardn_name_match", sales.findById("sale_money").orElseThrow().pharmacistSource)
        assertEquals(0L, pending.count())
    }

    @Test
    fun `future-dated client ACK and printed sale are not rewritten or rewarded`() {
        val future = Instant.now().plusSeconds(3600)
        val event = event()
        shown(event, future.minusSeconds(30))
        sale(at = future)
        assertEquals(0L, pending.count())
        assertEquals(future, sales.findById("sale_money").orElseThrow().printedAt)
        assertEquals(future.minusSeconds(30), events.findById(event.id).orElseThrow().displayedAt)
    }

    @Test
    fun `later unrelated receipt cannot take the existing source-bound pending`() {
        val event = event()
        shown(event)
        sale()
        val bonus = pending.findAll().single()
        val firstReceipt = receipts.findFirstByPendingBonusId(bonus.id)!!
        sale(id = "sale_other_receipt", session = "other-session")
        assertEquals(1L, pending.count())
        val actualReceipt = receipts.findFirstByPendingBonusId(bonus.id)!!
        assertEquals(firstReceipt.id, actualReceipt.id)
        assertEquals("fiscal_sale_money", actualReceipt.fiscalId)
        assertEquals("sale_money", pending.findById(bonus.id).orElseThrow().sourceSaleId)
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `concurrent ACK print retry and explicit outcome create only one pending`() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            repeat(3) { iteration ->
                val at = soldAt
                val session = "session_race_$iteration"
                val event = event("rec_race_$iteration", session, at = at.minusSeconds(90))
                val start = CountDownLatch(1)
                val operations = listOf<() -> Unit>(
                    { shown(event, at.minusSeconds(60)) },
                    { sale(id = "sale_race_$iteration", session = session, at = at) },
                    { sale(id = "sale_race_$iteration", session = session, at = at) },
                    {
                        mvc.perform(post("/api/posm/recommendations/${event.id}/outcome").header("X-Posm-Key", POSM_KEY)
                            .contentType(MediaType.APPLICATION_JSON).content("""{"outcome":"accepted"}"""))
                            .andExpect(status().isOk)
                    },
                ).map { action -> CompletableFuture.runAsync({ start.await(); action() }, executor) }
                start.countDown()
                CompletableFuture.allOf(*operations.toTypedArray()).get(15, TimeUnit.SECONDS)
                assertEquals(iteration + 1L, pending.count())
                assertNotNull(events.findById(event.id).orElseThrow().displayedAt)
                assertNotNull(events.findById(event.id).orElseThrow().pendingBonusId)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `display after the sale or outside the evidence window never earns pending`() {
        val at = soldAt
        val event = event(at = at.minusSeconds(3600))
        shown(event, at.plusSeconds(60))
        sale(at = at)
        assertEquals(0L, pending.count())
        assertNull(events.findById(event.id).orElseThrow().pendingBonusId)
    }

    private fun event(
        id: String = "rec_money", session: String = "session_money", pharmacy: String = "ph_money",
        at: Instant = soldAt.minusSeconds(90), trigger: String = "p_trigger",
    ) = events.save(RecommendationEventEntity(
        id = id, sessionId = session, pharmacistId = "u_money", pharmacyId = pharmacy,
        ruleId = "rule_money", triggerSku = trigger, recommendSku = "p_offer",
        recommendName = "Товар 20 мг капсулы №30", expectedAmount = 1000, bonus = 250, shownAt = at,
    ))

    private fun shown(event: RecommendationEventEntity, at: Instant = soldAt.minusSeconds(60)) {
        mvc.perform(post("/api/posm/recommendations/${event.id}/shown").header("X-Posm-Key", POSM_KEY)
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(MarkShownRequest(at))))
            .andExpect(status().isOk)
    }

    private fun sale(
        id: String = "sale_money", session: String = "session_money", pharmacist: String = "u_money",
        at: Instant = soldAt, captureSource: String = "standardn-print-log",
        pharmacistName: String? = null,
        item: PosSaleItemDto = PosSaleItemDto(barcode = BARCODE, qty = 1.0, price = 1000, total = 1000),
    ) {
        mvc.perform(post("/api/posm/sales").header("X-Posm-Key", POSM_KEY).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(PosSaleRequest(
                saleId = id, pharmacistId = pharmacist, pharmacistName = pharmacistName,
                pharmacyId = "ph_money", sessionId = session,
                fiscalId = "fiscal_$id", captureSource = captureSource,
                totalAmount = item.total, items = listOf(item), printedAt = at,
            )))).andExpect(status().isOk)
    }
}
