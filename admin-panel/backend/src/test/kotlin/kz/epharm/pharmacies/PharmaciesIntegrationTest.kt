package kz.epharm.pharmacies

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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
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
class PharmaciesIntegrationTest {

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
    @Autowired private lateinit var pharmacistRepository: PharmacistRepository
    @Autowired private lateinit var adminUserRepository: AdminUserRepository
    @Autowired private lateinit var passwordEncoder: PasswordEncoder

    private lateinit var bearer: String

    @BeforeEach
    fun seed() {
        pharmacistRepository.deleteAll()
        pharmacyRepository.deleteAll()
        chainRepository.deleteAll()
        adminUserRepository.deleteAll()

        chainRepository.save(
            ChainEntity(id = "europharma", name = "Europharma", color = "#2A2BE2", points = 100)
                .also { it.group = PharmacyGroup.pilot },
        )
        chainRepository.save(
            ChainEntity(id = "biosfera", name = "Биосфера", color = "#8B5CF6", points = 50)
                .also { it.group = PharmacyGroup.control },
        )

        pharmacyRepository.save(
            PharmacyEntity(
                id = "europharma_0", name = "Europharma №100",
                chainId = "europharma", chainName = "Europharma",
                city = "Алматы", district = "Бостандыкский", addr = "ул. Абая, 10",
                pharmacists = 3, receipts30d = 500, gmv30d = 1_500_000,
            ).also { it.group = PharmacyGroup.pilot },
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
    fun `GET chains returns 2 seeded`() {
        mockMvc.perform(get("/api/admin/pharmacies/chains").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].id").value("europharma")) // sorted by points DESC
    }

    @Test
    fun `GET pharmacies — список с правильными полями`() {
        mockMvc.perform(get("/api/admin/pharmacies").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].chainName").value("Europharma"))
            .andExpect(jsonPath("$[0].city").value("Алматы"))
    }

    @Test
    fun `GET pharmacies filter by group=pilot`() {
        mockMvc.perform(get("/api/admin/pharmacies?group=pilot").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
    }

    @Test
    fun `GET pharmacy by id 404 на unknown`() {
        mockMvc.perform(get("/api/admin/pharmacies/unknown").header("Authorization", bearer))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
    }

    @Test
    fun `POST create pharmacy — успешный сценарий`() {
        val payload = """
            {"name":"Новая аптека","chainId":"europharma","city":"Астана","group":"pilot"}
        """.trimIndent()
        mockMvc.perform(
            post("/api/admin/pharmacies")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("Новая аптека"))
            .andExpect(jsonPath("$.chainName").value("Europharma"))
    }

    @Test
    fun `POST create с unknown chain → 400`() {
        val payload = """
            {"name":"X","chainId":"nope","city":"X","group":"pilot"}
        """.trimIndent()
        mockMvc.perform(
            post("/api/admin/pharmacies")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
    }

    @Test
    fun `PATCH pharmacy обновляет метрики`() {
        mockMvc.perform(
            patch("/api/admin/pharmacies/europharma_0")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"receipts30d":1000}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.receipts30d").value(1000))
    }

    @Test
    fun `GET без Bearer → 401`() {
        mockMvc.perform(get("/api/admin/pharmacies"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `GET с недопустимым enum-параметром → 400 VALIDATION_FAILED`() {
        // group=bogus не входит в PharmacyGroup → MethodArgumentTypeMismatchException
        // → GlobalExceptionHandler.handleTypeMismatch (раньше Spring-дефолт без кода).
        mockMvc.perform(get("/api/admin/pharmacies?group=bogus").header("Authorization", bearer))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
    }

    // ── Chains CRUD (Блок 2) ─────────────────────────────────────────────

    @Test
    fun `POST chain creates — 201`() {
        val payload = """{"id":"newchain","name":"Новая сеть","color":"#16C97A","points":10,"group":"pilot"}"""
        mockMvc.perform(
            post("/api/admin/pharmacies/chains")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").value("newchain"))
            .andExpect(jsonPath("$.color").value("#16C97A"))
    }

    @Test
    fun `POST chain duplicate id → 409`() {
        val payload = """{"id":"europharma","name":"X","color":"#000000","group":"pilot"}"""
        mockMvc.perform(
            post("/api/admin/pharmacies/chains")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("CONFLICT"))
    }

    @Test
    fun `POST chain bad color → 400`() {
        val payload = """{"id":"badcol","name":"X","color":"red","group":"pilot"}"""
        mockMvc.perform(
            post("/api/admin/pharmacies/chains")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
    }

    @Test
    fun `PATCH chain updates points`() {
        mockMvc.perform(
            patch("/api/admin/pharmacies/chains/biosfera")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"points":999}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.points").value(999))
            .andExpect(jsonPath("$.name").value("Биосфера"))
    }

    @Test
    fun `DELETE chain with pharmacies → 409`() {
        // europharma имеет аптеку europharma_0
        mockMvc.perform(delete("/api/admin/pharmacies/chains/europharma").header("Authorization", bearer))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("CONFLICT"))
    }

    @Test
    fun `DELETE empty chain → 204`() {
        // biosfera без аптек — удаляется
        mockMvc.perform(delete("/api/admin/pharmacies/chains/biosfera").header("Authorization", bearer))
            .andExpect(status().isNoContent)
        mockMvc.perform(get("/api/admin/pharmacies/chains").header("Authorization", bearer))
            .andExpect(jsonPath("$.length()").value(1))
    }

    // ── Pharmacy DELETE (Блок 2) ──────────────────────────────────────────

    @Test
    fun `DELETE pharmacy without pharmacists → 204`() {
        mockMvc.perform(delete("/api/admin/pharmacies/europharma_0").header("Authorization", bearer))
            .andExpect(status().isNoContent)
        mockMvc.perform(get("/api/admin/pharmacies/europharma_0").header("Authorization", bearer))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `DELETE pharmacy with pharmacists → 409`() {
        pharmacistRepository.save(
            PharmacistEntity(
                id = "phc_test01", name = "Тест Фармацевт", iin = "990101300123",
                phone = "+77001234567", pharmacyId = "europharma_0",
                pharmacyName = "Europharma №100", city = "Алматы",
            ),
        )
        mockMvc.perform(delete("/api/admin/pharmacies/europharma_0").header("Authorization", bearer))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("CONFLICT"))
        // аптека на месте
        mockMvc.perform(get("/api/admin/pharmacies/europharma_0").header("Authorization", bearer))
            .andExpect(status().isOk)
    }

    @Test
    fun `DELETE pharmacy unknown → 404`() {
        mockMvc.perform(delete("/api/admin/pharmacies/unknown").header("Authorization", bearer))
            .andExpect(status().isNotFound)
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
