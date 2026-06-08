package kz.epharm.rules

import com.fasterxml.jackson.databind.ObjectMapper
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.domain.AdminUserStatus
import kz.epharm.auth.dto.LoginRequest
import kz.epharm.auth.dto.LoginResponse
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.catalog.entity.ProductEntity
import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.rules.entity.RuleEntity
import kz.epharm.rules.entity.RuleStatus
import kz.epharm.rules.entity.RuleTrigger
import kz.epharm.rules.entity.RuleType
import kz.epharm.rules.repository.RuleRepository
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * REPRODUCTION-тесты latent-багов backend Rules API. Изначально все методы
 * с префиксом `repro_` должны падать — подтверждение что баг настоящий, а не выдуманный.
 *
 * Bugs:
 *   A) TriggerDto.value: Any не валидирует соответствие kind:
 *      kind=product → value должно быть String, не List
 *      kind=product_any → value должно быть List, не String
 *      kind=mnn → value должно быть String
 *   B) PATCH /rules/{id} с status=archived обходит dedicated /archive endpoint
 *      (контракт-баг — frontend ожидает что архивация идёт через /archive
 *      для отдельного audit-event'а).
 *   C) Правило с recommend == trigger.value создаётся без ошибки
 *      (рекомендует самого себя; бессмысленно для substitution).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Transactional
class RuleValidationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("epharm_test")
            .withUsername("epharm")
            .withPassword("epharm_test")
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
    @Autowired private lateinit var adminUserRepository: AdminUserRepository
    @Autowired private lateinit var passwordEncoder: PasswordEncoder

    private lateinit var bearer: String

    @BeforeEach
    fun seed() {
        ruleRepository.deleteAll()
        productRepository.deleteAll()
        adminUserRepository.deleteAll()

        productRepository.saveAll(
            listOf(
                ProductEntity(id = "p_aql", name = "Аквалор", brand = "Aurena Labs",
                    vendor = "Aurena", mnn = "Морская вода", price = 1620),
                ProductEntity(id = "p_aqm", name = "Аквамарис", brand = "Jadran-Galenski",
                    vendor = "Jadran", mnn = "Морская вода", price = 1480),
            ),
        )

        ruleRepository.save(
            RuleEntity(
                id = "r_seed",
                trigger = RuleTrigger(kind = "product", value = "p_aql"),
                recommend = "p_aqm",
                bonus = 520,
                createdBy = "seed",
            ).also { it.type = RuleType.substitution; it.status = RuleStatus.active },
        )

        adminUserRepository.save(
            AdminUserEntity(
                email = "damir@jadran.com",
                passwordHash = passwordEncoder.encode("damir2026"),
                name = "Дамир", company = "Jadran",
            ).also { it.role = AdminRole.BRAND_MANAGER; it.status = AdminUserStatus.ACTIVE },
        )
        bearer = "Bearer " + login().tokens.accessToken
    }

    // ── BUG A: trigger shape validation ────────────────────────────────────

    @Test
    fun `repro_A1 — POST trigger kind=product with array value rejected as 400`() {
        // Нарушение контракта: для kind=product value должно быть строкой,
        // но мы шлём массив. Сейчас backend принимает без ошибки.
        val payload = """
            {
              "type":"substitution",
              "trigger":{"kind":"product","value":["p_aql","p_aqm"]},
              "recommend":"p_aqm",
              "bonus":100
            }
        """.trimIndent()
        mockMvc.perform(
            post("/api/admin/rules")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
    }

    @Test
    fun `repro_A2 — POST trigger kind=product_any with string value rejected as 400`() {
        // kind=product_any требует массив, не строку.
        val payload = """
            {
              "type":"substitution",
              "trigger":{"kind":"product_any","value":"p_aql"},
              "recommend":"p_aqm",
              "bonus":100
            }
        """.trimIndent()
        mockMvc.perform(
            post("/api/admin/rules")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
    }

    @Test
    fun `repro_A3 — POST trigger kind=mnn with array value rejected as 400`() {
        // kind=mnn ожидает строку (название МНН).
        val payload = """
            {
              "type":"substitution",
              "trigger":{"kind":"mnn","value":[]},
              "recommend":"p_aqm",
              "bonus":100
            }
        """.trimIndent()
        mockMvc.perform(
            post("/api/admin/rules")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
    }

    @Test
    fun `repro_A4 — POST trigger kind=product with referenced productId — accepted (sanity)`() {
        // Контрольный тест: правильная форма должна работать после фикса.
        val payload = """
            {
              "type":"substitution",
              "trigger":{"kind":"product","value":"p_aql"},
              "recommend":"p_aqm",
              "bonus":100
            }
        """.trimIndent()
        mockMvc.perform(
            post("/api/admin/rules")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload),
        ).andExpect(status().isOk)
    }

    // ── BUG B: PATCH bypass /archive endpoint ─────────────────────────────

    @Test
    fun `repro_B — PATCH with status=archived rejected (must use dedicated archive endpoint)`() {
        // Frontend полагается на `/archive` endpoint для отдельного audit-event'а
        // и toast'а. PATCH сейчас допускает status=archived → silent state change.
        val payload = """{"status":"archived"}"""
        mockMvc.perform(
            patch("/api/admin/rules/r_seed")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
    }

    @Test
    fun `repro_B_sanity — PATCH с status=paused (валидный переход) принят`() {
        // Контрольный: paused/active можно ставить через PATCH.
        mockMvc.perform(
            patch("/api/admin/rules/r_seed")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"status":"paused"}"""),
        ).andExpect(status().isOk)
    }

    // ── BUG C: self-reference recommend == trigger.value ──────────────────

    @Test
    fun `repro_C1 — POST правило где recommend == trigger product value rejected`() {
        // Бессмысленное правило «замени p_aql на p_aql».
        val payload = """
            {
              "type":"substitution",
              "trigger":{"kind":"product","value":"p_aql"},
              "recommend":"p_aql",
              "bonus":100
            }
        """.trimIndent()
        mockMvc.perform(
            post("/api/admin/rules")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
    }

    @Test
    fun `repro_C2 — POST product_any содержащий recommend rejected`() {
        // p_aqm в trigger-списке + p_aqm в recommend — тоже self-reference.
        val payload = """
            {
              "type":"crosssell",
              "trigger":{"kind":"product_any","value":["p_aql","p_aqm"]},
              "recommend":"p_aqm",
              "bonus":100
            }
        """.trimIndent()
        mockMvc.perform(
            post("/api/admin/rules")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
    }

    private fun login(): LoginResponse {
        val req = LoginRequest(email = "damir@jadran.com", password = "damir2026")
        val result = mockMvc.perform(
            post("/api/admin/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)),
        ).andExpect(status().isOk).andReturn()
        return objectMapper.readValue(result.response.contentAsString, LoginResponse::class.java)
    }
}
