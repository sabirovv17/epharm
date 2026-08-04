package kz.epharm.lms

import com.fasterxml.jackson.databind.ObjectMapper
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.domain.AdminUserStatus
import kz.epharm.auth.dto.LoginRequest
import kz.epharm.auth.dto.LoginResponse
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.lms.entity.CourseEntity
import kz.epharm.lms.entity.CourseStatus
import kz.epharm.lms.repository.CourseRepository
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
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
class LmsIntegrationTest {

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
    @Autowired private lateinit var courseRepository: CourseRepository
    @Autowired private lateinit var adminUserRepository: AdminUserRepository
    @Autowired private lateinit var passwordEncoder: PasswordEncoder

    private lateinit var bearer: String

    @BeforeEach
    fun seed() {
        courseRepository.deleteAll()
        adminUserRepository.deleteAll()

        courseRepository.save(CourseEntity(id = "crs_pub", title = "Опубликованный", category = "A", lessons = 5, enrolled = 100, completed = 40).also { it.status = CourseStatus.published })
        courseRepository.save(CourseEntity(id = "crs_draft", title = "Черновик", category = "B").also { it.status = CourseStatus.draft })

        adminUserRepository.save(
            AdminUserEntity(email = "damir@jadran.com", passwordHash = passwordEncoder.encode("damir2026"),
                name = "Дамир", company = "Jadran").also { it.role = AdminRole.HQ_HEAD; it.status = AdminUserStatus.ACTIVE },
        )
        bearer = "Bearer " + login().tokens.accessToken
    }

    @Test
    fun `GET courses → 2`() {
        mockMvc.perform(get("/api/admin/lms/courses").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
    }

    @Test
    fun `GET courses filter status=published`() {
        mockMvc.perform(get("/api/admin/lms/courses?status=published").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value("crs_pub"))
            .andExpect(jsonPath("$[0].enrolled").value(100))
    }

    @Test
    fun `GET unknown course → 404`() {
        mockMvc.perform(get("/api/admin/lms/courses/nope").header("Authorization", bearer))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
    }

    @Test
    fun `POST create course → draft по умолчанию`() {
        val body = """{"title":"Новый курс","lessons":3,"durationMin":20,"bonus":500}"""
        mockMvc.perform(
            post("/api/admin/lms/courses").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(body),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.title").value("Новый курс"))
            .andExpect(jsonPath("$.status").value("draft"))
            .andExpect(jsonPath("$.lessons").value(3))
    }

    @Test
    fun `POST create без title → 400`() {
        mockMvc.perform(
            post("/api/admin/lms/courses").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content("""{"title":""}"""),
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `GET без Bearer → 401`() {
        mockMvc.perform(get("/api/admin/lms/courses"))
            .andExpect(status().isUnauthorized)
    }

    // ── CRUD (Блок 2) ────────────────────────────────────────────────────

    @Test
    fun `PATCH course обновляет поля и статус`() {
        mockMvc.perform(
            patch("/api/admin/lms/courses/crs_draft")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"Опубликован","status":"published","bonus":500}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.title").value("Опубликован"))
            .andExpect(jsonPath("$.status").value("published"))
            .andExpect(jsonPath("$.bonus").value(500))
            .andExpect(jsonPath("$.category").value("B")) // не передан → прежний
    }

    @Test
    fun `PATCH missing course → 404`() {
        mockMvc.perform(
            patch("/api/admin/lms/courses/crs_unknown")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"bonus":1}"""),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
    }

    @Test
    fun `DELETE course archives it without deleting history`() {
        mockMvc.perform(delete("/api/admin/lms/courses/crs_draft").header("Authorization", bearer))
            .andExpect(status().isNoContent)
        mockMvc.perform(get("/api/admin/lms/courses/crs_draft").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("archived"))
        mockMvc.perform(get("/api/admin/lms/courses?status=archived").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].id").value("crs_draft"))
    }

    @Test
    fun `PATCH без Bearer → 401`() {
        mockMvc.perform(
            patch("/api/admin/lms/courses/crs_draft")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"bonus":1}"""),
        )
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
