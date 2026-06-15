package kz.epharm.lift

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.domain.AdminUserStatus
import kz.epharm.auth.dto.LoginRequest
import kz.epharm.auth.dto.LoginResponse
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.pharmacies.entity.ChainEntity
import kz.epharm.pharmacies.entity.PharmacyEntity
import kz.epharm.pharmacies.entity.PharmacyGroup
import kz.epharm.pharmacies.repository.ChainRepository
import kz.epharm.pharmacies.repository.PharmacyRepository
import kz.epharm.posm.entity.PosSaleEntity
import kz.epharm.posm.entity.RecommendationEventEntity
import kz.epharm.posm.entity.RecommendationOutcome
import kz.epharm.posm.repository.PosSaleRepository
import kz.epharm.posm.repository.RecommendationEventRepository
import org.hamcrest.Matchers.greaterThan
import org.hamcrest.Matchers.lessThan
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
 * Lift на РЕАЛЬНЫХ данных: конверсия pilot/control из recommendation_events,
 * receipts30d из pos_sales, pValue из z-теста. Сеем события и продажи, не поля.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Transactional
class LiftIntegrationTest {

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
    @Autowired private lateinit var pharmacyRepository: PharmacyRepository
    @Autowired private lateinit var chainRepository: ChainRepository
    @Autowired private lateinit var adminUserRepository: AdminUserRepository
    @Autowired private lateinit var eventRepository: RecommendationEventRepository
    @Autowired private lateinit var saleRepository: PosSaleRepository
    @Autowired private lateinit var passwordEncoder: PasswordEncoder

    private lateinit var bearer: String

    @BeforeEach
    fun seed() {
        eventRepository.deleteAll()
        saleRepository.deleteAll()
        pharmacyRepository.deleteAll()
        chainRepository.deleteAll()
        adminUserRepository.deleteAll()

        chainRepository.save(ChainEntity(id = "ch_a", name = "Europharma", color = "#2A2BE2", points = 100).also { it.group = PharmacyGroup.pilot })
        chainRepository.save(ChainEntity(id = "ch_b", name = "Садыхан", color = "#0F8F55", points = 50).also { it.group = PharmacyGroup.pilot })

        // pilot: p1,p2 (Europharma), p4 (Садыхан); control: p3; rolled: p5
        pharmacyRepository.save(pharmacy("p1", "EP-1", "ch_a", "Europharma", PharmacyGroup.pilot))
        pharmacyRepository.save(pharmacy("p2", "EP-2", "ch_a", "Europharma", PharmacyGroup.pilot))
        pharmacyRepository.save(pharmacy("p3", "EP-3", "ch_a", "Europharma", PharmacyGroup.control))
        pharmacyRepository.save(pharmacy("p4", "SD-1", "ch_b", "Садыхан", PharmacyGroup.pilot))
        pharmacyRepository.save(pharmacy("p5", "SD-2", "ch_b", "Садыхан", PharmacyGroup.rolled))

        // Конверсия (accepted/shown):
        //  Europharma pilot: p1 20/12, p2 20/8 → 40 показов, 20 принятых = 0.5
        //  Садыхан pilot:    p4 10/8         → 10 показов, 8 принятых  = 0.8
        //  control:          p3 20/4         → 20 показов, 4 принятых  = 0.2
        //  pilot всего: 50 показов, 28 принятых = 0.56
        //  networkLift = (0.56-0.2)/0.2*100 = 180.0
        events("p1", shown = 20, accepted = 12)
        events("p2", shown = 20, accepted = 8)
        events("p4", shown = 10, accepted = 8)
        events("p3", shown = 20, accepted = 4)

        // Чеки кассы (receipts30d): pilot p1:5 p2:5 p4:3 = 13; control p3:7
        sales("p1", 5); sales("p2", 5); sales("p4", 3); sales("p3", 7)

        adminUserRepository.save(
            AdminUserEntity(email = "damir@jadran.com", passwordHash = passwordEncoder.encode("damir2026"),
                name = "Дамир", company = "Jadran").also { it.role = AdminRole.BRAND_MANAGER; it.status = AdminUserStatus.ACTIVE },
        )
        bearer = "Bearer " + login().tokens.accessToken
    }

    private fun pharmacy(id: String, name: String, chainId: String, chainName: String, group: PharmacyGroup) =
        PharmacyEntity(id = id, name = name, chainId = chainId, chainName = chainName, city = "Алматы")
            .also { it.group = group }

    /** Сеет [shown] показов рекомендации в аптеке, из них [accepted] принятых. */
    private fun events(pharmacyId: String, shown: Int, accepted: Int) {
        repeat(shown) { i ->
            val e = RecommendationEventEntity(
                id = "rec_${pharmacyId}_$i",
                sessionId = "s_${pharmacyId}_$i",
                pharmacistId = "ph_$pharmacyId",
                pharmacyId = pharmacyId,
                ruleId = "r_001",
                recommendSku = "prod_x",
                recommendName = "Товар X",
                expectedAmount = 1000,
                bonus = 100,
            ).also { if (i < accepted) it.outcome = RecommendationOutcome.accepted }
            eventRepository.save(e)
        }
    }

    private fun sales(pharmacyId: String, count: Int) {
        repeat(count) { i ->
            saleRepository.save(
                PosSaleEntity(id = "sale_${pharmacyId}_$i", pharmacistId = "ph_$pharmacyId", pharmacyId = pharmacyId, totalAmount = 5000)
                    .also { it.printedAt = Instant.now(); it.createdAt = Instant.now() },
            )
        }
    }

    @Test
    fun `lift summary — counts по группам`() {
        mockMvc.perform(get("/api/admin/lift").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.pilotCount").value(3))
            .andExpect(jsonPath("$.controlCount").value(1))
            .andExpect(jsonPath("$.rolledCount").value(1))
    }

    @Test
    fun `lift summary — реальный networkLift и значимый pValue`() {
        mockMvc.perform(get("/api/admin/lift").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.networkLiftPct").value(180.0))
            .andExpect(jsonPath("$.insufficientData").value(false))
            // pValue — настоящее число от z-теста, статистически значимо (< 0.05).
            .andExpect(jsonPath("$.pValue").value(lessThan(0.05)))
            .andExpect(jsonPath("$.pValue").value(greaterThan(0.0)))
    }

    @Test
    fun `lift summary — receipts pilot vs control из pos_sales`() {
        mockMvc.perform(get("/api/admin/lift").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.pilotReceipts30d").value(13))
            .andExpect(jsonPath("$.controlReceipts30d").value(7))
    }

    @Test
    fun `lift summary — сегменты по сетям, сортировка по lift DESC`() {
        mockMvc.perform(get("/api/admin/lift").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.segments.length()").value(2))
            // Садыхан (0.8 vs 0.2 → 300%) > Europharma (0.5 vs 0.2 → 150%)
            .andExpect(jsonPath("$.segments[0].name").value("Садыхан"))
            .andExpect(jsonPath("$.segments[0].liftPct").value(300.0))
            .andExpect(jsonPath("$.segments[1].name").value("Europharma"))
            .andExpect(jsonPath("$.segments[1].liftPct").value(150.0))
            .andExpect(jsonPath("$.segments[1].pharmacies").value(2))
    }

    @Test
    fun `lift summary — нет событий → insufficientData, pValue null`() {
        eventRepository.deleteAll()
        mockMvc.perform(get("/api/admin/lift").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.insufficientData").value(true))
            .andExpect(jsonPath("$.networkLiftPct").value(0.0))
            .andExpect(jsonPath("$.pValue").doesNotExist())
    }

    @Test
    fun `GET lift без Bearer → 401`() {
        mockMvc.perform(get("/api/admin/lift"))
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
