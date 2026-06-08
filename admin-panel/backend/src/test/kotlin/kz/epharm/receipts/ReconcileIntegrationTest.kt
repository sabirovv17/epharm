package kz.epharm.receipts

import com.fasterxml.jackson.databind.ObjectMapper
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
import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.receipts.entity.PendingBonusEntity
import kz.epharm.receipts.repository.PendingBonusRepository
import kz.epharm.receipts.repository.ReceiptRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Transactional
class ReconcileIntegrationTest {

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
    @Autowired private lateinit var receiptRepository: ReceiptRepository
    @Autowired private lateinit var pendingBonusRepository: PendingBonusRepository
    @Autowired private lateinit var pharmacistRepository: PharmacistRepository
    @Autowired private lateinit var pharmacyRepository: PharmacyRepository
    @Autowired private lateinit var chainRepository: ChainRepository
    @Autowired private lateinit var adminUserRepository: AdminUserRepository
    @Autowired private lateinit var passwordEncoder: PasswordEncoder

    private lateinit var bearer: String

    @BeforeEach
    fun seed() {
        receiptRepository.deleteAll()
        pendingBonusRepository.deleteAll()
        pharmacistRepository.deleteAll()
        pharmacyRepository.deleteAll()
        chainRepository.deleteAll()
        adminUserRepository.deleteAll()

        // FK pharmacists.pharmacy_id → pharmacies.id: сначала сеть + аптека.
        chainRepository.save(
            ChainEntity(id = "ch_t", name = "Сеть Т", color = "#16C97A", points = 10)
                .also { it.group = PharmacyGroup.pilot },
        )
        pharmacyRepository.save(
            PharmacyEntity(
                id = "ph_t", name = "Аптека Т", chainId = "ch_t", chainName = "Сеть Т",
                city = "Алматы", district = "", addr = "",
            ).also { it.group = PharmacyGroup.pilot },
        )
        pharmacistRepository.save(
            PharmacistEntity(
                id = "u_t", name = "Тест Фарм", iin = "990101300123", phone = "+77001234567",
                pharmacyId = "ph_t", pharmacyName = "Аптека Т", city = "Алматы",
                balance = 0, earned30d = 0,
            ),
        )
        pendingBonusRepository.save(
            PendingBonusEntity(
                id = "pb_t", pharmacistId = "u_t", pharmacistName = "Тест Фарм",
                pharmacyId = "ph_t", pharmacyName = "Аптека Т",
                sku = "p_x", productName = "Товар X", expectedAmount = 1_000, bonus = 300,
                createdAt = Instant.now(),
            ),
        )
        adminUserRepository.save(
            AdminUserEntity(email = "damir@jadran.com", passwordHash = passwordEncoder.encode("damir2026"),
                name = "Дамир", company = "Jadran").also { it.role = AdminRole.BRAND_MANAGER; it.status = AdminUserStatus.ACTIVE },
        )
        bearer = "Bearer " + login().tokens.accessToken
    }

    @Test
    fun `submit QR → авто-одобрение + начисление бонуса`() {
        // QR → score 0.97, сумма совпала (mock берёт hint=expected), окно ок → auto-approve.
        mockMvc.perform(
            multipart("/api/admin/reconcile/submit")
                .param("pharmacistId", "u_t")
                .param("qr", "FISCAL-QR-001")
                .header("Authorization", bearer),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.status").value("approved"))
            .andExpect(jsonPath("$.autoApproved").value(true))
            .andExpect(jsonPath("$.bonusCredited").value(300))

        // Баланс фармацевта вырос на бонус.
        val ph = pharmacistRepository.findById("u_t").get()
        assert(ph.balance == 300L) { "balance=${ph.balance}, ожидали 300" }
    }

    @Test
    fun `submit фото (низкий score) → ручная модерация (pending)`() {
        val file = MockMultipartFile("file", "r.jpg", "image/jpeg", byteArrayOf(1, 2, 3))
        mockMvc.perform(
            multipart("/api/admin/reconcile/submit")
                .file(file)
                .param("pharmacistId", "u_t")
                .header("Authorization", bearer),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.status").value("pending"))
            .andExpect(jsonPath("$.autoApproved").value(false))
        // Бонус НЕ начислен до ручного одобрения.
        assert(pharmacistRepository.findById("u_t").get().balance == 0L)
    }

    @Test
    fun `дубль фискального чека → flagged (анти-фрод)`() {
        // Первый сабмит QR — проходит. Второй с тем же QR → тот же fiscalId → дубль.
        repeat(2) {
            mockMvc.perform(
                multipart("/api/admin/reconcile/submit")
                    .param("pharmacistId", "u_t").param("qr", "DUP-QR")
                    .header("Authorization", bearer),
            ).andExpect(status().isCreated)
        }
        val flagged = receiptRepository.findAll().filter { it.status.name == "flagged" }
        assert(flagged.any { it.flagReason == "duplicate_receipt" }) { "ожидали flagged duplicate" }
    }

    @Test
    fun `ручное одобрение pending → начисление`() {
        val file = MockMultipartFile("file", "r.jpg", "image/jpeg", byteArrayOf(1, 2, 3))
        val res = mockMvc.perform(
            multipart("/api/admin/reconcile/submit").file(file)
                .param("pharmacistId", "u_t").header("Authorization", bearer),
        ).andExpect(status().isCreated).andReturn()
        val id = objectMapper.readTree(res.response.contentAsString)["id"].asText()

        mockMvc.perform(post("/api/admin/reconcile/$id/approve").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("approved"))
            .andExpect(jsonPath("$.bonusCredited").value(300))
        assert(pharmacistRepository.findById("u_t").get().balance == 300L)
    }

    @Test
    fun `отклонение чека → rejected + причина`() {
        val file = MockMultipartFile("file", "r.jpg", "image/jpeg", byteArrayOf(1, 2, 3))
        val res = mockMvc.perform(
            multipart("/api/admin/reconcile/submit").file(file)
                .param("pharmacistId", "u_t").header("Authorization", bearer),
        ).andReturn()
        val id = objectMapper.readTree(res.response.contentAsString)["id"].asText()

        mockMvc.perform(
            post("/api/admin/reconcile/$id/reject").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content("""{"reason":"поддельный"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("rejected"))
            .andExpect(jsonPath("$.flagReason").value("поддельный"))
        assert(pharmacistRepository.findById("u_t").get().balance == 0L)
    }

    @Test
    fun `GET summary считает ветки`() {
        mockMvc.perform(get("/api/admin/reconcile/summary").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.queue").exists())
            .andExpect(jsonPath("$.approved").exists())
    }

    @Test
    fun `GET reconcile без Bearer → 401`() {
        mockMvc.perform(get("/api/admin/reconcile"))
            .andExpect(status().isUnauthorized)
    }

    private fun login(): LoginResponse {
        val req = LoginRequest(email = "damir@jadran.com", password = "damir2026")
        val result = mockMvc.perform(
            post("/api/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)),
        ).andExpect(status().isOk).andReturn()
        return objectMapper.readValue(result.response.contentAsString, LoginResponse::class.java)
    }
}
