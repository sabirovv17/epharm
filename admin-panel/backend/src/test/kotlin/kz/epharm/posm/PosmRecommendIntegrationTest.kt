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
import kz.epharm.pharmacists.entity.PharmacistStatus
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.posm.dto.CartItemDto
import kz.epharm.posm.dto.OutcomeRequest
import kz.epharm.posm.dto.RecommendRequest
import kz.epharm.posm.dto.RecommendResponse
import kz.epharm.posm.repository.RecommendationEventRepository
import kz.epharm.receipts.repository.PendingBonusRepository
import kz.epharm.rules.entity.RuleCard
import kz.epharm.rules.entity.RuleComparisonRow
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

    // EAN-13 штрих-коды демо-товаров (по ним матчится корзина кассы).
    private val barBio = "4603423004936"
    private val barOlda = "4603423001973"
    private val barFood = "3858881254039"
    // Триггерные товары (рекомендации не нужны для матча корзины — они только в правилах).

    @BeforeEach
    fun seed() {
        eventRepository.deleteAll()
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
            ).also { it.status = PharmacistStatus.active },
        )

        // Каталог: триггеры (со штрих-кодами) + рекомендации.
        listOf(
            product("p_bio", "Bioderma", 4200, barBio, ipartId = "80309"),
            product("p_olda", "Старый бренд А", 3000, barOlda),
            product("p_food", "Детское питание", 1500, barFood),
            product("p_zen", "SelfieLab Zen", 4500, null),
            product("p_zen2", "SelfieLab Zen 2", 4100, null),
            product("p_cream", "Крем под подгузник", 1900, null),
        ).forEach { productRepository.save(it) }

        // 2 замены (бонус 650 и 500) + 1 cross-sell (320).
        ruleRepository.save(rule("r_s_1", RuleType.substitution, trigger("p_bio"), "p_zen", 650))
        ruleRepository.save(rule("r_s_2", RuleType.substitution, trigger("p_olda"), "p_zen2", 500))
        ruleRepository.save(rule("r_x_1", RuleType.crosssell, trigger("p_food"), "p_cream", 320))
    }

    @Test
    fun `замены раньше cross-sell, отсортированы по бонусу, лимит 2`() {
        val resp = recommendByBarcode("s1", listOf(barBio, barOlda, barFood))
        assertEquals(2, resp.recommendations.size, "лимит top-2")
        // Обе замены впереди, cross-sell (320) выброшен лимитом.
        assertEquals("substitution", resp.recommendations[0].kind)
        assertEquals("p_zen", resp.recommendations[0].recommendSku)
        assertEquals(650, resp.recommendations[0].bonus)
        assertEquals("80309", resp.recommendations[0].triggerIpartId)
        assertEquals("substitution", resp.recommendations[1].kind)
        assertEquals("p_zen2", resp.recommendations[1].recommendSku)
        assertEquals(500, resp.recommendations[1].bonus)
        assertEquals("Bioderma", resp.recommendations[0].triggerName)
    }

    @Test
    fun `cross-sell показывается когда нет замен`() {
        val resp = recommendByBarcode("s2", listOf(barFood))
        assertEquals(1, resp.recommendations.size)
        assertEquals("crosssell", resp.recommendations[0].kind)
        assertEquals("p_cream", resp.recommendations[0].recommendSku)
        assertEquals(320, resp.recommendations[0].bonus)
    }

    @Test
    fun `замена и cross-sell приходят ВМЕСТЕ (замена первой) — для табов на кассе`() {
        // Корзина даёт ровно одну замену (p_bio→p_zen) и один cross-sell (p_food→p_cream).
        // На кассе это две вкладки «Замена | Допродажа» в одной карточке.
        val resp = recommendByBarcode("s7", listOf(barBio, barFood))
        assertEquals(2, resp.recommendations.size)
        assertEquals("substitution", resp.recommendations[0].kind) // замена впереди
        assertEquals("p_zen", resp.recommendations[0].recommendSku)
        assertEquals("crosssell", resp.recommendations[1].kind)    // cross-sell вторым
        assertEquals("p_cream", resp.recommendations[1].recommendSku)
    }

    @Test
    fun `богатая карточка из правила приходит на кассу (сравнение, партнёр, цель)`() {
        // У товара-триггера задан объём (строка «покупатель попросил»).
        productRepository.save(productRepository.findById("p_bio").get().also { it.volume = "150 мл" })
        // Базовое правило на p_zen убираем, чтобы выиграло наше card-правило.
        ruleRepository.deleteById("r_s_1")
        // Правило с богатой карточкой (как задал бы менеджер в админке).
        ruleRepository.save(
            rule("r_card", RuleType.substitution, trigger("p_bio"), "p_zen", 650).also {
                it.card = RuleCard(
                    partnerLabel = "ПАРТНЁР EPHARM",
                    comparison = listOf(
                        RuleComparisonRow("Состав", "раствор", "✓ вода Адриатики", true),
                        RuleComparisonRow("Объём", "150 мл", "150 мл", false),
                    ),
                    goalLabel = "замен в мае",
                    goalTarget = 10,
                    goalBonus = 2000,
                )
            },
        )

        val r = recommendByBarcode("sc", listOf(barBio)).recommendations[0]
        assertEquals("p_zen", r.recommendSku)
        assertEquals("150 мл", r.triggerVolume)           // из каталога (product.volume)
        assertEquals(4200, r.triggerPrice)                // из каталога (product.price)
        assertEquals("ПАРТНЁР EPHARM", r.partnerLabel)    // из правила (card)
        assertEquals(2, r.comparison.size)
        assertEquals("Состав", r.comparison[0].label)
        assertTrue(r.comparison[0].recommendHighlight)
        assertEquals("цель «0/10 замен в мае»", r.goalText) // динамика: пока 0 принятых
        assertEquals(2000, r.goalBonus)

        // Принимаем рекомендацию → счётчик цели должен стать 1/10 (динамический).
        mockMvc.perform(
            post("/api/posm/recommendations/${r.eventId}/outcome")
                .header("X-Posm-Key", POSM_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(OutcomeRequest(outcome = "accepted"))),
        ).andExpect(status().isOk)

        val r2 = recommendByBarcode("sc2", listOf(barBio)).recommendations[0]
        assertEquals("цель «1/10 замен в мае»", r2.goalText)
    }

    @Test
    fun `принятая рекомендация создаёт pending_bonus, баланс ещё не начислен`() {
        val resp = recommendByBarcode("s3", listOf(barBio))
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
        val first = recommendByBarcode("s4", listOf(barBio))
        val eventId = first.recommendations[0].eventId

        mockMvc.perform(
            post("/api/posm/recommendations/$eventId/outcome")
                .header("X-Posm-Key", POSM_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(OutcomeRequest(outcome = "rejected"))),
        ).andExpect(status().isOk)

        val second = recommendByBarcode("s4", listOf(barBio))
        assertTrue(second.recommendations.none { it.recommendSku == "p_zen" }, "отклонённое не повторяем")
    }

    @Test
    fun `скан по штрих-коду (EAN-13) резолвится в товар → срабатывает замена`() {
        // Касса прислала EAN-13 4603423004936 (Bioderma) → матч по barcode → замена на p_zen.
        val resp = recommendByBarcode("s6", listOf(barBio))
        assertEquals(1, resp.recommendations.size)
        assertEquals("p_zen", resp.recommendations[0].recommendSku)
        assertEquals("Bioderma", resp.recommendations[0].triggerName)
    }

    @Test
    fun `скан по iPartID резолвится в товар → срабатывает замена`() {
        // Стандарт-Н может прислать только iPartID без EAN-13. Если iPartID задан в админке,
        // matcher обязан сработать так же стабильно, как по barcode.
        val resp = recommend("s11", listOf(CartItemDto(sku = "80309")))
        assertEquals(1, resp.recommendations.size)
        assertEquals("p_zen", resp.recommendations[0].recommendSku)
        assertEquals("Bioderma", resp.recommendations[0].triggerName)
    }

    @Test
    fun `fallback по имени (sname из лога) когда штрих-код не пришёл`() {
        // Лог кассы пока без штрих-кода — только sname. Матч по нормализованному имени
        // («bioderma» == ProductEntity.name «Bioderma»).
        val resp = recommend("s8", listOf(CartItemDto(name = "Bioderma")))
        assertEquals(1, resp.recommendations.size)
        assertEquals("p_zen", resp.recommendations[0].recommendSku)
    }

    @Test
    fun `имя матчится нормализованно (регистр, пунктуация, пробелы)`() {
        // Касса прислала «BIODERMA!!»  с лишними знаками/регистром → всё равно матч на Bioderma.
        val resp = recommend("s9", listOf(CartItemDto(name = "  BIODERMA!! ")))
        assertEquals(1, resp.recommendations.size)
        assertEquals("p_zen", resp.recommendations[0].recommendSku)
    }

    @Test
    fun `штрих-код имеет приоритет над именем — неизвестное имя не мешает`() {
        // barcode резолвит p_bio (замена p_zen), даже если name мусорный.
        val resp = recommend("s10", listOf(CartItemDto(barcode = barBio, name = "мусор")))
        assertEquals(1, resp.recommendations.size)
        assertEquals("p_zen", resp.recommendations[0].recommendSku)
    }

    @Test
    fun `штрих-код имеет приоритет над конфликтующим локальным iPartID`() {
        // PARTS.ID is local to a pharmacy database. A numerically equal catalog ipartId can point
        // to a different product, but an exact EAN must still trigger the configured campaign.
        productRepository.save(product("p_local_collision", "Другой товар", 1000, "4870000000001", ipartId = "99123"))

        val resp = recommend(
            "s12",
            listOf(CartItemDto(sku = "99123", barcode = barBio, name = "Ivatherm-like scan")),
        )

        assertEquals(1, resp.recommendations.size)
        assertEquals("p_zen", resp.recommendations[0].recommendSku)
        assertEquals("Bioderma", resp.recommendations[0].triggerName)
    }

    @Test
    fun `без device-key → 401`() {
        mockMvc.perform(
            post("/api/posm/recommend")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(reqByBarcode("s5", listOf(barBio)))),
        ).andExpect(status().isUnauthorized)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun recommend(session: String, items: List<CartItemDto>): RecommendResponse {
        val body = mockMvc.perform(
            post("/api/posm/recommend")
                .header("X-Posm-Key", POSM_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req(session, items))),
        )
            .andExpect(status().isOk)
            .andReturn().response.getContentAsString(Charsets.UTF_8) // кириллица в карточке → UTF-8
        return objectMapper.readValue(body, RecommendResponse::class.java)
    }

    private fun recommendByBarcode(session: String, barcodes: List<String>): RecommendResponse =
        recommend(session, barcodes.map { CartItemDto(barcode = it) })

    private fun req(session: String, items: List<CartItemDto>) = RecommendRequest(
        pharmacistId = "u_t", pharmacyId = "ph_t", sessionId = session, cart = items,
    )

    private fun reqByBarcode(session: String, barcodes: List<String>) =
        req(session, barcodes.map { CartItemDto(barcode = it) })

    private fun product(id: String, name: String, price: Int, barcode: String?, ipartId: String? = null) =
        ProductEntity(id = id, name = name, brand = "B", vendor = "V", mnn = "", price = price)
            .also { it.barcode = barcode; it.ipartId = ipartId }

    private fun trigger(productId: String) = RuleTrigger(kind = "product", value = productId)

    private fun rule(id: String, type: RuleType, trigger: RuleTrigger, recommend: String, bonus: Int) =
        RuleEntity(id = id, trigger = trigger, recommend = recommend, bonus = bonus, createdBy = "seed")
            .also { it.type = type; it.status = RuleStatus.active }
}
