package kz.epharm.shared

import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.service.JwtService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Контракт ошибок аутентификации/авторизации (п.6 — корень бага «Ошибка сервера»).
 *
 * Раньше SecurityConfig отдавал 401/403 с ПУСТЫМ телом (HttpStatusEntryPoint). Весь остальной
 * API отдаёт ошибки как JSON {code,message} через GlobalExceptionHandler, поэтому мобильный
 * `_decodeList` на пустом теле деградировал в дефолтную «Ошибка сервера» (экран «Мои чеки»).
 *
 * Тест фиксирует, что security ТОЖЕ отдаёт JSON `ApiErrorResponse` {code,message}:
 *   - запрос на защищённый mobile-эндпоинт без токена → 401 + JSON {code:UNAUTHORIZED}
 *   - pharmacist-токен на admin-эндпоинт (роль не та) → 403 + JSON {code:FORBIDDEN}
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class AuthErrorContractTest {

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
    @Autowired private lateinit var jwtService: JwtService

    @Test
    fun `защищённый mobile-эндпоинт без токена → 401 + JSON {code,message}`() {
        mockMvc.perform(get("/api/mobile/receipts"))
            .andExpect(status().isUnauthorized)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
            // Тело НЕ пустое — message присутствует и нетривиален.
            .andExpect(jsonPath("$.message").isString)
            .andExpect(jsonPath("$.message").isNotEmpty)
    }

    @Test
    fun `pharmacist-токен на admin-эндпоинте → 403 + JSON {code,message}`() {
        // Роль PHARMACIST не входит в ADMIN_ROLES → аутентифицирован, но нет доступа →
        // ApiAccessDeniedHandler, а не пустой 403.
        val token = jwtService.issuePharmacistToken("u_rx", "Фарм Тестов", "+77005556677")
        mockMvc.perform(
            get("/api/admin/catalog/products").header("Authorization", "Bearer $token"),
        )
            .andExpect(status().isForbidden)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("FORBIDDEN"))
            .andExpect(jsonPath("$.message").isString)
            .andExpect(jsonPath("$.message").isNotEmpty)
    }

    @Test
    fun `невалидный токен на mobile-эндпоинте → 401 + JSON {code,message}`() {
        // Битый/невалидный токен JwtAuthenticationFilter не аутентифицирует → 401, не пустое тело.
        mockMvc.perform(
            get("/api/mobile/receipts").header("Authorization", "Bearer not-a-real-jwt"),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.message").isNotEmpty)
    }

    @Test
    fun `training manager имеет доступ к учебному контуру но не к HQ API`() {
        val token = adminToken(AdminRole.TRAINING_MANAGER)

        mockMvc.perform(
            get("/api/admin/lms/courses").header("Authorization", "Bearer $token"),
        ).andExpect(status().isOk)

        mockMvc.perform(
            get("/api/admin/ai-exam/questions").header("Authorization", "Bearer $token"),
        ).andExpect(status().isOk)

        mockMvc.perform(
            get("/api/admin/training/pharmacists").header("Authorization", "Bearer $token"),
        ).andExpect(status().isOk)

        mockMvc.perform(
            get("/api/admin/catalog/products").header("Authorization", "Bearer $token"),
        )
            .andExpect(status().isForbidden)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("FORBIDDEN"))
    }

    @Test
    fun `HQ HEAD читает весь учебный контур но не может изменять данные`() {
        val token = adminToken(AdminRole.HQ_HEAD)
        val authorization = "Bearer $token"

        mockMvc.perform(
            get("/api/admin/lms/courses").header("Authorization", authorization),
        ).andExpect(status().isOk)

        mockMvc.perform(
            get("/api/admin/ai-exam/questions").header("Authorization", authorization),
        ).andExpect(status().isOk)

        mockMvc.perform(
            get("/api/admin/training/dashboard").header("Authorization", authorization),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.capabilities.canManagePrograms").value(false))
            .andExpect(jsonPath("$.capabilities.canManageAssignments").value(false))
            .andExpect(jsonPath("$.capabilities.canManageEvents").value(false))
            .andExpect(jsonPath("$.capabilities.canMarkAttendance").value(false))
            .andExpect(jsonPath("$.capabilities.canAdjustRewards").value(false))
            .andExpect(jsonPath("$.capabilities.canRecordResults").value(false))
            .andExpect(jsonPath("$.capabilities.canManagePreferences").value(false))
            .andExpect(jsonPath("$.capabilities.canExport").value(true))

        mockMvc.perform(
            delete("/api/admin/lms/courses/read-only-probe").header("Authorization", authorization),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("FORBIDDEN"))

        mockMvc.perform(
            delete("/api/admin/ai-exam/questions/read-only-probe").header("Authorization", authorization),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("FORBIDDEN"))

        mockMvc.perform(
            post("/api/admin/training/programs")
                .header("Authorization", authorization)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Read-only probe","allowedFormats":["online"]}"""),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("FORBIDDEN"))
    }

    @Test
    fun `brand manager не получает доступ к учебному API`() {
        val token = adminToken(AdminRole.BRAND_MANAGER)

        mockMvc.perform(
            get("/api/admin/lms/courses").header("Authorization", "Bearer $token"),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("FORBIDDEN"))
    }

    private fun adminToken(role: AdminRole): String = jwtService.issueAccessToken(
        AdminUserEntity(
            email = "${role.name.lowercase()}@example.test",
            name = "Role contract test",
            company = "ePharm",
        ).also { it.role = role },
    )
}
