package kz.epharm.appupdate

import com.fasterxml.jackson.databind.ObjectMapper
import kz.epharm.appupdate.dto.AppVersionDto
import kz.epharm.appupdate.dto.RegisterReleaseRequest
import kz.epharm.appupdate.repository.AppReleaseRepository
import kz.epharm.appupdate.service.AppReleaseService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
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
class AppReleaseIntegrationTest {

    companion object {
        private const val POSM_KEY = "dev-posm-key"

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
    @Autowired private lateinit var appReleaseService: AppReleaseService
    @Autowired private lateinit var appReleaseRepository: AppReleaseRepository

    @BeforeEach
    fun clean() = appReleaseRepository.deleteAll()

    @Test
    fun `нет релиза → current=false (касса не обновляется)`() {
        val resp = getVersion()
        assertFalse(resp.current)
        assertEquals("", resp.version)
    }

    @Test
    fun `зарегистрированный релиз отдаётся кассе`() {
        appReleaseService.register(
            RegisterReleaseRequest(version = "1.2.0", url = "http://minio/app/epharm-1.2.0.zip", sha256 = "abc"),
        )
        val resp = getVersion()
        assertTrue(resp.current)
        assertEquals("1.2.0", resp.version)
        assertEquals("http://minio/app/epharm-1.2.0.zip", resp.url)
        assertEquals("abc", resp.sha256)
    }

    @Test
    fun `register делает текущим ровно один релиз на платформу`() {
        appReleaseService.register(RegisterReleaseRequest(version = "1.0.0", url = "http://minio/v1.zip"))
        appReleaseService.register(RegisterReleaseRequest(version = "1.1.0", url = "http://minio/v11.zip"))
        // текущий — последний; ровно один current
        val current = appReleaseRepository.findAllByPlatformAndIsCurrentTrue("win-x64")
        assertEquals(1, current.size)
        assertEquals("1.1.0", current[0].version)
        assertEquals("1.1.0", getVersion().version)
    }

    @Test
    fun `без device-key → 401`() {
        mockMvc.perform(get("/api/posm/app/version")).andExpect(status().isUnauthorized)
    }

    private fun getVersion(): AppVersionDto {
        val body = mockMvc.perform(get("/api/posm/app/version").header("X-Posm-Key", POSM_KEY))
            .andExpect(status().isOk)
            .andReturn().response.getContentAsString(Charsets.UTF_8)
        return objectMapper.readValue(body, AppVersionDto::class.java)
    }
}
