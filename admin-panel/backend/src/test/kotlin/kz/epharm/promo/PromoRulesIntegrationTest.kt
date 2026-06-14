package kz.epharm.promo

import com.fasterxml.jackson.databind.ObjectMapper
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
            ).also {
                it.status = PromoStatus.active
                it.tiers = listOf(PromoTier(minQty = 1, price = 0, bonus = 300))
            },
        )
    }

    @Test
    fun `PUT генерирует правила замены и кросс-селла из кампании`() {
        val body = """
            {"replacements":[{"medusaProductId":"prod_comp1","name":"Аквалор Норм","price":1200}],
             "crossSells":[{"medusaProductId":"prod_cross1","name":"Платочки","price":500}],
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
        assertThat(cross.trigger.value).isEqualTo("prod_promoted") // триггер — продвигаемый
        assertThat(cross.recommend).isEqualTo("prod_cross1")       // допродаём компаньон

        // Локальные товары апсертнуты (id = medusaProductId).
        assertThat(productRepository.existsById("prod_promoted")).isTrue()
        assertThat(productRepository.existsById("prod_comp1")).isTrue()
        assertThat(productRepository.existsById("prod_cross1")).isTrue()
    }

    @Test
    fun `GET возвращает сохранённую конфигурацию правил`() {
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
            .andExpect(jsonPath("$.config.script").value("Скрипт"))
            .andExpect(jsonPath("$.ruleCount").value(1))
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
