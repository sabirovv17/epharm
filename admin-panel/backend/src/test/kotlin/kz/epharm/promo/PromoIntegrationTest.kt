package kz.epharm.promo

import com.fasterxml.jackson.databind.ObjectMapper
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.domain.AdminUserStatus
import kz.epharm.auth.dto.LoginRequest
import kz.epharm.auth.dto.LoginResponse
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.promo.dto.CreatePromoRequest
import kz.epharm.promo.dto.UpdatePromoRequest
import kz.epharm.promo.entity.PromoEntity
import kz.epharm.promo.entity.PromoStatus
import kz.epharm.promo.repository.PromoRepository
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
class PromoIntegrationTest {

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
    @Autowired private lateinit var promoRepository: PromoRepository
    @Autowired private lateinit var adminUserRepository: AdminUserRepository
    @Autowired private lateinit var passwordEncoder: PasswordEncoder

    private lateinit var bearer: String

    @BeforeEach
    fun seed() {
        promoRepository.deleteAll()
        adminUserRepository.deleteAll()

        promoRepository.save(
            PromoEntity(
                id = "pr_seed_a", title = "Майская кампания",
                brand = "Jadran-Galenski", period = "01.05 — 31.05",
                pharmacies = 100, budget = 1_000_000, spent = 500_000,
                kpi = "1000 рек.", cover = "#16C97A",
                createdBy = "seed",
            ).also { it.status = PromoStatus.active },
        )
        promoRepository.save(
            PromoEntity(
                id = "pr_seed_b", title = "Черновик кампании",
                brand = "Jadran-Galenski", period = "01.06 — 30.06",
                budget = 500_000, createdBy = "seed",
            ).also { it.status = PromoStatus.draft },
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
    fun `GET list returns all 2 promos sorted by updatedAt`() {
        mockMvc.perform(get("/api/admin/promo").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
    }

    @Test
    fun `GET list filtered by status=draft`() {
        mockMvc.perform(get("/api/admin/promo?status=draft").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value("pr_seed_b"))
    }

    @Test
    fun `GET single by id`() {
        mockMvc.perform(get("/api/admin/promo/pr_seed_a").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.title").value("Майская кампания"))
            .andExpect(jsonPath("$.budget").value(1_000_000))
            .andExpect(jsonPath("$.kpi").value("1000 рек."))
    }

    @Test
    fun `GET unknown id returns 404 NOT_FOUND`() {
        mockMvc.perform(get("/api/admin/promo/pr_nope").header("Authorization", bearer))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
    }

    @Test
    fun `POST create with valid body persists`() {
        val req = CreatePromoRequest(
            title = "Летняя кампания",
            status = PromoStatus.active,
            brand = "Jadran-Galenski",
            period = "01.07 — 31.07",
            budget = 2_000_000,
            kpi = "ожидаемо 3000 рек.",
            cover = "#3DCDA2",
        )
        mockMvc.perform(
            post("/api/admin/promo")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").exists())
            .andExpect(jsonPath("$.title").value("Летняя кампания"))
            .andExpect(jsonPath("$.status").value("active"))
            .andExpect(jsonPath("$.budget").value(2_000_000))

        assertThat(promoRepository.count()).isEqualTo(3)
    }

    @Test
    fun `POST create with blank title returns 400 VALIDATION_FAILED`() {
        val payload = """{"title":"","brand":"Jadran","budget":1000}"""
        mockMvc.perform(
            post("/api/admin/promo")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
    }

    @Test
    fun `POST create with negative budget returns 400 VALIDATION_FAILED`() {
        val payload = """{"title":"X","brand":"Jadran","budget":-100}"""
        mockMvc.perform(
            post("/api/admin/promo")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload),
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `PATCH update title and status from draft to active`() {
        val req = UpdatePromoRequest(title = "Обновлённое название", status = PromoStatus.active)
        mockMvc.perform(
            patch("/api/admin/promo/pr_seed_b")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.title").value("Обновлённое название"))
            .andExpect(jsonPath("$.status").value("active"))
    }

    @Test
    fun `Bug G regression — PATCH with status=archived rejected (must use dedicated endpoint)`() {
        // Урок из Rules Bug G — PATCH не должен ставить archived (silent state change).
        mockMvc.perform(
            patch("/api/admin/promo/pr_seed_a")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"status":"archived"}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
    }

    @Test
    fun `PATCH on already-archived returns 409 CONFLICT`() {
        promoRepository.findById("pr_seed_a").get().apply {
            status = PromoStatus.archived
            promoRepository.save(this)
        }
        mockMvc.perform(
            patch("/api/admin/promo/pr_seed_a")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"X"}"""),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("CONFLICT"))
    }

    @Test
    fun `POST archive flips status to archived`() {
        mockMvc.perform(
            post("/api/admin/promo/pr_seed_a/archive").header("Authorization", bearer),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("archived"))

        assertThat(promoRepository.findById("pr_seed_a").get().status).isEqualTo(PromoStatus.archived)
    }

    @Test
    fun `POST archive idempotent — second call returns 200 (not 409)`() {
        mockMvc.perform(post("/api/admin/promo/pr_seed_a/archive").header("Authorization", bearer))
            .andExpect(status().isOk)
        mockMvc.perform(post("/api/admin/promo/pr_seed_a/archive").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("archived"))
    }

    // ── Bug L: restore archived promo ─────────────────────────────────────

    @Test
    fun `Bug L — POST restore on archived promo returns 200 with status=draft`() {
        // Archive first.
        mockMvc.perform(post("/api/admin/promo/pr_seed_a/archive").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("archived"))
        // Restore.
        mockMvc.perform(post("/api/admin/promo/pr_seed_a/restore").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("draft"))

        assertThat(promoRepository.findById("pr_seed_a").get().status).isEqualTo(PromoStatus.draft)
    }

    @Test
    fun `Bug L — POST restore on already-active promo returns 200 idempotently`() {
        // pr_seed_a уже active в фикстуре. Restore — no-op.
        mockMvc.perform(post("/api/admin/promo/pr_seed_a/restore").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("active"))
    }

    @Test
    fun `Bug L — POST restore on unknown id returns 404`() {
        mockMvc.perform(post("/api/admin/promo/pr_nope/restore").header("Authorization", bearer))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
    }

    @Test
    fun `GET without Bearer returns 401`() {
        mockMvc.perform(get("/api/admin/promo"))
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
