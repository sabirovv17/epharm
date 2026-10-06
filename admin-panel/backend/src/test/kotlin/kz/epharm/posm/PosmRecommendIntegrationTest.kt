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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Transactional
class PosmRecommendIntegrationTest {

    companion object {
        private const val POSM_KEY = "dev-posm-key"
        private const val GROUP_KEY = "grp_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val SUBGROUP_KEY = "sub_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        private const val MNN_KEY = "mnn_cccccccccccccccccccccccccccccccc"

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
    @Autowired private lateinit var jdbc: JdbcTemplate

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
    fun `замены раньше cross-sell и все подходящие видны`() {
        val resp = recommendByBarcode("s1", listOf(barBio, barOlda, barFood))
        assertEquals(3, resp.recommendations.size)
        // Обе замены впереди, затем cross-sell.
        assertEquals("substitution", resp.recommendations[0].kind)
        assertEquals("p_zen", resp.recommendations[0].recommendSku)
        assertEquals(650, resp.recommendations[0].bonus)
        assertEquals("80309", resp.recommendations[0].triggerIpartId)
        assertEquals("substitution", resp.recommendations[1].kind)
        assertEquals("p_zen2", resp.recommendations[1].recommendSku)
        assertEquals(500, resp.recommendations[1].bonus)
        assertEquals("crosssell", resp.recommendations[2].kind)
        assertEquals("p_cream", resp.recommendations[2].recommendSku)
        assertEquals("Bioderma", resp.recommendations[0].triggerName)
    }

    @Test
    fun `backend возвращает не более пяти замен и пяти cross-sell`() {
        val trigger = product("p_cap", "Товар-триггер", 1_000, "4870000000099")
        productRepository.save(trigger)
        (1..6).forEach { index ->
            val substitution = product("p_cap_s_$index", "Аналог $index", 1_000 + index, null)
                .also { it.volume = "$index уп." }
            val crossSell = product("p_cap_x_$index", "Допродажа $index", 2_000 + index, null)
                .also { it.volume = "$index шт." }
            productRepository.saveAll(listOf(substitution, crossSell))
            ruleRepository.save(
                rule("r_cap_s_$index", RuleType.substitution, trigger("p_cap"), substitution.id, 700 - index),
            )
            ruleRepository.save(
                rule("r_cap_x_$index", RuleType.crosssell, trigger("p_cap"), crossSell.id, 600 - index),
            )
        }

        val resp = recommendByBarcode("s-cap", listOf("4870000000099"))
        val substitutions = resp.recommendations.filter { it.kind == "substitution" }
        val crossSells = resp.recommendations.filter { it.kind == "crosssell" }

        assertEquals(5, substitutions.size)
        assertEquals(5, crossSells.size)
        assertEquals((1..5).map { "p_cap_s_$it" }, substitutions.map { it.recommendSku })
        assertEquals((1..5).map { "p_cap_x_$it" }, crossSells.map { it.recommendSku })
        assertEquals("V", substitutions.first().recommendVendor)
        assertEquals("1 уп.", substitutions.first().recommendVolume)
    }

    @Test
    fun `ACC group subgroup and MNN match an arbitrary scanned product by exact EAN`() {
        val barcode = "4871234567890"
        publishAccSnapshot(
            AccRow("ware-1", barcode, GROUP_KEY, SUBGROUP_KEY, MNN_KEY),
        )
        ruleRepository.saveAll(
            listOf(
                rule("r_acc_group", RuleType.substitution, RuleTrigger("acc_group", GROUP_KEY), "p_zen", 0),
                rule("r_acc_subgroup", RuleType.substitution, RuleTrigger("acc_subgroup", SUBGROUP_KEY), "p_zen2", 0),
                rule("r_acc_mnn", RuleType.crosssell, RuleTrigger("acc_mnn", MNN_KEY), "p_cream", 0),
            ),
        )

        val result = recommend("s-acc", listOf(CartItemDto(sku = "SN-711", barcode = barcode, name = "Сканированный товар ACC")))
        assertEquals(listOf("p_zen2", "p_zen", "p_cream"), result.recommendations.map { it.recommendSku })
        assertTrue(result.recommendations.all { it.triggerBarcode == barcode })
        assertTrue(result.recommendations.all { it.triggerIpartId == "SN-711" })
        assertTrue(result.recommendations.all { it.triggerName == "Сканированный товар ACC" })
        assertTrue(result.recommendations.all { it.triggerSku?.startsWith("acc_") == true })
        assertEquals("Сканированный товар ACC", eventRepository.findById(result.recommendations.first().eventId).get().triggerName)

        // Real POSM sends barcode + name but often omits SKU when the barcode is available.
        val withoutSku = recommend("s-acc-posm", listOf(CartItemDto(barcode = barcode, name = "Товар с кассы")))
        assertEquals(3, withoutSku.recommendations.size)
        assertTrue(withoutSku.recommendations.all { it.triggerIpartId == null && it.triggerName == "Товар с кассы" })

        // A broad scope must never use the fuzzy/local-name fallback without an ACC barcode.
        val noBarcode = recommend("s-acc-no-ean", listOf(CartItemDto(name = "Сканированный товар ACC")))
        assertTrue(noBarcode.recommendations.isEmpty())
    }

    @Test
    fun `ambiguous ACC EAN fails closed and distinct concrete triggers get distinct events`() {
        val ambiguous = "4871234567807"
        val first = "4871234567814"
        val second = "4871234567821"
        publishAccSnapshot(
            AccRow("ware-a", ambiguous, GROUP_KEY),
            AccRow("ware-b", ambiguous, GROUP_KEY),
            AccRow("ware-first", first, GROUP_KEY),
            AccRow("ware-first", second, GROUP_KEY), // a second EAN for the same WARE_ID
        )
        ruleRepository.save(rule("r_acc", RuleType.substitution, RuleTrigger("acc_group", GROUP_KEY), "p_zen", 0))

        assertTrue(recommendByBarcode("s-ambiguous", listOf(ambiguous)).recommendations.isEmpty())
        val firstResult = recommend("s-concrete", listOf(CartItemDto(barcode = first, name = "Первый препарат")))
        val repeated = recommend("s-concrete", listOf(CartItemDto(barcode = first, name = "Первый препарат")))
        val secondResult = recommend("s-concrete", listOf(CartItemDto(barcode = second, name = "Второй препарат")))
        assertEquals(firstResult.recommendations.single().eventId, repeated.recommendations.single().eventId)
        assertTrue(firstResult.recommendations.single().eventId != secondResult.recommendations.single().eventId)
        assertTrue(firstResult.recommendations.single().triggerSku != secondResult.recommendations.single().triggerSku)
        assertEquals("Первый препарат", eventRepository.findById(firstResult.recommendations.single().eventId).get().triggerName)
        assertEquals("Второй препарат", eventRepository.findById(secondResult.recommendations.single().eventId).get().triggerName)
        val combinedCart = recommend(
            "s-current-scan",
            listOf(CartItemDto(barcode = first, name = "Первый препарат"), CartItemDto(barcode = second, name = "Второй препарат")),
            scannedBarcode = second,
        )
        assertEquals(second, combinedCart.recommendations.single().triggerBarcode)
        assertEquals("Второй препарат", combinedCart.recommendations.single().triggerName)
        mockMvc.perform(
            post("/api/posm/recommendations/${firstResult.recommendations.single().eventId}/outcome")
                .header("X-Posm-Key", POSM_KEY)
                .contentType(MediaType.APPLICATION_JSON).content("""{"outcome":"accepted"}"""),
        ).andExpect(status().isOk)
        mockMvc.perform(
            post("/api/posm/recommendations/${secondResult.recommendations.single().eventId}/outcome")
                .header("X-Posm-Key", POSM_KEY)
                .contentType(MediaType.APPLICATION_JSON).content("""{"outcome":"accepted"}"""),
        ).andExpect(status().isConflict)
        assertEquals(1, pendingBonusRepository.count())
        assertTrue(recommendByBarcode("s-concrete", listOf(second)).recommendations.isEmpty())
    }

    @Test
    fun `ACC classification reaches barcodes beyond first 200 cart lines`() {
        val lastBarcode = "4871234567999"
        publishAccSnapshot(AccRow("ware-last", lastBarcode, GROUP_KEY))
        ruleRepository.save(rule("r_acc_last", RuleType.substitution, RuleTrigger("acc_group", GROUP_KEY), "p_zen", 0))

        val filler = (0 until 200).map { index -> CartItemDto(barcode = "48${index.toString().padStart(11, '0')}") }
        val result = recommend("s-long-cart", filler + CartItemDto(barcode = lastBarcode, name = "Последняя позиция"))

        assertEquals(1, result.recommendations.size)
        assertEquals(lastBarcode, result.recommendations.single().triggerBarcode)
        assertEquals("Последняя позиция", result.recommendations.single().triggerName)
    }

    @Test
    fun `exact product outranks broad ACC offers under five-per-kind cap`() {
        publishAccSnapshot(AccRow("ware-bio", barBio, GROUP_KEY))
        (1..5).forEach { index ->
            val product = product("p_acc_$index", "ACC analog $index", 1000 + index, null)
            productRepository.save(product)
            ruleRepository.save(rule("r_acc_$index", RuleType.substitution, RuleTrigger("acc_group", GROUP_KEY), product.id, 900 - index))
        }

        val substitutions = recommendByBarcode("s-acc-cap", listOf(barBio)).recommendations
            .filter { it.kind == "substitution" }
        assertEquals(5, substitutions.size)
        assertEquals("p_zen", substitutions.first().recommendSku) // existing exact-product rule
        assertEquals(4, substitutions.drop(1).count { it.recommendSku.startsWith("p_acc_") })
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
    fun `замена и cross-sell приходят вместе для двух секций popup`() {
        // Корзина даёт ровно одну замену (p_bio→p_zen) и один cross-sell (p_food→p_cream).
        // На кассе это две секции «Замена» и «Допродажа» в одной карточке.
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

    @Test
    fun `disabled task bridge stays empty and cannot affect recommendations`() {
        mockMvc.perform(
            get("/api/posm/tasks")
                .header("X-Posm-Key", POSM_KEY)
                .header("X-Device-Id", "POS-02")
                .param("pharmacyId", "ph_t"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.task").isEmpty)
            .andExpect(jsonPath("$.available").value(true))

        val recommendation = recommend("s-merch-isolation", listOf(CartItemDto(barcode = barBio)))
        assertEquals(1, recommendation.recommendations.size)
        assertEquals("p_zen", recommendation.recommendations[0].recommendSku)

        mockMvc.perform(
            get("/api/posm/tasks")
                .param("pharmacyId", "ph_t"),
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `task acknowledgement validates identifiers before forwarding`() {
        mockMvc.perform(
            post("/api/posm/tasks/shown")
                .header("X-Posm-Key", POSM_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{
                      "dispatchId":"123e4567-e89b-42d3-a456-426614174000",
                      "pharmacyId":"ph_t",
                      "deviceId":"POS-02",
                      "deliveryToken":"test-opaque-delivery-token-00000001"
                    }""".trimIndent(),
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accepted").value(false))
            .andExpect(jsonPath("$.available").value(true))

        mockMvc.perform(
            post("/api/posm/tasks/shown")
                .header("X-Posm-Key", POSM_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{
                      "dispatchId":"123e4567-e89b-42d3-a456-426614174000",
                      "pharmacyId":"ph_t",
                      "deviceId":"POS-02",
                      "deliveryToken":"invalid token with spaces"
                    }""".trimIndent(),
                ),
        ).andExpect(status().isBadRequest)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun recommend(session: String, items: List<CartItemDto>, scannedBarcode: String? = null): RecommendResponse {
        val body = mockMvc.perform(
            post("/api/posm/recommend")
                .header("X-Posm-Key", POSM_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req(session, items).copy(scannedBarcode = scannedBarcode))),
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

    private data class AccRow(
        val wareId: String,
        val barcode: String,
        val groupKey: String? = null,
        val subgroupKey: String? = null,
        val mnnKey: String? = null,
    )

    private fun publishAccSnapshot(vararg rows: AccRow) {
        val snapshotId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO acc_catalog_snapshots(id,sha256,source_name,item_count,barcode_count) VALUES (?,?,?,?,?)",
            snapshotId, snapshotId.toString().replace("-", "").repeat(2).take(64), "test.xlsx",
            rows.map { it.wareId }.distinct().size, rows.size,
        )
        rows.forEach { row ->
            jdbc.update(
                """INSERT INTO acc_catalog_barcodes(snapshot_id,ware_id,barcode,group_key,group_label,
                   subgroup_key,subgroup_label,mnn_key,mnn_label) VALUES (?,?,?,?,?,?,?,?,?)""",
                snapshotId, row.wareId, row.barcode, row.groupKey,
                row.groupKey?.let { "Анальгетики" }, row.subgroupKey,
                row.subgroupKey?.let { "Нестероидные" }, row.mnnKey,
                row.mnnKey?.let { "Ибупрофен" },
            )
        }
        jdbc.update("UPDATE acc_catalog_state SET active_snapshot_id=? WHERE singleton=1", snapshotId)
    }

}
