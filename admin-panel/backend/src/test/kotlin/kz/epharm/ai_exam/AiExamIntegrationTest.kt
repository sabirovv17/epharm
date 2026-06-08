package kz.epharm.ai_exam

import com.fasterxml.jackson.databind.ObjectMapper
import kz.epharm.ai_exam.entity.ExamQuestionEntity
import kz.epharm.ai_exam.entity.ExamQuestionKind
import kz.epharm.ai_exam.repository.ExamQuestionRepository
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.domain.AdminUserStatus
import kz.epharm.auth.dto.LoginRequest
import kz.epharm.auth.dto.LoginResponse
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
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
class AiExamIntegrationTest {

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
    @Autowired private lateinit var questionRepository: ExamQuestionRepository
    @Autowired private lateinit var adminUserRepository: AdminUserRepository
    @Autowired private lateinit var passwordEncoder: PasswordEncoder

    private lateinit var bearer: String

    @BeforeEach
    fun seed() {
        questionRepository.deleteAll()
        adminUserRepository.deleteAll()

        questionRepository.save(ExamQuestionEntity(id = "q_fact", prompt = "Факт?", category = "A", keywords = listOf("один", "два"), difficulty = 1).also { it.kind = ExamQuestionKind.factual })
        questionRepository.save(ExamQuestionEntity(id = "q_scen", prompt = "Сценарий?", category = "B", keywords = listOf("три"), difficulty = 3).also { it.kind = ExamQuestionKind.scenario })

        adminUserRepository.save(
            AdminUserEntity(email = "damir@jadran.com", passwordHash = passwordEncoder.encode("damir2026"),
                name = "Дамир", company = "Jadran").also { it.role = AdminRole.BRAND_MANAGER; it.status = AdminUserStatus.ACTIVE },
        )
        bearer = "Bearer " + login().tokens.accessToken
    }

    @Test
    fun `GET questions → 2`() {
        mockMvc.perform(get("/api/admin/ai-exam/questions").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
    }

    @Test
    fun `GET questions filter kind=scenario`() {
        mockMvc.perform(get("/api/admin/ai-exam/questions?kind=scenario").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value("q_scen"))
    }

    @Test
    fun `POST create question — keywords сохраняются jsonb, blank отфильтрованы`() {
        val body = """{"prompt":"Новый вопрос?","kind":"comparative","category":"C","keywords":["alpha","  ","beta"],"difficulty":2}"""
        mockMvc.perform(
            post("/api/admin/ai-exam/questions").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(body),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.prompt").value("Новый вопрос?"))
            .andExpect(jsonPath("$.kind").value("comparative"))
            .andExpect(jsonPath("$.keywords.length()").value(2))
            .andExpect(jsonPath("$.keywords[0]").value("alpha"))
    }

    @Test
    fun `POST create без prompt → 400`() {
        mockMvc.perform(
            post("/api/admin/ai-exam/questions").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content("""{"prompt":""}"""),
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `POST create difficulty вне 1-5 → 400`() {
        mockMvc.perform(
            post("/api/admin/ai-exam/questions").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content("""{"prompt":"q","difficulty":9}"""),
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `GET без Bearer → 401`() {
        mockMvc.perform(get("/api/admin/ai-exam/questions"))
            .andExpect(status().isUnauthorized)
    }

    // ── CRUD (Блок 2) ────────────────────────────────────────────────────

    @Test
    fun `PATCH question обновляет prompt и keywords (blank отфильтрованы)`() {
        mockMvc.perform(
            patch("/api/admin/ai-exam/questions/q_fact")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"prompt":"Изменён?","keywords":["новый","  ","ключ"],"difficulty":4}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.prompt").value("Изменён?"))
            .andExpect(jsonPath("$.keywords.length()").value(2))
            .andExpect(jsonPath("$.difficulty").value(4))
            .andExpect(jsonPath("$.category").value("A")) // не передан → прежний
    }

    @Test
    fun `PATCH missing question → 404`() {
        mockMvc.perform(
            patch("/api/admin/ai-exam/questions/q_unknown")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"difficulty":2}"""),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
    }

    @Test
    fun `PATCH difficulty вне 1-5 → 400`() {
        mockMvc.perform(
            patch("/api/admin/ai-exam/questions/q_fact")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"difficulty":0}"""),
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `DELETE question → 204 и список уменьшается`() {
        mockMvc.perform(delete("/api/admin/ai-exam/questions/q_fact").header("Authorization", bearer))
            .andExpect(status().isNoContent)
        mockMvc.perform(get("/api/admin/ai-exam/questions").header("Authorization", bearer))
            .andExpect(jsonPath("$.length()").value(1))
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
