package kz.epharm.banners

import com.fasterxml.jackson.databind.ObjectMapper
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.domain.AdminUserStatus
import kz.epharm.auth.dto.LoginRequest
import kz.epharm.auth.dto.LoginResponse
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.banners.entity.BannerEntity
import kz.epharm.banners.entity.BannerStatus
import kz.epharm.banners.repository.BannerRepository
import kz.epharm.banners.service.BannerService
import org.junit.jupiter.api.Assertions.assertEquals
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
class BannerIntegrationTest {

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
    @Autowired private lateinit var bannerRepository: BannerRepository
    @Autowired private lateinit var bannerService: BannerService
    @Autowired private lateinit var adminUserRepository: AdminUserRepository
    @Autowired private lateinit var passwordEncoder: PasswordEncoder

    private lateinit var bearer: String

    @BeforeEach
    fun seed() {
        bannerRepository.deleteAll()
        adminUserRepository.deleteAll()

        // Активные в обратном порядке позиций — проверим, что лента сортирует по position.
        bannerRepository.save(
            BannerEntity(id = "bn_b", title = "Второй", subtitle = "сабтайтл-2", imageUrl = "http://x/b.jpg", detailMd = "**деталь B**", position = 1)
                .also { it.status = BannerStatus.active },
        )
        bannerRepository.save(
            BannerEntity(id = "bn_a", title = "Первый", subtitle = "сабтайтл-1", imageUrl = "http://x/a.jpg", detailMd = "**деталь A**", position = 0)
                .also { it.status = BannerStatus.active },
        )
        // Черновик — НЕ должен попасть в мобильную ленту.
        bannerRepository.save(
            BannerEntity(id = "bn_draft", title = "Черновик", imageUrl = "http://x/d.jpg", position = 5)
                .also { it.status = BannerStatus.draft },
        )

        adminUserRepository.save(
            AdminUserEntity(email = "damir@jadran.com", passwordHash = passwordEncoder.encode("damir2026"),
                name = "Дамир", company = "Jadran").also { it.role = AdminRole.BRAND_MANAGER; it.status = AdminUserStatus.ACTIVE },
        )
        bearer = "Bearer " + login().tokens.accessToken
    }

    // ── Админка ───────────────────────────────────────────────────────────

    @Test
    fun `GET banners → 3 (все статусы)`() {
        mockMvc.perform(get("/api/admin/banners").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(3))
    }

    @Test
    fun `GET без Bearer → 401`() {
        mockMvc.perform(get("/api/admin/banners"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `POST banner → 201 draft по умолчанию, список содержит`() {
        mockMvc.perform(
            post("/api/admin/banners").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"Новый баннер","subtitle":"саб","detailMd":"# Привет"}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.title").value("Новый баннер"))
            .andExpect(jsonPath("$.subtitle").value("саб"))
            .andExpect(jsonPath("$.detailMd").value("# Привет"))
            .andExpect(jsonPath("$.status").value("draft"))

        mockMvc.perform(get("/api/admin/banners").header("Authorization", bearer))
            .andExpect(jsonPath("$.length()").value(4))
    }

    @Test
    fun `PATCH banner → меняет поля и статус`() {
        mockMvc.perform(
            patch("/api/admin/banners/bn_draft").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"Обновлён","status":"active","position":9}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.title").value("Обновлён"))
            .andExpect(jsonPath("$.status").value("active"))
            .andExpect(jsonPath("$.position").value(9))
    }

    @Test
    fun `DELETE banner → 204`() {
        mockMvc.perform(delete("/api/admin/banners/bn_a").header("Authorization", bearer))
            .andExpect(status().isNoContent)
        assertEquals(false, bannerRepository.existsById("bn_a"))
    }

    @Test
    fun `POST image (image content-type) → 200 imageUrl`() {
        val file = MockMultipartFile("file", "promo.jpg", "image/jpeg", byteArrayOf(1, 2, 3, 4))
        mockMvc.perform(
            multipart("/api/admin/banners/image")
                .file(file)
                .header("Authorization", bearer),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.imageUrl").value(org.hamcrest.Matchers.startsWith("http")))
    }

    @Test
    fun `POST image с не-картинкой → 400`() {
        val file = MockMultipartFile("file", "doc.pdf", "application/pdf", byteArrayOf(1, 2))
        mockMvc.perform(
            multipart("/api/admin/banners/image")
                .file(file)
                .header("Authorization", bearer),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
    }

    // ── Мобильная лента (через сервис — permitAll-роутинг проверяется отдельно) ──

    @Test
    fun `activeFeed → только active, в порядке position`() {
        val feed = bannerService.activeFeed()
        assertEquals(2, feed.size)
        assertEquals(listOf("bn_a", "bn_b"), feed.map { it.id }) // position 0, затем 1
        // Черновик исключён.
        assertEquals(false, feed.any { it.id == "bn_draft" })
        // Поля для детального экрана прокинуты.
        assertEquals("сабтайтл-1", feed[0].subtitle)
        assertEquals("**деталь A**", feed[0].detailMd)
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
