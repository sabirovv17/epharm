package kz.epharm.auth

import com.fasterxml.jackson.databind.ObjectMapper
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.domain.AdminUserStatus
import kz.epharm.auth.dto.LoginRequest
import kz.epharm.auth.dto.LoginResponse
import kz.epharm.auth.dto.RefreshRequest
import kz.epharm.auth.dto.RefreshResponse
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.auth.repository.RefreshTokenRepository
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
class AuthIntegrationTest {

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
        fun dataSourceProps(reg: DynamicPropertyRegistry) {
            reg.add("spring.datasource.url") { postgres.jdbcUrl }
            reg.add("spring.datasource.username") { postgres.username }
            reg.add("spring.datasource.password") { postgres.password }
        }
    }

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var adminUserRepository: AdminUserRepository
    @Autowired private lateinit var refreshTokenRepository: RefreshTokenRepository
    @Autowired private lateinit var passwordEncoder: PasswordEncoder

    @BeforeEach
    fun seed() {
        refreshTokenRepository.deleteAll()
        adminUserRepository.deleteAll()

        val user = AdminUserEntity(
            email = "damir@jadran.com",
            passwordHash = passwordEncoder.encode("damir2026"),
            name = "Дамир Нурланов",
            company = "Jadran",
        ).also {
            it.role = AdminRole.BRAND_MANAGER
            it.status = AdminUserStatus.ACTIVE
        }
        adminUserRepository.save(user)
    }

    @Test
    fun `POST login with valid credentials returns 200 with tokens and user`() {
        val req = LoginRequest(email = "damir@jadran.com", password = "damir2026")
        val result = mockMvc.perform(
            post("/api/admin/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.user.email").value("damir@jadran.com"))
            .andExpect(jsonPath("$.user.name").value("Дамир Нурланов"))
            .andExpect(jsonPath("$.user.role").value("BRAND_MANAGER"))
            .andExpect(jsonPath("$.user.company").value("Jadran"))
            .andExpect(jsonPath("$.tokens.accessToken").isString)
            .andExpect(jsonPath("$.tokens.refreshToken").isString)
            .andReturn()

        val body: LoginResponse = objectMapper.readValue(
            result.response.contentAsString,
            LoginResponse::class.java,
        )
        assertThat(body.tokens.accessToken).isNotBlank
        assertThat(body.tokens.refreshToken).isNotBlank
    }

    @Test
    fun `POST login is case-insensitive on email`() {
        // Mixed case на local-part — должно совпасть с seeded damir@jadran.com.
        // Полностью uppercase email иногда отвергается @Email-валидатором по IDN-нормализации,
        // поэтому используем умеренный mixed case.
        val req = LoginRequest(email = "Damir@jadran.com", password = "damir2026")
        mockMvc.perform(
            post("/api/admin/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)),
        ).andExpect(status().isOk)
    }

    @Test
    fun `POST login with wrong password returns 401 INVALID_CREDENTIALS`() {
        val req = LoginRequest(email = "damir@jadran.com", password = "wrong")
        mockMvc.perform(
            post("/api/admin/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
    }

    @Test
    fun `POST login with unknown email returns 401 INVALID_CREDENTIALS`() {
        val req = LoginRequest(email = "nobody@example.com", password = "whatever")
        mockMvc.perform(
            post("/api/admin/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
    }

    @Test
    fun `POST login with bad email format returns 400 VALIDATION_FAILED`() {
        val req = LoginRequest(email = "not-an-email", password = "x")
        mockMvc.perform(
            post("/api/admin/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
    }

    @Test
    fun `POST refresh with valid token returns new pair`() {
        val login = login("damir@jadran.com", "damir2026")
        val refresh = RefreshRequest(refreshToken = login.tokens.refreshToken)

        val result = mockMvc.perform(
            post("/api/admin/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(refresh)),
        )
            .andExpect(status().isOk)
            .andReturn()

        val body: RefreshResponse = objectMapper.readValue(
            result.response.contentAsString,
            RefreshResponse::class.java,
        )
        // Новые токены отличаются от старых
        assertThat(body.tokens.accessToken).isNotEqualTo(login.tokens.accessToken)
        assertThat(body.tokens.refreshToken).isNotEqualTo(login.tokens.refreshToken)
    }

    @Test
    fun `POST refresh with revoked token returns 401`() {
        val login = login("damir@jadran.com", "damir2026")
        // Используем 1 раз — токен ротируется
        val firstRefresh = RefreshRequest(refreshToken = login.tokens.refreshToken)
        mockMvc.perform(
            post("/api/admin/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(firstRefresh)),
        ).andExpect(status().isOk)

        // Повтор — уже не работает (rotation одноразовый)
        mockMvc.perform(
            post("/api/admin/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(firstRefresh)),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"))
    }

    @Test
    fun `POST refresh with garbage token returns 401`() {
        val refresh = RefreshRequest(refreshToken = "absolutely-not-a-token")
        mockMvc.perform(
            post("/api/admin/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(refresh)),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"))
    }

    @Test
    fun `GET me with valid Bearer returns user DTO`() {
        val login = login("damir@jadran.com", "damir2026")
        mockMvc.perform(
            get("/api/admin/auth/me")
                .header("Authorization", "Bearer ${login.tokens.accessToken}"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.email").value("damir@jadran.com"))
            .andExpect(jsonPath("$.role").value("BRAND_MANAGER"))
    }

    @Test
    fun `GET me without Bearer returns 401`() {
        // Нет токена → 401 (HttpStatusEntryPoint(UNAUTHORIZED) в SecurityConfig).
        // Истёкший access-токен тоже даёт 401 → axios-interceptor делает refresh.
        mockMvc.perform(get("/api/admin/auth/me"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `GET me with bogus Bearer returns 401`() {
        mockMvc.perform(
            get("/api/admin/auth/me")
                .header("Authorization", "Bearer not.a.real.jwt"),
        )
            .andExpect(status().isUnauthorized)
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun login(email: String, password: String): LoginResponse {
        val req = LoginRequest(email = email, password = password)
        val result = mockMvc.perform(
            post("/api/admin/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)),
        ).andExpect(status().isOk).andReturn()
        return objectMapper.readValue(result.response.contentAsString, LoginResponse::class.java)
    }
}
