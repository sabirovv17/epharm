package kz.epharm.promo

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.persistence.EntityManager
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.domain.AdminUserStatus
import kz.epharm.auth.dto.LoginRequest
import kz.epharm.auth.dto.LoginResponse
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.catalog.entity.ProductEntity
import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.promo.entity.PromoEntity
import kz.epharm.promo.entity.PromoStatus
import kz.epharm.promo.entity.PromoTier
import kz.epharm.promo.repository.PromoRepository
import kz.epharm.rules.entity.RuleStatus
import kz.epharm.rules.entity.RuleTrigger
import kz.epharm.rules.entity.RuleType
import kz.epharm.rules.repository.RuleRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * T2: правила замены/кросс-селла генерятся из карточки кампании (PUT /promo/{id}/rules),
 * читаются обратно (GET), под выбранные товары витрины апсертятся локальные товары каталога.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Transactional
class PromoRulesIntegrationTest {

    companion object {
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
    @Autowired private lateinit var promoRepository: PromoRepository
    @Autowired private lateinit var ruleRepository: RuleRepository
    @Autowired private lateinit var productRepository: ProductRepository
    @Autowired private lateinit var adminUserRepository: AdminUserRepository
    @Autowired private lateinit var passwordEncoder: PasswordEncoder
    @Autowired private lateinit var entityManager: EntityManager

    private lateinit var bearer: String

    @BeforeEach
    fun seed() {
        ruleRepository.deleteAll()
        promoRepository.deleteAll()
        adminUserRepository.deleteAll()
        adminUserRepository.save(
            AdminUserEntity(
                email = "hq@epharm.kz", passwordHash = passwordEncoder.encode("pw123456"),
                name = "HQ", company = "Inkar",
            ).also { it.role = AdminRole.HQ_HEAD; it.status = AdminUserStatus.ACTIVE },
        )
        val login = objectMapper.readValue(
            mockMvc.perform(
                post("/api/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(LoginRequest("hq@epharm.kz", "pw123456"))),
            ).andReturn().response.contentAsString,
            LoginResponse::class.java,
        )
        bearer = "Bearer " + login.tokens.accessToken

        // Кампания продвигает товар prod_promoted, бонус фармацевту = 300.
        promoRepository.save(
            PromoEntity(
                id = "pr_camp", title = "Кампания Аквамарис",
                medusaProductId = "prod_promoted", productName = "Аквамарис Норм",
                barcode = "4600000000001", ipartId = "90001",
            ).also {
                it.status = PromoStatus.active
                it.tiers = listOf(PromoTier(minQty = 1, price = 0, bonus = 300))
            },
        )
    }

    @Test
    fun `PUT генерирует правила замены и кросс-селла из кампании`() {
        val body = """
            {"replacements":[{"medusaProductId":"prod_comp1","name":"Аквалор Норм","price":1200,
               "barcode":"4603423004936","ipartId":"80309"}],
             "crossSells":[{"medusaProductId":"prod_cross1","name":"Платочки","price":500,
               "barcode":"4604249789012","ipartId":"80444"}],
             "script":"Предложите Аквамарис","advantages":["Дешевле","Тот же эффект"],
             "partnerLabel":"ПАРТНЁР EPHARM"}
        """.trimIndent()

        mockMvc.perform(
            put("/api/admin/promo/pr_camp/rules").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(body),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.ruleCount").value(2))
            .andExpect(jsonPath("$.activeCount").value(2))

        val rules = ruleRepository.findAllByPromoIdOrderByUpdatedAtDesc("pr_camp")
        assertThat(rules).hasSize(2)
        val sub = rules.first { it.type == RuleType.substitution }
        assertThat(sub.recommend).isEqualTo("prod_promoted")     // заменяем НА продвигаемый
        assertThat(sub.trigger.value).isEqualTo("prod_comp1")    // триггер — заменяемый товар
        assertThat(sub.bonus).isEqualTo(300)                     // бонус кампании
        val cross = rules.first { it.type == RuleType.crosssell }
        assertThat(cross.trigger.value).isEqualTo("prod_cross1")   // триггер — товар уже в чеке
        assertThat(cross.recommend).isEqualTo("prod_promoted")     // допродаём товар кампании

        // Локальные товары апсертнуты (id = medusaProductId).
        assertThat(productRepository.existsById("prod_promoted")).isTrue()
        assertThat(productRepository.existsById("prod_comp1")).isTrue()
        assertThat(productRepository.existsById("prod_cross1")).isTrue()
        assertThat(productRepository.findById("prod_promoted").get().ipartId).isEqualTo("90001")
        assertThat(productRepository.findById("prod_comp1").get().ipartId).isEqualTo("80309")
        assertThat(productRepository.findById("prod_cross1").get().ipartId).isEqualTo("80444")
    }

    @Test
    fun `GET возвращает сохранённую конфигурацию правил (общий скрипт как дефолт пары)`() {
        // Общий script задан, у пары своего нет → попадает в правило как дефолт,
        // и в GET виден per-pair (config.script теперь пустой — текст ушёл в пары).
        val body = """
            {"replacements":[{"medusaProductId":"prod_comp1","name":"Аквалор Норм"}],
             "crossSells":[],"script":"Скрипт","advantages":["Плюс"]}
        """.trimIndent()
        mockMvc.perform(
            put("/api/admin/promo/pr_camp/rules").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isOk)

        mockMvc.perform(get("/api/admin/promo/pr_camp/rules").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.config.replacements.length()").value(1))
            .andExpect(jsonPath("$.config.replacements[0].medusaProductId").value("prod_comp1"))
            .andExpect(jsonPath("$.config.replacements[0].script").value("Скрипт"))
            .andExpect(jsonPath("$.config.script").value(""))
            .andExpect(jsonPath("$.ruleCount").value(1))
    }

    @Test
    fun `product_any round-trips through campaign editor without losing trigger products`() {
        val initial = """
            {"replacements":[{"medusaProductId":"prod_comp1","name":"Жидкий уголь детский"}],
             "crossSells":[],"script":"Предложите замену"}
        """.trimIndent()
        mockMvc.perform(
            put("/api/admin/promo/pr_camp/rules").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(initial),
        ).andExpect(status().isOk)

        productRepository.save(
            ProductEntity(id = "prod_comp2").also {
                it.name = "Жидкий уголь взрослый"
                it.barcode = "4601164003157"
            },
        )
        val stored = ruleRepository.findAllByPromoIdOrderByUpdatedAtDesc("pr_camp").single()
        stored.trigger = RuleTrigger(kind = "product_any", value = listOf("prod_comp1", "prod_comp2"))
        ruleRepository.saveAndFlush(stored)
        entityManager.clear()

        val response = mockMvc.perform(
            get("/api/admin/promo/pr_camp/rules").header("Authorization", bearer),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.ruleCount").value(1))
            .andExpect(jsonPath("$.config.replacements.length()").value(2))
            .andExpect(
                jsonPath("$.config.replacements[*].medusaProductId")
                    .value(org.hamcrest.Matchers.containsInAnyOrder("prod_comp1", "prod_comp2")),
            )
            .andReturn().response.contentAsString

        val config = objectMapper.readTree(response).get("config").toString()
        mockMvc.perform(
            put("/api/admin/promo/pr_camp/rules").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(config),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.config.replacements.length()").value(2))

        val rewritten = ruleRepository.findAllByPromoIdOrderByUpdatedAtDesc("pr_camp")
        assertThat(rewritten).hasSize(2)
        assertThat(rewritten.map { it.trigger.kind }).containsOnly("product")
        assertThat(rewritten.map { it.trigger.value })
            .containsExactlyInAnyOrder("prod_comp1", "prod_comp2")
    }

    @Test
    fun `одна пара сохраняет и возвращает до пяти вариантов замены`() {
        val body = """
            {"replacements":[{
               "medusaProductId":"prod_comp1","name":"Аквалор Норм","script":"Предложите подходящий аналог",
               "additionalRecommendations":[
                 {"medusaProductId":"prod_alt1","name":"Аналог 1","brand":"Brand 1","price":1100},
                 {"medusaProductId":"prod_alt2","name":"Аналог 2","brand":"Brand 2","price":1200},
                 {"medusaProductId":"prod_alt3","name":"Аналог 3","brand":"Brand 3","price":1300},
                 {"medusaProductId":"prod_alt4","name":"Аналог 4","brand":"Brand 4","price":1400}
               ]}],
             "crossSells":[]}
        """.trimIndent()

        mockMvc.perform(
            put("/api/admin/promo/pr_camp/rules").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(body),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.ruleCount").value(5))
            .andExpect(jsonPath("$.activeCount").value(5))
            .andExpect(jsonPath("$.config.replacements.length()").value(1))
            .andExpect(jsonPath("$.config.replacements[0].additionalRecommendations.length()").value(4))
            .andExpect(
                jsonPath("$.config.replacements[0].additionalRecommendations[3].medusaProductId")
                    .value("prod_alt4"),
            )

        val rules = ruleRepository.findAllByPromoIdOrderByUpdatedAtDesc("pr_camp")
        assertThat(rules).hasSize(5)
        assertThat(rules.map { it.recommend })
            .containsExactlyInAnyOrder("prod_promoted", "prod_alt1", "prod_alt2", "prod_alt3", "prod_alt4")
        assertThat(rules.map { it.trigger.value }).containsOnly("prod_comp1")
        assertThat(rules).allSatisfy { assertThat(it.script).isEqualTo("Предложите подходящий аналог") }
        assertThat(rules).allSatisfy { assertThat(it.bonus).isEqualTo(300) }
    }

    @Test
    fun `бонус каждого предлагаемого препарата сохраняется независимо и round-trip проходит без потери`() {
        val body = """
            {"replacements":[{"medusaProductId":"prod_comp1","name":"Аквалор Норм","bonus":400,
               "additionalRecommendations":[
                 {"medusaProductId":"prod_alt1","name":"Аналог без бонуса","bonus":0},
                 {"medusaProductId":"prod_alt2","name":"Аналог с бонусом","bonus":150}
               ]}],
             "crossSells":[{"medusaProductId":"prod_cross1","name":"Платочки","bonus":0,
               "additionalRecommendations":[
                 {"medusaProductId":"prod_alt3","name":"Другой допродажный товар","bonus":250}
               ]}]}
        """.trimIndent()

        val saved = mockMvc.perform(
            put("/api/admin/promo/pr_camp/rules").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.ruleCount").value(5))
            .andExpect(jsonPath("$.config.replacements[0].bonus").value(400))
            .andExpect(jsonPath("$.config.replacements[0].additionalRecommendations[0].bonus").value(0))
            .andExpect(jsonPath("$.config.replacements[0].additionalRecommendations[1].bonus").value(150))
            .andExpect(jsonPath("$.config.crossSells[0].bonus").value(0))
            .andExpect(jsonPath("$.config.crossSells[0].additionalRecommendations[0].bonus").value(250))
            .andReturn().response.contentAsString

        val rules = ruleRepository.findAllByPromoIdOrderByUpdatedAtDesc("pr_camp")
        assertThat(rules.associate { (it.type to it.recommend) to it.bonus }).containsAllEntriesOf(
            mapOf(
                (RuleType.substitution to "prod_promoted") to 400,
                (RuleType.substitution to "prod_alt1") to 0,
                (RuleType.substitution to "prod_alt2") to 150,
                (RuleType.crosssell to "prod_promoted") to 0,
                (RuleType.crosssell to "prod_alt3") to 250,
            ),
        )

        // Редактор отправляет обратно прочитанную конфигурацию: бонусы не должны
        // возвращаться к общему значению кампании (300 ₸) после повторного сохранения.
        val returnedConfig = objectMapper.readTree(saved).get("config").toString()
        mockMvc.perform(
            put("/api/admin/promo/pr_camp/rules").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(returnedConfig),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.config.replacements[0].additionalRecommendations[0].bonus").value(0))
            .andExpect(jsonPath("$.config.crossSells[0].additionalRecommendations[0].bonus").value(250))
    }

    @Test
    fun `отрицательный бонус у дополнительного препарата отклоняется до перезаписи правил`() {
        val body = """
            {"replacements":[{"medusaProductId":"prod_comp1","name":"Аквалор Норм",
               "additionalRecommendations":[
                 {"medusaProductId":"prod_alt1","name":"Аналог","bonus":-1}
               ]}],"crossSells":[]}
        """.trimIndent()

        mockMvc.perform(
            put("/api/admin/promo/pr_camp/rules").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isBadRequest)

        assertThat(ruleRepository.findAllByPromoIdOrderByUpdatedAtDesc("pr_camp")).isEmpty()
    }

    @Test
    fun `более пяти вариантов в одной паре отклоняются валидацией`() {
        val extras = (1..5).joinToString(",") { index ->
            """{"medusaProductId":"prod_alt$index","name":"Аналог $index"}"""
        }
        val body = """
            {"replacements":[{"medusaProductId":"prod_comp1","name":"Аквалор",
              "additionalRecommendations":[$extras]}],"crossSells":[]}
        """.trimIndent()

        mockMvc.perform(
            put("/api/admin/promo/pr_camp/rules").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isBadRequest)

        assertThat(ruleRepository.findAllByPromoIdOrderByUpdatedAtDesc("pr_camp")).isEmpty()
    }

    @Test
    fun `per-pair скрипт сохраняется в правило и возвращается по паре`() {
        // У каждой пары — СВОЙ скрипт. Должен попасть в rules.script именно её правила
        // (это поле уходит на кассу) и вернуться в GET по соответствующей паре.
        val body = """
            {"replacements":[{"medusaProductId":"prod_comp1","name":"Аквалор Норм","script":"Замени на наш — мягче"}],
             "crossSells":[{"medusaProductId":"prod_cross1","name":"Платочки","script":"Допродай платочки — пригодятся"}],
             "script":""}
        """.trimIndent()
        mockMvc.perform(
            put("/api/admin/promo/pr_camp/rules").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isOk)

        // В БД скрипт записан в нужное правило.
        val rules = ruleRepository.findAllByPromoIdOrderByUpdatedAtDesc("pr_camp")
        val sub = rules.first { it.type == RuleType.substitution }
        val cross = rules.first { it.type == RuleType.crosssell }
        assertThat(sub.script).isEqualTo("Замени на наш — мягче")
        assertThat(cross.script).isEqualTo("Допродай платочки — пригодятся")

        // GET отдаёт per-pair скрипты обратно.
        mockMvc.perform(get("/api/admin/promo/pr_camp/rules").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.config.replacements[0].script").value("Замени на наш — мягче"))
            .andExpect(jsonPath("$.config.crossSells[0].script").value("Допродай платочки — пригодятся"))
    }

    @Test
    fun `per-pair поля карточки сохраняются по паре, а цель — на уровне кампании`() {
        // Преимущества/партнёр/сравнение — у каждой пары свои (rules.advantages/card).
        // Цель (goalLabel/goalTarget/goalBonus) — одна на всю кампанию (на уровне config),
        // применяется ко ВСЕМ правилам кампании.
        val body = """
            {"replacements":[{"medusaProductId":"prod_comp1","name":"Аквалор Норм",
              "advantages":["Дешевле","Мягче"],
              "partnerLabel":"ПАРТНЁР",
              "comparison":[{"label":"Объём","triggerValue":"15мл","recommendValue":"30мл","recommendHighlight":true}]}],
             "crossSells":[],
             "goalLabel":"замен","goalTarget":10,"goalBonus":500}
        """.trimIndent()
        mockMvc.perform(
            put("/api/admin/promo/pr_camp/rules").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isOk)

        // В БД: per-pair поля в правиле пары; цель кампании наложена на карточку правила.
        val rule = ruleRepository.findAllByPromoIdOrderByUpdatedAtDesc("pr_camp")
            .first { it.type == RuleType.substitution }
        assertThat(rule.advantages).containsExactly("Дешевле", "Мягче")
        assertThat(rule.card?.partnerLabel).isEqualTo("ПАРТНЁР")
        assertThat(rule.card?.comparison).hasSize(1)
        assertThat(rule.card?.goalTarget).isEqualTo(10)
        assertThat(rule.card?.goalBonus).isEqualTo(500)

        // GET: per-pair поля у пары, а цель — на уровне config (кампании).
        mockMvc.perform(get("/api/admin/promo/pr_camp/rules").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.config.replacements[0].advantages[0]").value("Дешевле"))
            .andExpect(jsonPath("$.config.replacements[0].partnerLabel").value("ПАРТНЁР"))
            .andExpect(jsonPath("$.config.replacements[0].comparison[0].label").value("Объём"))
            .andExpect(jsonPath("$.config.replacements[0].comparison[0].recommendHighlight").value(true))
            .andExpect(jsonPath("$.config.replacements[0].active").value(true))
            .andExpect(jsonPath("$.config.goalTarget").value(10))
            .andExpect(jsonPath("$.config.goalBonus").value(500))
    }

    @Test
    fun `пара со статусом Черновик не активна даже в активной кампании`() {
        // Кампания pr_camp активна (см. setUp). Пара active=false → её правило draft.
        val body = """
            {"replacements":[
               {"medusaProductId":"prod_comp1","name":"Активная","active":true},
               {"medusaProductId":"prod_comp2","name":"Черновик","active":false}],
             "crossSells":[]}
        """.trimIndent()
        mockMvc.perform(
            put("/api/admin/promo/pr_camp/rules").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isOk)

        val rules = ruleRepository.findAllByPromoIdOrderByUpdatedAtDesc("pr_camp")
        val draft = rules.first { (it.trigger.value as? String) == "prod_comp2" }
        val active = rules.first { (it.trigger.value as? String) == "prod_comp1" }
        assertThat(draft.status).isEqualTo(RuleStatus.draft)
        assertThat(active.status).isEqualTo(RuleStatus.active)

        // GET round-trip'ит намерение пары (active true/false) обратно в редактор.
        mockMvc.perform(get("/api/admin/promo/pr_camp/rules").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.config.replacements[?(@.medusaProductId=='prod_comp2')].active").value(false))
    }

    @Test
    fun `активация кампании активирует правила замен и кросс-селла`() {
        promoRepository.saveAndFlush(
            promoRepository.findById("pr_camp").get().also { it.status = PromoStatus.draft },
        )
        val body = """
            {"replacements":[
               {"medusaProductId":"prod_comp1","name":"Активная замена","active":true},
               {"medusaProductId":"prod_comp2","name":"Черновик замены","active":false}],
             "crossSells":[{"medusaProductId":"prod_cross1","name":"Активный кросс-селл","active":true}]}
        """.trimIndent()
        mockMvc.perform(
            put("/api/admin/promo/pr_camp/rules").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(body),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.ruleCount").value(3))
            .andExpect(jsonPath("$.activeCount").value(0))

        assertThat(ruleRepository.findAllByPromoIdOrderByUpdatedAtDesc("pr_camp"))
            .allSatisfy { assertThat(it.status).isEqualTo(RuleStatus.draft) }

        mockMvc.perform(
            patch("/api/admin/promo/pr_camp").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content("""{"status":"active"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("active"))

        entityManager.clear()
        val rules = ruleRepository.findAllByPromoIdOrderByUpdatedAtDesc("pr_camp")
        assertThat(rules).hasSize(3)
        assertThat(rules.filter { it.status == RuleStatus.active }).hasSize(2)
        assertThat(rules.first { (it.trigger.value as? String) == "prod_comp2" }.status)
            .isEqualTo(RuleStatus.draft)

        mockMvc.perform(get("/api/admin/promo/pr_camp/rules").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.activeCount").value(2))
            .andExpect(jsonPath("$.config.replacements[?(@.medusaProductId=='prod_comp2')].active").value(false))
    }

    @Test
    fun `цель кампании сохраняется даже без пар (на promo, не в правилах)`() {
        // Регресс на критическую находку ревью: цель жила только в rules.card и терялась,
        // если у кампании нет ни одной пары. Теперь источник истины — promos.*.
        val body = """
            {"replacements":[],"crossSells":[],
             "goalLabel":"замен в июне","goalTarget":7,"goalBonus":300}
        """.trimIndent()
        mockMvc.perform(
            put("/api/admin/promo/pr_camp/rules").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isOk)

        // Правил нет, но цель не потерялась — отдаётся на GET из promos.
        mockMvc.perform(get("/api/admin/promo/pr_camp/rules").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.config.replacements.length()").value(0))
            .andExpect(jsonPath("$.config.goalLabel").value("замен в июне"))
            .andExpect(jsonPath("$.config.goalTarget").value(7))
            .andExpect(jsonPath("$.config.goalBonus").value(300))
    }

    @Test
    fun `PUT возвращает точный путь вложенного невалидного поля`() {
        val body = """
            {"replacements":[{"medusaProductId":"prod_comp1","name":"Аквалор Норм",
               "barcode":"123456789012345678901234567890123"}],
             "crossSells":[]}
        """.trimIndent()

        mockMvc.perform(
            put("/api/admin/promo/pr_camp/rules").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(body),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$['fields']['replacements[0].barcode']").exists())

        // Validation happens before replace semantics: existing rules are untouched.
        assertThat(ruleRepository.findAllByPromoIdOrderByUpdatedAtDesc("pr_camp")).isEmpty()
    }

    @Test
    fun `PUT для кампании без товара → 400`() {
        promoRepository.save(
            PromoEntity(id = "pr_noprod", title = "Без товара").also { it.status = PromoStatus.draft },
        )
        val body = """{"replacements":[{"medusaProductId":"prod_x","name":"X"}],"crossSells":[]}"""
        mockMvc.perform(
            put("/api/admin/promo/pr_noprod/rules").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isBadRequest)
    }
}
