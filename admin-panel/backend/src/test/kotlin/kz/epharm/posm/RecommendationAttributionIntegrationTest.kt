package kz.epharm.posm

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import kz.epharm.catalog.entity.ProductEntity
import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.dashboard.service.RecommendationAnalyticsService
import kz.epharm.pharmacies.entity.ChainEntity
import kz.epharm.pharmacies.entity.PharmacyEntity
import kz.epharm.pharmacies.entity.PharmacyGroup
import kz.epharm.pharmacies.repository.ChainRepository
import kz.epharm.pharmacies.repository.PharmacyRepository
import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.posm.dto.CartItemDto
import kz.epharm.posm.dto.MarkShownRequest
import kz.epharm.posm.dto.PosSaleItemDto
import kz.epharm.posm.dto.PosSaleRequest
import kz.epharm.posm.dto.RecommendRequest
import kz.epharm.posm.dto.RecommendResponse
import kz.epharm.posm.repository.PosSaleRepository
import kz.epharm.posm.repository.RecommendationEventRepository
import kz.epharm.receipts.repository.PendingBonusRepository
import kz.epharm.rules.entity.RuleEntity
import kz.epharm.rules.entity.RuleStatus
import kz.epharm.rules.entity.RuleTrigger
import kz.epharm.rules.entity.RuleType
import kz.epharm.rules.repository.RuleRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Атрибуция продаж к показанным рекомендациям (V032, Задача 1 + 1.2):
 *  - рекомендованный товар попал в чек той же сессии → событие закрыто продажей (sold_at,
 *    sale_id, seconds_to_sale);
 *  - рекомендованного товара в чеке нет → событие НЕ атрибутируется;
 *  - пинг «показано» проставляет displayed_at;
 *  - аналитика дашборда отражает конверсию и «лог» событий.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Transactional
class RecommendationAttributionIntegrationTest {

    companion object {
        private const val POSM_KEY = "dev-posm-key"

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("epharm_test").withUsername("epharm").withPassword("epharm_test")
            .apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun props(reg: DynamicPropertyRegistry) {
            reg.add("spring.datasource.url") { postgres.jdbcUrl }
            reg.add("spring.datasource.username") { postgres.username }
            reg.add("spring.datasource.password") { postgres.password }
        }
    }

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var ruleRepository: RuleRepository
    @Autowired private lateinit var productRepository: ProductRepository
    @Autowired private lateinit var pharmacistRepository: PharmacistRepository
    @Autowired private lateinit var pharmacyRepository: PharmacyRepository
    @Autowired private lateinit var chainRepository: ChainRepository
    @Autowired private lateinit var pendingBonusRepository: PendingBonusRepository
    @Autowired private lateinit var eventRepository: RecommendationEventRepository
    @Autowired private lateinit var posSaleRepository: PosSaleRepository
    @Autowired private lateinit var analyticsService: RecommendationAnalyticsService

    private val barBio = "4603423004936" // триггер (Bioderma)
    private val barZen = "4600000000017" // рекомендация (SelfieLab Zen) — есть в чеке при конверсии

    @BeforeEach
    fun seed() {
        eventRepository.deleteAll()
        posSaleRepository.deleteAll()
        pendingBonusRepository.deleteAll()
        ruleRepository.deleteAll()
        productRepository.deleteAll()
        pharmacistRepository.deleteAll()
        pharmacyRepository.deleteAll()
        chainRepository.deleteAll()

        chainRepository.save(
            ChainEntity(id = "ch_t", name = "Сеть Т", color = "#16C97A", points = 10)
                .also { it.group = PharmacyGroup.pilot },
        )
        pharmacyRepository.save(
            PharmacyEntity(
                id = "ph_t", name = "Аптека Т", chainId = "ch_t", chainName = "Сеть Т",
                city = "Алматы", district = "", addr = "",
            ).also { it.group = PharmacyGroup.pilot },
        )
        pharmacistRepository.save(
            PharmacistEntity(
                id = "u_t", name = "Тест Фарм", iin = "900115300013", phone = "+77001234567",
                pharmacyId = "ph_t", pharmacyName = "Аптека Т", city = "Алматы",
                balance = 0, earned30d = 0,
            ),
        )

        // Триггер p_bio (штрих-код barBio) → рекомендация p_zen (штрих-код barZen, чтобы продажа
        // рекомендованного товара резолвилась обратно в p_zen).
        productRepository.save(product("p_bio", "Bioderma", 4200, barBio, ipartId = "80309"))
        productRepository.save(product("p_zen", "SelfieLab Zen", 4500, barZen))
        ruleRepository.save(rule("r_s_1", RuleType.substitution, trigger("p_bio"), "p_zen", 650))
    }

    @Test
    fun `рекомендованный товар продан в той же сессии → атрибуция (sold_at + seconds_to_sale)`() {
        val rec = recommendByBarcode("sess_a", listOf(barBio)).recommendations[0]
        assertEquals("p_zen", rec.recommendSku)

        // Чек той же сессии содержит рекомендованный товар (barZen) → атрибуция.
        postSale("sale_a", "sess_a", listOf(saleItem(barZen, 4500)))

        val ev = eventRepository.findById(rec.eventId).get()
        assertNotNull(ev.soldAt, "продажа должна закрыть рекомендацию")
        assertEquals("sale_a", ev.saleId)
        assertNotNull(ev.secondsToSale)
        assertTrue(ev.secondsToSale!! >= 0, "время до продажи неотрицательно")
    }

    @Test
    fun `рекомендованного товара нет в чеке → событие НЕ атрибутируется`() {
        val rec = recommendByBarcode("sess_b", listOf(barBio)).recommendations[0]

        // В чеке только сам триггер (Bioderma), рекомендованного Zen нет.
        postSale("sale_b", "sess_b", listOf(saleItem(barBio, 4200)))

        val ev = eventRepository.findById(rec.eventId).get()
        assertNull(ev.soldAt, "без рекомендованного товара в чеке — не атрибутируем")
        assertNull(ev.secondsToSale)
    }

    @Test
    fun `продажа другой сессии не закрывает рекомендацию (корреляция по session_id)`() {
        val rec = recommendByBarcode("sess_c", listOf(barBio)).recommendations[0]

        // Тот же товар продан, но в ДРУГОЙ сессии → не наша рекомендация.
        postSale("sale_c", "sess_OTHER", listOf(saleItem(barZen, 4500)))

        val ev = eventRepository.findById(rec.eventId).get()
        assertNull(ev.soldAt)
    }

    @Test
    fun `пинг показа проставляет displayed_at (идемпотентно)`() {
        val rec = recommendByBarcode("sess_d", listOf(barBio)).recommendations[0]
        val shownAt = Instant.parse("2026-06-26T10:00:00Z")

        mockMvc.perform(
            post("/api/posm/recommendations/${rec.eventId}/shown")
                .header("X-Posm-Key", POSM_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(MarkShownRequest(shownAt))),
        ).andExpect(status().isOk)

        assertEquals(shownAt, eventRepository.findById(rec.eventId).get().displayedAt)

        // Повтор не перезаписывает (идемпотентность).
        mockMvc.perform(
            post("/api/posm/recommendations/${rec.eventId}/shown")
                .header("X-Posm-Key", POSM_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(MarkShownRequest(Instant.parse("2026-06-26T11:00:00Z")))),
        ).andExpect(status().isOk)
        assertEquals(shownAt, eventRepository.findById(rec.eventId).get().displayedAt)
    }

    @Test
    fun `аналитика отражает конверсию и единый журнал показ+продажа`() {
        val rec = recommendByBarcode("sess_e", listOf(barBio)).recommendations[0]
        postSale("sale_e", "sess_e", listOf(saleItem(barZen, 4500)))

        val dto = analyticsService.analytics(100)
        assertEquals(1, dto.shown)
        assertEquals(1, dto.converted)
        assertEquals(100.0, dto.convRate)
        assertEquals(1, dto.convertedUnder2m, "продажа сразу после показа → в корзине ≤2 мин")
        assertEquals(4500L, dto.attributedRevenue)

        // Журнал содержит и показ, и продажу (две наши таблицы в одном логе).
        val show = dto.log.first { it.id == rec.eventId }
        assertEquals("show", show.type)
        assertTrue(show.converted)
        assertEquals("SelfieLab Zen", show.title)        // ЧТО рекомендовали
        assertEquals("Bioderma", show.triggerName)       // ЧТО выбрал покупатель (триггер)
        assertEquals("Аптека Т", show.pharmacyName)      // резолв pharmacyId → имя
        assertEquals("Тест Фарм", show.pharmacistName)   // резолв pharmacistId → имя

        val sale = dto.log.first { it.type == "sale" }
        assertEquals("sale_e", sale.id)
        assertEquals("Тест Фарм", sale.pharmacistName)
        assertEquals(1.0, sale.units)                    // продано единиц в чеке (Σ qty)
        // на строке продажи тоже видно время до продажи (этот чек закрыл показ).
        assertNotNull(sale.secondsToSale)
    }

    @Test
    fun `recommend без pharmacistId (пустой) принимается — фармацевт берётся из лога кассы`() {
        val body = mockMvc.perform(
            post("/api/posm/recommend")
                .header("X-Posm-Key", POSM_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"pharmacyId":"ph_t","sessionId":"sess_np","cart":[{"barcode":"$barBio"}]}"""),
        )
            .andExpect(status().isOk)
            .andReturn().response.getContentAsString(Charsets.UTF_8)
        val resp = objectMapper.readValue(body, RecommendResponse::class.java)
        assertEquals(1, resp.recommendations.size, "pharmacistId опционален — рекомендация всё равно отдаётся")
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun recommendByBarcode(session: String, barcodes: List<String>): RecommendResponse {
        val body = mockMvc.perform(
            post("/api/posm/recommend")
                .header("X-Posm-Key", POSM_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        RecommendRequest(
                            pharmacistId = "u_t", pharmacyId = "ph_t", sessionId = session,
                            cart = barcodes.map { CartItemDto(barcode = it) },
                        ),
                    ),
                ),
        )
            .andExpect(status().isOk)
            .andReturn().response.getContentAsString(Charsets.UTF_8)
        return objectMapper.readValue(body, RecommendResponse::class.java)
    }

    private fun postSale(saleId: String, session: String, items: List<PosSaleItemDto>) {
        mockMvc.perform(
            post("/api/posm/sales")
                .header("X-Posm-Key", POSM_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        PosSaleRequest(
                            saleId = saleId, pharmacistId = "u_t", pharmacyId = "ph_t",
                            sessionId = session, totalAmount = items.sumOf { it.total },
                            items = items, printedAt = Instant.now(),
                        ),
                    ),
                ),
        ).andExpect(status().isOk)
    }

    private fun saleItem(barcode: String, price: Long) =
        PosSaleItemDto(barcode = barcode, name = "", qty = 1.0, price = price, total = price)

    private fun product(id: String, name: String, price: Int, barcode: String?, ipartId: String? = null) =
        ProductEntity(id = id, name = name, brand = "B", vendor = "V", mnn = "", price = price)
            .also { it.barcode = barcode; it.ipartId = ipartId }

    private fun trigger(productId: String) = RuleTrigger(kind = "product", value = productId)

    private fun rule(id: String, type: RuleType, trigger: RuleTrigger, recommend: String, bonus: Int) =
        RuleEntity(id = id, trigger = trigger, recommend = recommend, bonus = bonus, createdBy = "seed")
            .also { it.type = type; it.status = RuleStatus.active }
}
