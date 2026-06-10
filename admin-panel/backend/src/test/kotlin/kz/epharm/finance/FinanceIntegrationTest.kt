package kz.epharm.finance

import com.fasterxml.jackson.databind.ObjectMapper
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.domain.AdminUserStatus
import kz.epharm.auth.dto.LoginRequest
import kz.epharm.auth.dto.LoginResponse
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.finance.entity.PayoutBatchEntity
import kz.epharm.finance.entity.PayoutBatchStatus
import kz.epharm.finance.entity.PayoutItemEntity
import kz.epharm.finance.repository.PayoutBatchRepository
import kz.epharm.finance.repository.PayoutItemRepository
import kz.epharm.pharmacies.entity.ChainEntity
import kz.epharm.pharmacies.entity.PharmacyEntity
import kz.epharm.pharmacies.entity.PharmacyGroup
import kz.epharm.pharmacies.repository.ChainRepository
import kz.epharm.pharmacies.repository.PharmacyRepository
import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.entity.PharmacistTier
import kz.epharm.pharmacists.repository.PharmacistRepository
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
import java.time.Instant
import java.time.LocalDate

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Transactional
class FinanceIntegrationTest {

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
    @Autowired private lateinit var batchRepository: PayoutBatchRepository
    @Autowired private lateinit var itemRepository: PayoutItemRepository
    @Autowired private lateinit var pharmacistRepository: PharmacistRepository
    @Autowired private lateinit var pharmacyRepository: PharmacyRepository
    @Autowired private lateinit var chainRepository: ChainRepository
    @Autowired private lateinit var adminUserRepository: AdminUserRepository
    @Autowired private lateinit var passwordEncoder: PasswordEncoder

    private lateinit var bearer: String

    @BeforeEach
    fun seed() {
        itemRepository.deleteAll()
        batchRepository.deleteAll()
        pharmacistRepository.deleteAll()
        pharmacyRepository.deleteAll()
        chainRepository.deleteAll()
        adminUserRepository.deleteAll()

        chainRepository.save(
            ChainEntity(id = "europharma", name = "Europharma", color = "#2A2BE2", points = 100)
                .also { it.group = PharmacyGroup.pilot },
        )
        pharmacyRepository.save(
            PharmacyEntity(id = "ph_1", name = "Europharma №100",
                chainId = "europharma", chainName = "Europharma", city = "Алматы")
                .also { it.group = PharmacyGroup.pilot },
        )
        pharmacistRepository.save(
            PharmacistEntity(
                id = "u_1", name = "Айгерим", iin = "830909300014", phone = "+7 (701) 100-10-20",
                pharmacyId = "ph_1", pharmacyName = "Europharma №100", city = "Алматы",
                joinedAt = LocalDate.of(2026, 1, 15),
            ).also { it.tier = PharmacistTier.Gold; it.status = kz.epharm.pharmacists.entity.PharmacistStatus.active },
        )

        // 2 батча: 1 pending + 1 approved
        batchRepository.save(
            PayoutBatchEntity(id = "pb_pending", period = "1–15 мая 2026",
                pharmacists = 100, amount = 1_000_000, items = 100)
                .also { it.status = PayoutBatchStatus.pending },
        )
        batchRepository.save(
            PayoutBatchEntity(id = "pb_approved", period = "1–15 апреля 2026",
                pharmacists = 80, amount = 800_000, items = 80,
                reviewerName = "Старый ревьюер",
                approvedAt = Instant.parse("2026-04-17T10:00:00Z"))
                .also { it.status = PayoutBatchStatus.approved },
        )

        itemRepository.save(
            PayoutItemEntity(
                id = "pi_1", batchId = "pb_pending", pharmacistId = "u_1",
                pharmacistName = "Айгерим", pharmacy = "Europharma №100", city = "Алматы",
                receipts = 50, rules = 20, amount = 15_000,
            ),
        )
        itemRepository.save(
            PayoutItemEntity(
                id = "pi_2", batchId = "pb_pending", pharmacistId = "u_1",
                pharmacistName = "Айгерим", pharmacy = "Europharma №100", city = "Алматы",
                receipts = 100, rules = 40, amount = 30_000,
                flag = "Высокий бонус",
            ),
        )

        adminUserRepository.save(
            AdminUserEntity(
                email = "damir@jadran.com",
                passwordHash = passwordEncoder.encode("damir2026"),
                name = "Дамир", company = "Jadran",
            // Выплаты теперь утверждает только финансовая роль (segregation of duties),
            // поэтому тестовый админ — FINANCE_REVIEWER.
            ).also { it.role = AdminRole.FINANCE_REVIEWER; it.status = AdminUserStatus.ACTIVE },
        )
        bearer = "Bearer " + login().tokens.accessToken
    }

    @Test
    fun `GET payouts → 2 батча`() {
        mockMvc.perform(get("/api/admin/payouts").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
    }

    @Test
    fun `GET payouts filter status=pending`() {
        mockMvc.perform(get("/api/admin/payouts?status=pending").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value("pb_pending"))
    }

    @Test
    fun `GET batch by id`() {
        mockMvc.perform(get("/api/admin/payouts/pb_pending").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.period").value("1–15 мая 2026"))
            .andExpect(jsonPath("$.amount").value(1_000_000))
    }

    @Test
    fun `GET unknown batch → 404`() {
        mockMvc.perform(get("/api/admin/payouts/nope").header("Authorization", bearer))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
    }

    @Test
    fun `GET batch items — sorted by amount DESC`() {
        mockMvc.perform(get("/api/admin/payouts/pb_pending/items").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].id").value("pi_2")) // amount 30k > 15k
            .andExpect(jsonPath("$[1].id").value("pi_1"))
    }

    @Test
    fun `POST approve pending batch → status=approved + reviewer заполнен`() {
        mockMvc.perform(post("/api/admin/payouts/pb_pending/approve").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("approved"))
            .andExpect(jsonPath("$.reviewerName").value("Дамир"))
            .andExpect(jsonPath("$.approvedAt").isString)
    }

    @Test
    fun `POST approve уже approved → 409 CONFLICT`() {
        mockMvc.perform(post("/api/admin/payouts/pb_approved/approve").header("Authorization", bearer))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("CONFLICT"))
    }

    @Test
    fun `GET без Bearer → 401`() {
        mockMvc.perform(get("/api/admin/payouts"))
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
