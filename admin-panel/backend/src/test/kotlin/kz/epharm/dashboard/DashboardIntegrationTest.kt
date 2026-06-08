package kz.epharm.dashboard

import com.fasterxml.jackson.databind.ObjectMapper
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.domain.AdminUserStatus
import kz.epharm.auth.dto.LoginRequest
import kz.epharm.auth.dto.LoginResponse
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.catalog.entity.ProductEntity
import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.finance.entity.PayoutBatchEntity
import kz.epharm.finance.entity.PayoutBatchStatus
import kz.epharm.finance.repository.PayoutBatchRepository
import kz.epharm.pharmacies.entity.ChainEntity
import kz.epharm.pharmacies.entity.PharmacyEntity
import kz.epharm.pharmacies.entity.PharmacyGroup
import kz.epharm.pharmacies.repository.ChainRepository
import kz.epharm.pharmacies.repository.PharmacyRepository
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Transactional
class DashboardIntegrationTest {

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
    @Autowired private lateinit var pharmacyRepository: PharmacyRepository
    @Autowired private lateinit var chainRepository: ChainRepository
    @Autowired private lateinit var batchRepository: PayoutBatchRepository
    @Autowired private lateinit var adminUserRepository: AdminUserRepository
    @Autowired private lateinit var passwordEncoder: PasswordEncoder

    private lateinit var bearer: String

    @BeforeEach
    fun seed() {
        ruleRepository.deleteAll()
        productRepository.deleteAll()
        pharmacyRepository.deleteAll()
        chainRepository.deleteAll()
        batchRepository.deleteAll()
        adminUserRepository.deleteAll()

        productRepository.save(ProductEntity(id = "p_aquamaris", name = "Аквамарис", brand = "Jadran", vendor = "Jadran", mnn = "Sea water", price = 1800))
        productRepository.save(ProductEntity(id = "p_other", name = "Линекс", brand = "Sandoz", vendor = "Sandoz", mnn = "Probiotic", price = 2400))

        // 2 живых правила + 1 архивное (должно исключаться из агрегаций)
        ruleRepository.save(
            RuleEntity(id = "r_active", recommend = "p_aquamaris", impressions = 1000, accepts = 400,
                convRate = BigDecimal("40.00"), revenue = 500_000,
                trigger = RuleTrigger(kind = "product", value = "p_x"))
                .also { it.type = RuleType.substitution; it.status = RuleStatus.active },
        )
        ruleRepository.save(
            RuleEntity(id = "r_paused", recommend = "p_other", impressions = 500, accepts = 100,
                convRate = BigDecimal("20.00"), revenue = 200_000,
                trigger = RuleTrigger(kind = "product", value = "p_y"))
                .also { it.type = RuleType.crosssell; it.status = RuleStatus.paused },
        )
        ruleRepository.save(
            RuleEntity(id = "r_archived", recommend = "p_aquamaris", impressions = 9999, accepts = 9999,
                convRate = BigDecimal("99.00"), revenue = 9_999_999,
                trigger = RuleTrigger(kind = "product", value = "p_z"))
                .also { it.type = RuleType.substitution; it.status = RuleStatus.archived },
        )

        chainRepository.save(ChainEntity(id = "ch1", name = "Europharma", color = "#2A2BE2", points = 100).also { it.group = PharmacyGroup.pilot })
        pharmacyRepository.save(
            PharmacyEntity(id = "ph_hi", name = "Аптека Лидер", chainId = "ch1", chainName = "Europharma", city = "Алматы",
                liftPct = BigDecimal("12.00"), rulesAccepted = 300).also { it.group = PharmacyGroup.pilot },
        )
        pharmacyRepository.save(
            PharmacyEntity(id = "ph_lo", name = "Аптека Тихая", chainId = "ch1", chainName = "Europharma", city = "Астана",
                liftPct = BigDecimal("4.00"), rulesAccepted = 50).also { it.group = PharmacyGroup.control },
        )

        batchRepository.save(PayoutBatchEntity(id = "pb_appr", period = "апрель", pharmacists = 10, amount = 800_000, items = 10).also { it.status = PayoutBatchStatus.approved })
        batchRepository.save(PayoutBatchEntity(id = "pb_pend", period = "май", pharmacists = 12, amount = 300_000, items = 12).also { it.status = PayoutBatchStatus.pending })

        adminUserRepository.save(
            AdminUserEntity(email = "damir@jadran.com", passwordHash = passwordEncoder.encode("damir2026"),
                name = "Дамир", company = "Jadran").also { it.role = AdminRole.BRAND_MANAGER; it.status = AdminUserStatus.ACTIVE },
        )
        bearer = "Bearer " + login().tokens.accessToken
    }

    @Test
    fun `summary KPI агрегируется только по живым правилам`() {
        mockMvc.perform(get("/api/admin/dashboard/summary").header("Authorization", bearer))
            .andExpect(status().isOk)
            // impressions = 1000 + 500 (archived 9999 исключён)
            .andExpect(jsonPath("$.impressions").value(1500))
            .andExpect(jsonPath("$.accepts").value(500))
            // convRate = 500/1500*100 = 33.3
            .andExpect(jsonPath("$.convRate").value(33.3))
            .andExpect(jsonPath("$.activeRules").value(1))
            .andExpect(jsonPath("$.totalRules").value(2))
    }

    @Test
    fun `summary суммы выплат — approved vs pending`() {
        mockMvc.perform(get("/api/admin/dashboard/summary").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.paidOut").value(800_000))
            .andExpect(jsonPath("$.pendingPayout").value(300_000))
    }

    @Test
    fun `summary pilot-аптеки и средний lift`() {
        mockMvc.perform(get("/api/admin/dashboard/summary").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.pilotPharmacies").value(1))
            .andExpect(jsonPath("$.avgLiftPct").value(12.0))
    }

    @Test
    fun `summary топ-правила отсортированы по convRate с именем продукта`() {
        mockMvc.perform(get("/api/admin/dashboard/summary").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.topRules.length()").value(2))
            .andExpect(jsonPath("$.topRules[0].id").value("r_active"))
            .andExpect(jsonPath("$.topRules[0].label").value("Аквамарис"))
            .andExpect(jsonPath("$.topRules[0].convRate").value(40.0))
    }

    @Test
    fun `summary топ-аптеки по принятым правилам`() {
        mockMvc.perform(get("/api/admin/dashboard/summary").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.topPharmacies[0].id").value("ph_hi"))
            .andExpect(jsonPath("$.topPharmacies[0].rulesAccepted").value(300))
    }

    @Test
    fun `summary топ-товары по выручке от замен`() {
        mockMvc.perform(get("/api/admin/dashboard/summary").header("Authorization", bearer))
            .andExpect(status().isOk)
            // p_aquamaris только из r_active (500k), r_archived исключён
            .andExpect(jsonPath("$.topProducts[0].id").value("p_aquamaris"))
            .andExpect(jsonPath("$.topProducts[0].name").value("Аквамарис"))
            .andExpect(jsonPath("$.topProducts[0].revenue").value(500_000))
    }

    @Test
    fun `GET summary без Bearer → 401`() {
        mockMvc.perform(get("/api/admin/dashboard/summary"))
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
