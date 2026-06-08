package kz.epharm.posm

import com.fasterxml.jackson.databind.ObjectMapper
import kz.epharm.catalog.entity.ProductEntity
import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.pharmacies.entity.ChainEntity
import kz.epharm.pharmacies.entity.PharmacyEntity
import kz.epharm.pharmacies.entity.PharmacyGroup
import kz.epharm.pharmacies.repository.ChainRepository
import kz.epharm.pharmacies.repository.PharmacyRepository
import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.posm.dto.CartItemDto
import kz.epharm.posm.dto.OutcomeRequest
import kz.epharm.posm.dto.RecommendRequest
import kz.epharm.posm.dto.RecommendResponse
import kz.epharm.posm.entity.ProductPosCodeEntity
import kz.epharm.posm.repository.ProductPosCodeRepository
import kz.epharm.posm.repository.RecommendationEventRepository
import kz.epharm.receipts.repository.PendingBonusRepository
import kz.epharm.rules.entity.RuleEntity
import kz.epharm.rules.entity.RuleStatus
import kz.epharm.rules.entity.RuleTrigger
import kz.epharm.rules.entity.RuleType
import kz.epharm.rules.repository.RuleRepository
import org.junit.jupiter.api.Assertions.assertEquals
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Transactional
class PosmRecommendIntegrationTest {

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
    @Autowired private lateinit var posCodeRepository: ProductPosCodeRepository

    @BeforeEach
    fun seed() {
        eventRepository.deleteAll()
        posCodeRepository.deleteAll()
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
                id = "u_t", name = "Тест Фарм", iin = "990101300123", phone = "+77001234567",
                pharmacyId = "ph_t", pharmacyName = "Аптека Т", city = "Алматы",
                balance = 0, earned30d = 0,
            ),
        )

        // Каталог: триггеры + рекомендации.
        listOf(
            product("p_bio", "Bioderma", 4200),
            product("p_olda", "Старый бренд А", 3000),
            product("p_food", "Детское питание", 1500),
            product("p_zen", "SelfieLab Zen", 4500),
            product("p_zen2", "SelfieLab Zen 2", 4100),
            product("p_cream", "Крем под подгузник", 1900),
        ).forEach { productRepository.save(it) }

        // 2 замены (бонус 650 и 500) + 1 cross-sell (320).
        ruleRepository.save(rule("r_s_1", RuleType.substitution, trigger("p_bio"), "p_zen", 650))
        ruleRepository.save(rule("r_s_2", RuleType.substitution, trigger("p_olda"), "p_zen2", 500))
        ruleRepository.save(rule("r_x_1", RuleType.crosssell, trigger("p_food"), "p_cream", 320))
    }

    @Test
    fun `замены раньше cross-sell, отсортированы по бонусу, лимит 2`() {
        val resp = recommend("s1", listOf("p_bio", "p_olda", "p_food"))
        assertEquals(2, resp.recommendations.size, "лимит top-2")
        // Обе замены впереди, cross-sell (320) выброшен лимитом.
        assertEquals("substitution", resp.recommendations[0].kind)
        assertEquals("p_zen", resp.recommendations[0].recommendSku)
        assertEquals(650, resp.recommendations[0].bonus)
        assertEquals("substitution", resp.recommendations[1].kind)
        assertEquals("p_zen2", resp.recommendations[1].recommendSku)
        assertEquals(500, resp.recommendations[1].bonus)
        assertEquals("Bioderma", resp.recommendations[0].triggerName)
    }

    @Test
    fun `cross-sell показывается когда нет замен`() {
        val resp = recommend("s2", listOf("p_food"))
        assertEquals(1, resp.recommendations.size)
        assertEquals("crosssell", resp.recommendations[0].kind)
        assertEquals("p_cream", resp.recommendations[0].recommendSku)
        assertEquals(320, resp.recommendations[0].bonus)
    }

    @Test
    fun `замена и cross-sell приходят ВМЕСТЕ (замена первой) — для табов на кассе`() {
        // Корзина даёт ровно одну замену (p_bio→p_zen) и один cross-sell (p_food→p_cream).
        // На кассе это две вкладки «Замена | Допродажа» в одной карточке.
        val resp = recommend("s7", listOf("p_bio", "p_food"))
        assertEquals(2, resp.recommendations.size)
        assertEquals("substitution", resp.recommendations[0].kind) // замена впереди
        assertEquals("p_zen", resp.recommendations[0].recommendSku)
        assertEquals("crosssell", resp.recommendations[1].kind)    // cross-sell вторым
        assertEquals("p_cream", resp.recommendations[1].recommendSku)
    }

    @Test
    fun `принятая рекомендация создаёт pending_bonus, баланс ещё не начислен`() {
        val resp = recommend("s3", listOf("p_bio"))
        val eventId = resp.recommendations[0].eventId

        mockMvc.perform(
            post("/api/posm/recommendations/$eventId/outcome")
                .header("X-Posm-Key", POSM_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(OutcomeRequest(outcome = "accepted"))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.outcome").value("accepted"))
            .andExpect(jsonPath("$.pendingBonusId").isNotEmpty)

        val pending = pendingBonusRepository.findAll().filter { it.pharmacistId == "u_t" }
        assertEquals(1, pending.size)
        assertEquals(650L, pending[0].bonus)
        assertEquals("p_zen", pending[0].sku)
        // Бонус ещё НЕ начислен — ждёт подтверждения чеком (сверка §3.5).
        assertEquals(0L, pharmacistRepository.findById("u_t").get().balance)
    }

    @Test
    fun `отклонённая рекомендация не показывается повторно в этом чеке`() {
        val first = recommend("s4", listOf("p_bio"))
        val eventId = first.recommendations[0].eventId

        mockMvc.perform(
            post("/api/posm/recommendations/$eventId/outcome")
                .header("X-Posm-Key", POSM_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(OutcomeRequest(outcome = "rejected"))),
        ).andExpect(status().isOk)

        val second = recommend("s4", listOf("p_bio"))
        assertTrue(second.recommendations.none { it.recommendSku == "p_zen" }, "отклонённое не повторяем")
    }

    @Test
    fun `код кассы (iPartID) резолвится в productId через product_pos_codes`() {
        // Касса прислала числовой артикул 80309 — маппится на p_bio → срабатывает замена.
        posCodeRepository.save(ProductPosCodeEntity(posCode = 80309, productId = "p_bio"))
        val resp = recommend("s6", listOf("80309"))
        assertEquals(1, resp.recommendations.size)
        assertEquals("p_zen", resp.recommendations[0].recommendSku)
    }

    @Test
    fun `без device-key → 401`() {
        mockMvc.perform(
            post("/api/posm/recommend")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req("s5", listOf("p_bio")))),
        ).andExpect(status().isUnauthorized)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun recommend(session: String, skus: List<String>): RecommendResponse {
        val body = mockMvc.perform(
            post("/api/posm/recommend")
                .header("X-Posm-Key", POSM_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req(session, skus))),
        )
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        return objectMapper.readValue(body, RecommendResponse::class.java)
    }

    private fun req(session: String, skus: List<String>) = RecommendRequest(
        pharmacistId = "u_t", pharmacyId = "ph_t", sessionId = session,
        cart = skus.map { CartItemDto(sku = it) },
    )

    private fun product(id: String, name: String, price: Int) =
        ProductEntity(id = id, name = name, brand = "B", vendor = "V", mnn = "", price = price)

    private fun trigger(productId: String) = RuleTrigger(kind = "product", value = productId)

    private fun rule(id: String, type: RuleType, trigger: RuleTrigger, recommend: String, bonus: Int) =
        RuleEntity(id = id, trigger = trigger, recommend = recommend, bonus = bonus, createdBy = "seed")
            .also { it.type = type; it.status = RuleStatus.active }
}
