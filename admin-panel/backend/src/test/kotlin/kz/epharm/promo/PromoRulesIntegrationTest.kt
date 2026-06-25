package kz.epharm.promo

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.persistence.EntityManager
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.domain.AdminUserStatus
import kz.epharm.auth.dto.LoginRequest
import kz.epharm.auth.dto.LoginResponse
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.promo.entity.PromoEntity
import kz.epharm.promo.entity.PromoStatus
import kz.epharm.promo.entity.PromoTier
import kz.epharm.promo.repository.PromoRepository
import kz.epharm.rules.entity.RuleStatus
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
