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
import kz.epharm.posm.entity.RecommendationEventEntity
import kz.epharm.posm.entity.RecommendationOutcome
import kz.epharm.posm.repository.RecommendationEventRepository
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

/**
 * Dashboard на РЕАЛЬНЫХ данных: KPI/топы агрегируются из recommendation_events
 * (а не из денормализованных полей правил/аптек), выплаты — из payout_batches,
 * avgLiftPct — из конверсии pilot/control.
 */
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
    @Autowired private lateinit var eventRepository: RecommendationEventRepository
    @Autowired private lateinit var adminUserRepository: AdminUserRepository
    @Autowired private lateinit var passwordEncoder: PasswordEncoder

    private lateinit var bearer: String

    @BeforeEach
    fun seed() {
        eventRepository.deleteAll()
        ruleRepository.deleteAll()
        productRepository.deleteAll()
        pharmacyRepository.deleteAll()
        chainRepository.deleteAll()
        batchRepository.deleteAll()
        adminUserRepository.deleteAll()

        productRepository.save(ProductEntity(id = "p_aquamaris", name = "Аквамарис", brand = "Jadran", vendor = "Jadran", mnn = "Sea water", price = 1800))
        productRepository.save(ProductEntity(id = "p_other", name = "Линекс", brand = "Sandoz", vendor = "Sandoz", mnn = "Probiotic", price = 2400))

        // 2 живых правила + 1 архивное (исключается из агрегаций KPI).
        ruleRepository.save(
            RuleEntity(id = "r_active", recommend = "p_aquamaris", trigger = RuleTrigger(kind = "product", value = "p_x"))
                .also { it.type = RuleType.substitution; it.status = RuleStatus.active },
        )
        ruleRepository.save(
            RuleEntity(id = "r_paused", recommend = "p_other", trigger = RuleTrigger(kind = "product", value = "p_y"))
                .also { it.type = RuleType.crosssell; it.status = RuleStatus.paused },
        )
        ruleRepository.save(
            RuleEntity(id = "r_archived", recommend = "p_aquamaris", trigger = RuleTrigger(kind = "product", value = "p_z"))
                .also { it.type = RuleType.substitution; it.status = RuleStatus.archived },
        )

        chainRepository.save(ChainEntity(id = "ch1", name = "Europharma", color = "#2A2BE2", points = 100).also { it.group = PharmacyGroup.pilot })
        pharmacyRepository.save(PharmacyEntity(id = "ph_hi", name = "Аптека Лидер", chainId = "ch1", chainName = "Europharma", city = "Алматы").also { it.group = PharmacyGroup.pilot })
        pharmacyRepository.save(PharmacyEntity(id = "ph_lo", name = "Аптека Тихая", chainId = "ch1", chainName = "Europharma", city = "Астана").also { it.group = PharmacyGroup.control })

        // Реальные события:
        //  r_active (p_aquamaris) в ph_hi (pilot): 50 показов, 20 принятых, 1000₸ → conv 40%, revenue 20000
        //  r_paused (p_other)     в ph_lo (control): 50 показов, 10 принятых, 500₸ → conv 20%, revenue 5000
        events("r_active", "ph_hi", "p_aquamaris", shown = 50, accepted = 20, amount = 1000)
        events("r_paused", "ph_lo", "p_other", shown = 50, accepted = 10, amount = 500)

        batchRepository.save(PayoutBatchEntity(id = "pb_appr", period = "апрель", pharmacists = 10, amount = 800_000, items = 10).also { it.status = PayoutBatchStatus.approved })
        batchRepository.save(PayoutBatchEntity(id = "pb_pend", period = "май", pharmacists = 12, amount = 300_000, items = 12).also { it.status = PayoutBatchStatus.pending })

        adminUserRepository.save(
            AdminUserEntity(email = "damir@jadran.com", passwordHash = passwordEncoder.encode("damir2026"),
                name = "Дамир", company = "Jadran").also { it.role = AdminRole.BRAND_MANAGER; it.status = AdminUserStatus.ACTIVE },
        )
        bearer = "Bearer " + login().tokens.accessToken
    }

    private fun events(ruleId: String, pharmacyId: String, sku: String, shown: Int, accepted: Int, amount: Long) {
        repeat(shown) { i ->
            val e = RecommendationEventEntity(
                id = "rec_${ruleId}_$i",
                sessionId = "s_${ruleId}_$i",
                pharmacistId = "ph_$pharmacyId",
                pharmacyId = pharmacyId,
                ruleId = ruleId,
                recommendSku = sku,
                recommendName = if (sku == "p_aquamaris") "Аквамарис" else "Линекс",
                expectedAmount = amount,
                bonus = 100,
            ).also { if (i < accepted) it.outcome = RecommendationOutcome.accepted }
            eventRepository.save(e)
        }
    }

    @Test
    fun `summary KPI агрегируется только по живым правилам из событий`() {
        mockMvc.perform(get("/api/admin/dashboard/summary").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.impressions").value(100)) // 50 + 50
            .andExpect(jsonPath("$.accepts").value(30)) // 20 + 10
            .andExpect(jsonPath("$.convRate").value(30.0))
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
    fun `summary pilot-аптеки и реальный lift из событий`() {
        mockMvc.perform(get("/api/admin/dashboard/summary").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.pilotPharmacies").value(1))
            // pilot ph_hi conv 0.4 vs control ph_lo conv 0.2 → lift 100%
            .andExpect(jsonPath("$.avgLiftPct").value(100.0))
    }

    @Test
    fun `summary топ-правила по реальной convRate с именем продукта`() {
        mockMvc.perform(get("/api/admin/dashboard/summary").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.topRules.length()").value(2))
            .andExpect(jsonPath("$.topRules[0].id").value("r_active"))
            .andExpect(jsonPath("$.topRules[0].label").value("Аквамарис"))
            .andExpect(jsonPath("$.topRules[0].convRate").value(40.0))
            .andExpect(jsonPath("$.topRules[0].accepts").value(20))
    }

    @Test
    fun `summary топ-аптеки по реальным принятым рекомендациям`() {
        mockMvc.perform(get("/api/admin/dashboard/summary").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.topPharmacies[0].id").value("ph_hi"))
            .andExpect(jsonPath("$.topPharmacies[0].rulesAccepted").value(20))
    }

    @Test
    fun `summary топ-товары по реальной выручке от принятых рекомендаций`() {
        mockMvc.perform(get("/api/admin/dashboard/summary").header("Authorization", bearer))
            .andExpect(status().isOk)
            // p_aquamaris: 20 принятых × 1000 = 20000 (больше, чем p_other 10 × 500 = 5000)
            .andExpect(jsonPath("$.topProducts[0].id").value("p_aquamaris"))
            .andExpect(jsonPath("$.topProducts[0].name").value("Аквамарис"))
            .andExpect(jsonPath("$.topProducts[0].revenue").value(20_000))
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
