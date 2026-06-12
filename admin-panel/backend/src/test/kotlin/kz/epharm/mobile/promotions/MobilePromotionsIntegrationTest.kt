package kz.epharm.mobile.promotions

import com.fasterxml.jackson.databind.ObjectMapper
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.domain.AdminUserStatus
import kz.epharm.auth.dto.LoginRequest
import kz.epharm.auth.dto.LoginResponse
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.promo.entity.PromoEntity
import kz.epharm.promo.entity.PromoStatus
import kz.epharm.promo.entity.PromoTier
import kz.epharm.promo.repository.PromoRepository
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDate

/**
 * Лента промо-товаров: публичный мобильный фид + админ-создание товарной акции.
 * Medusa в тест-профиле выключена → лента деградирует на снимок товара (productName).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Transactional
class MobilePromotionsIntegrationTest {

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
    @Autowired private lateinit var adminUserRepository: AdminUserRepository
    @Autowired private lateinit var passwordEncoder: PasswordEncoder

    private lateinit var bearer: String
    private val today: LocalDate = LocalDate.now()

    @BeforeEach
    fun seed() {
        promoRepository.deleteAll()
        adminUserRepository.deleteAll()
        adminUserRepository.save(
            AdminUserEntity(
                email = "hq@epharm.kz",
                passwordHash = passwordEncoder.encode("pw123456"),
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
    }

    private fun savePromo(
        id: String, status: PromoStatus, productId: String?,
        start: LocalDate?, end: LocalDate?, tiers: List<PromoTier> = emptyList(),
        name: String = "Лифта 10мг", brand: String = "Abdi Ibrahim",
    ) = promoRepository.save(
        PromoEntity(id = id, title = "Акция $id", brand = brand, productName = name).also {
            it.status = status; it.medusaProductId = productId
            it.dateStart = start; it.dateEnd = end; it.tiers = tiers
        },
    )

    @Test
    fun `публичная лента без токена — только активные товарные акции в окне дат`() {
        savePromo(
            "pr_ok", PromoStatus.active, "prod_lifta", today.minusDays(1), today.plusDays(30),
            tiers = listOf(PromoTier(1, 500, 0), PromoTier(10, 600, 900), PromoTier(20, 700, 1900)),
        )
        savePromo("pr_draft", PromoStatus.draft, "prod_x", today.minusDays(1), today.plusDays(30))
        savePromo("pr_noprod", PromoStatus.active, null, today.minusDays(1), today.plusDays(30))
        savePromo("pr_expired", PromoStatus.active, "prod_y", today.minusDays(30), today.minusDays(1))

        mockMvc.perform(get("/api/mobile/promotions")) // БЕЗ токена — публичный
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value("pr_ok"))
            .andExpect(jsonPath("$[0].productId").value("prod_lifta"))
            .andExpect(jsonPath("$[0].name").value("Лифта 10мг")) // снимок (Medusa off)
            .andExpect(jsonPath("$[0].tiers.length()").value(3))
            .andExpect(jsonPath("$[0].tiers[1].minQty").value(10))
            .andExpect(jsonPath("$[0].tiers[1].bonus").value(900))
    }

    @Test
    fun `админ создаёт товарную акцию с товаром, датами и порогами`() {
        val body = """
            {"title":"Лифта июнь","medusaProductId":"prod_lifta","productName":"Лифта 10мг",
             "brand":"Abdi Ibrahim","status":"active","dateStart":"2026-06-01","dateEnd":"2026-06-30",
             "tiers":[{"minQty":1,"price":500,"bonus":0},{"minQty":10,"price":600,"bonus":900}]}
        """.trimIndent()
        mockMvc.perform(
            post("/api/admin/promo").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(body),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.medusaProductId").value("prod_lifta"))
            .andExpect(jsonPath("$.dateStart").value("2026-06-01"))
            .andExpect(jsonPath("$.tiers.length()").value(2))
            .andExpect(jsonPath("$.tiers[1].bonus").value(900))
    }

    @Test
    fun `немонотонные пороги (по количеству) → 400`() {
        val body = """
            {"title":"Кривые пороги","medusaProductId":"prod_z",
             "tiers":[{"minQty":10,"price":600},{"minQty":5,"price":500}]}
        """.trimIndent()
        mockMvc.perform(
            post("/api/admin/promo").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `активная акция с товаром, но без ценовых порогов → 400`() {
        val body = """
            {"title":"Без порогов","status":"active","medusaProductId":"prod_a",
             "productName":"Товар","tiers":[]}
        """.trimIndent()
        mockMvc.perform(
            post("/api/admin/promo").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isBadRequest)
    }
}
