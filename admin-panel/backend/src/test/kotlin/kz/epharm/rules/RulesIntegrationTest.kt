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
import kz.epharm.rules.dto.CreateRuleRequest
import kz.epharm.rules.dto.TriggerDto
import kz.epharm.rules.dto.UpdateRuleRequest
import kz.epharm.rules.entity.RuleEntity
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
class RulesIntegrationTest {

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
                ProductEntity(id = "p_aqm_norm_s", name = "Аквамарис Норм спрей 30мл",
                    brand = "Jadran-Galenski", vendor = "Jadran", mnn = "Морская вода", price = 1480),
                ProductEntity(id = "p_aql_norm_s", name = "Аквалор Норм спрей 50мл",
                    brand = "Aurena Labs", vendor = "Aurena", mnn = "Морская вода", price = 1620),
                ProductEntity(id = "p_pinos", name = "Пиносол спрей 10мл",
                    brand = "Jadran-Galenski", vendor = "Jadran", mnn = "Эфирные масла", price = 1820),
            ),
        )

        ruleRepository.save(
            RuleEntity(
                id = "r_seed_1",
                trigger = RuleTrigger(kind = "product", value = "p_aql_norm_s"),
                recommend = "p_aqm_norm_s",
                bonus = 520, script = "seed script",
                advantages = listOf("a1", "a2"),
                createdBy = "seed",
            ).also { it.type = RuleType.substitution; it.status = RuleStatus.active },
        )
        ruleRepository.save(
            RuleEntity(
                id = "r_seed_2",
                trigger = RuleTrigger(kind = "mnn", value = "Ксилометазолин"),
                recommend = "p_pinos",
                bonus = 320,
                createdBy = "seed",
            ).also { it.type = RuleType.crosssell; it.status = RuleStatus.active },
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

    @Test
    fun `GET rules without filter returns all`() {
        mockMvc.perform(get("/api/admin/rules").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
    }

    @Test
    fun `GET rules filter by type returns only substitution`() {
        mockMvc.perform(get("/api/admin/rules?type=substitution").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value("r_seed_1"))
    }

    @Test
    fun `GET rules filter by status active`() {
        mockMvc.perform(get("/api/admin/rules?status=active").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
    }

    @Test
    fun `GET rule by id returns trigger jsonb intact`() {
        mockMvc.perform(get("/api/admin/rules/r_seed_1").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value("r_seed_1"))
            .andExpect(jsonPath("$.trigger.kind").value("product"))
            .andExpect(jsonPath("$.trigger.value").value("p_aql_norm_s"))
            .andExpect(jsonPath("$.advantages.length()").value(2))
    }

    @Test
    fun `GET unknown rule returns 404`() {
        mockMvc.perform(get("/api/admin/rules/r_nope").header("Authorization", bearer))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
    }

    @Test
    fun `POST create rule with valid body persists and returns 200`() {
        val req = CreateRuleRequest(
            type = RuleType.substitution,
            status = RuleStatus.active,
            trigger = TriggerDto(kind = "product", value = "p_aql_norm_s"),
            recommend = "p_aqm_norm_s",
            bonus = 420,
            script = "тестовый скрипт",
            advantages = listOf("плюс 1", "плюс 2"),
        )
        mockMvc.perform(
            post("/api/admin/rules")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").exists())
            .andExpect(jsonPath("$.type").value("substitution"))
            .andExpect(jsonPath("$.status").value("active"))
            .andExpect(jsonPath("$.trigger.kind").value("product"))
            .andExpect(jsonPath("$.recommend").value("p_aqm_norm_s"))
            .andExpect(jsonPath("$.bonus").value(420))
            .andExpect(jsonPath("$.advantages.length()").value(2))

        assertThat(ruleRepository.count()).isEqualTo(3)
    }

    @Test
    fun `POST create rule with unknown recommend returns 400`() {
        val req = CreateRuleRequest(
            type = RuleType.substitution,
            trigger = TriggerDto(kind = "product", value = "p_aql_norm_s"),
            recommend = "p_nonexistent",
        )
        mockMvc.perform(
            post("/api/admin/rules")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
    }

    @Test
    fun `POST create rule with bad trigger kind returns 400`() {
        val payload = """
            {
              "type":"substitution",
              "trigger":{"kind":"random","value":"x"},
              "recommend":"p_aqm_norm_s"
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
    fun `PATCH rule updates status and bonus`() {
        val req = UpdateRuleRequest(status = RuleStatus.paused, bonus = 999)
        mockMvc.perform(
            patch("/api/admin/rules/r_seed_1")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("paused"))
            .andExpect(jsonPath("$.bonus").value(999))
    }

    @Test
    fun `PATCH archived rule returns 409`() {
        val arch = ruleRepository.findById("r_seed_1").get().apply { status = RuleStatus.archived }
        ruleRepository.save(arch)

        mockMvc.perform(
            patch("/api/admin/rules/r_seed_1")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(UpdateRuleRequest(bonus = 1))),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("CONFLICT"))
    }

    @Test
    fun `POST archive flips status`() {
        mockMvc.perform(
            post("/api/admin/rules/r_seed_1/archive").header("Authorization", bearer),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("archived"))

        assertThat(ruleRepository.findById("r_seed_1").get().status).isEqualTo(RuleStatus.archived)
    }

    @Test
    fun `POST duplicate creates draft copy with new id`() {
        mockMvc.perform(
            post("/api/admin/rules/r_seed_1/duplicate").header("Authorization", bearer),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.not("r_seed_1")))
            .andExpect(jsonPath("$.status").value("draft"))
            .andExpect(jsonPath("$.recommend").value("p_aqm_norm_s"))
            .andExpect(jsonPath("$.advantages.length()").value(2))

        assertThat(ruleRepository.count()).isEqualTo(3)
    }

    @Test
    fun `GET rules without Bearer returns 401`() {
        mockMvc.perform(get("/api/admin/rules"))
            .andExpect(status().isUnauthorized)
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
