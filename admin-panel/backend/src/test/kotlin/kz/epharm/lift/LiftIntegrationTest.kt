package kz.epharm.lift

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
    @Autowired private lateinit var passwordEncoder: PasswordEncoder

    private lateinit var bearer: String

    @BeforeEach
    fun seed() {
        pharmacyRepository.deleteAll()
        chainRepository.deleteAll()
        adminUserRepository.deleteAll()

        chainRepository.save(ChainEntity(id = "ch_a", name = "Europharma", color = "#2A2BE2", points = 100).also { it.group = PharmacyGroup.pilot })
        chainRepository.save(ChainEntity(id = "ch_b", name = "Садыхан", color = "#0F8F55", points = 50).also { it.group = PharmacyGroup.pilot })

        // Europharma: 2 pilot (lift 10 и 20 → avg 15), 1 control
        pharmacyRepository.save(PharmacyEntity(id = "p1", name = "EP-1", chainId = "ch_a", chainName = "Europharma", city = "Алматы", liftPct = BigDecimal("10.00"), receipts30d = 100).also { it.group = PharmacyGroup.pilot })
        pharmacyRepository.save(PharmacyEntity(id = "p2", name = "EP-2", chainId = "ch_a", chainName = "Europharma", city = "Алматы", liftPct = BigDecimal("20.00"), receipts30d = 200).also { it.group = PharmacyGroup.pilot })
        pharmacyRepository.save(PharmacyEntity(id = "p3", name = "EP-3", chainId = "ch_a", chainName = "Europharma", city = "Алматы", liftPct = BigDecimal("0.00"), receipts30d = 150).also { it.group = PharmacyGroup.control })
        // Садыхан: 1 pilot (lift 30), 1 rolled
        pharmacyRepository.save(PharmacyEntity(id = "p4", name = "SD-1", chainId = "ch_b", chainName = "Садыхан", city = "Астана", liftPct = BigDecimal("30.00"), receipts30d = 90).also { it.group = PharmacyGroup.pilot })
        pharmacyRepository.save(PharmacyEntity(id = "p5", name = "SD-2", chainId = "ch_b", chainName = "Садыхан", city = "Астана", liftPct = BigDecimal("0.00"), receipts30d = 60).also { it.group = PharmacyGroup.rolled })

        adminUserRepository.save(
            AdminUserEntity(email = "damir@jadran.com", passwordHash = passwordEncoder.encode("damir2026"),
                name = "Дамир", company = "Jadran").also { it.role = AdminRole.BRAND_MANAGER; it.status = AdminUserStatus.ACTIVE },
        )
        bearer = "Bearer " + login().tokens.accessToken
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
    fun `lift summary — networkLift среднее по pilot`() {
        // (10 + 20 + 30) / 3 = 20.0
        mockMvc.perform(get("/api/admin/lift").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.networkLiftPct").value(20.0))
            .andExpect(jsonPath("$.pValue").doesNotExist())
    }

    @Test
    fun `lift summary — receipts pilot vs control`() {
        mockMvc.perform(get("/api/admin/lift").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.pilotReceipts30d").value(390)) // 100+200+90
            .andExpect(jsonPath("$.controlReceipts30d").value(150))
    }

    @Test
    fun `lift summary — сегменты по сетям, сортировка по lift DESC`() {
        mockMvc.perform(get("/api/admin/lift").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.segments.length()").value(2))
            // Садыхан avg=30 > Europharma avg=15
            .andExpect(jsonPath("$.segments[0].name").value("Садыхан"))
            .andExpect(jsonPath("$.segments[0].liftPct").value(30.0))
            .andExpect(jsonPath("$.segments[1].name").value("Europharma"))
            .andExpect(jsonPath("$.segments[1].liftPct").value(15.0))
            .andExpect(jsonPath("$.segments[1].pharmacies").value(2))
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
