package kz.epharm.screens

import com.fasterxml.jackson.databind.ObjectMapper
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.domain.AdminUserStatus
import kz.epharm.auth.dto.LoginRequest
import kz.epharm.auth.dto.LoginResponse
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.screens.entity.PlaylistEntity
import kz.epharm.screens.entity.PlaylistStatus
import kz.epharm.screens.entity.SlideEntity
import kz.epharm.screens.entity.SlideKind
import kz.epharm.screens.repository.PlaylistRepository
import kz.epharm.screens.repository.SlideRepository
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
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
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
class ScreensIntegrationTest {

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
    @Autowired private lateinit var playlistRepository: PlaylistRepository
    @Autowired private lateinit var slideRepository: SlideRepository
    @Autowired private lateinit var adminUserRepository: AdminUserRepository
    @Autowired private lateinit var passwordEncoder: PasswordEncoder

    private lateinit var bearer: String

    @BeforeEach
    fun seed() {
        slideRepository.deleteAll()
        playlistRepository.deleteAll()
        adminUserRepository.deleteAll()

        playlistRepository.save(PlaylistEntity(id = "pl_act", name = "Активный", slidesCount = 2, durationSec = 40, pharmacies = 10).also { it.status = PlaylistStatus.active })
        playlistRepository.save(PlaylistEntity(id = "pl_dr", name = "Черновик").also { it.status = PlaylistStatus.draft })
        slideRepository.save(SlideEntity(id = "sl_v", title = "Видео", durationSec = 20, mediaUrl = "s3://x/v.mp4", playlistId = "pl_act").also { it.kind = SlideKind.video })
        slideRepository.save(SlideEntity(id = "sl_lib", title = "Библиотечный", durationSec = 15, mediaUrl = "s3://x/i.jpg", playlistId = null).also { it.kind = SlideKind.image })

        adminUserRepository.save(
            AdminUserEntity(email = "damir@jadran.com", passwordHash = passwordEncoder.encode("damir2026"),
                name = "Дамир", company = "Jadran").also { it.role = AdminRole.BRAND_MANAGER; it.status = AdminUserStatus.ACTIVE },
        )
        bearer = "Bearer " + login().tokens.accessToken
    }

    @Test
    fun `GET playlists → 2`() {
        mockMvc.perform(get("/api/admin/screens/playlists").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
    }

    @Test
    fun `GET playlists filter status=active`() {
        mockMvc.perform(get("/api/admin/screens/playlists?status=active").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value("pl_act"))
            .andExpect(jsonPath("$[0].slidesCount").value(2))
    }

    @Test
    fun `GET slides → 2 (включая библиотечный без плейлиста)`() {
        mockMvc.perform(get("/api/admin/screens/slides").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
    }

    @Test
    fun `GET без Bearer → 401`() {
        mockMvc.perform(get("/api/admin/screens/playlists"))
            .andExpect(status().isUnauthorized)
    }

    // ── CRUD управления экранами (ТЗ §3.3) ────────────────────────────────

    @Test
    fun `POST playlist → 201 draft`() {
        mockMvc.perform(
            post("/api/admin/screens/playlists").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Новый плейлист"}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.name").value("Новый плейлист"))
            .andExpect(jsonPath("$.status").value("draft"))
    }

    @Test
    fun `PATCH playlist → активируем`() {
        mockMvc.perform(
            patch("/api/admin/screens/playlists/pl_dr").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"status":"active"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("active"))
    }

    @Test
    fun `DELETE playlist → 204, слайды откреплены`() {
        mockMvc.perform(delete("/api/admin/screens/playlists/pl_act").header("Authorization", bearer))
            .andExpect(status().isNoContent)
        // sl_v был привязан к pl_act → теперь playlistId == null (остался в библиотеке)
        val slide = slideRepository.findById("sl_v").get()
        assert(slide.playlistId == null) { "слайд должен быть откреплён" }
    }

    @Test
    fun `POST slide upload (video) → 201, kind=video, mediaUrl`() {
        val file = MockMultipartFile("file", "promo.mp4", "video/mp4", byteArrayOf(1, 2, 3, 4))
        mockMvc.perform(
            multipart("/api/admin/screens/slides")
                .file(file)
                .param("title", "Промо ролик")
                .param("durationSec", "20")
                .header("Authorization", bearer),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.kind").value("video"))
            .andExpect(jsonPath("$.title").value("Промо ролик"))
            .andExpect(jsonPath("$.durationSec").value(20))
            .andExpect(jsonPath("$.mediaUrl").value(org.hamcrest.Matchers.startsWith("http")))
            .andExpect(jsonPath("$.playlistId").doesNotExist())
    }

    @Test
    fun `POST slide upload с не-медиа content-type → 400`() {
        val file = MockMultipartFile("file", "doc.pdf", "application/pdf", byteArrayOf(1, 2))
        mockMvc.perform(
            multipart("/api/admin/screens/slides")
                .file(file)
                .param("title", "PDF")
                .header("Authorization", bearer),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
    }

    @Test
    fun `POST assign slide → плейлист пересчитан`() {
        // sl_lib (durationSec=15) в библиотеке → привязываем к pl_dr (пустому)
        mockMvc.perform(
            post("/api/admin/screens/slides/sl_lib/assign").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"playlistId":"pl_dr","position":0}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.playlistId").value("pl_dr"))

        mockMvc.perform(get("/api/admin/screens/playlists?status=draft").header("Authorization", bearer))
            .andExpect(jsonPath("$[0].slidesCount").value(1))
            .andExpect(jsonPath("$[0].durationSec").value(15))
    }

    @Test
    fun `DELETE slide → 204`() {
        mockMvc.perform(delete("/api/admin/screens/slides/sl_lib").header("Authorization", bearer))
            .andExpect(status().isNoContent)
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
