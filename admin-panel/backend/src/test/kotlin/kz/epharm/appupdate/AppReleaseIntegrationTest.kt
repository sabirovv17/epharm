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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.annotation.Propagation
import org.springframework.jdbc.core.JdbcTemplate
import org.junit.jupiter.api.Assertions.assertThrows
import org.springframework.dao.DataIntegrityViolationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
            reg.add("app.posm.legacy-device-key-enabled") { "false" }
        }
    }

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var appReleaseService: AppReleaseService
    @Autowired private lateinit var appReleaseRepository: AppReleaseRepository
    @Autowired private lateinit var jdbc: JdbcTemplate

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
            release("1.2.0", "https://cdn.example/app/epharm-1.2.0.zip"),
        )
        val resp = getVersion()
        assertTrue(resp.current)
        assertEquals("1.2.0", resp.version)
        assertEquals("https://cdn.example/app/epharm-1.2.0.zip", resp.url)
        assertEquals("a".repeat(64), resp.sha256)
        assertEquals("AQID", resp.manifestSignature)
    }

    @Test
    fun `register делает текущим ровно один релиз на платформу`() {
        appReleaseService.register(release("1.0.0", "https://cdn.example/v1.zip"))
        appReleaseService.register(release("1.1.0", "https://cdn.example/v11.zip"))
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

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `concurrent first publications serialize and leave exactly one current release`() {
        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = listOf("1.0.0", "1.0.1").map { version -> pool.submit<String> {
                ready.countDown(); check(go.await(5, TimeUnit.SECONDS))
                appReleaseService.register(release(version, "https://cdn.example/$version.zip")).id
            } }
            assertTrue(ready.await(5, TimeUnit.SECONDS)); go.countDown()
            assertEquals(2, results.map { it.get(15, TimeUnit.SECONDS) }.toSet().size)
            assertEquals(2L, appReleaseRepository.count())
            assertEquals(1, appReleaseRepository.findAllByPlatformAndIsCurrentTrue("win-x64").size)
        } finally { go.countDown(); pool.shutdownNow() }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `database rejects a second current release even outside publication service`() {
        appReleaseService.register(release("1.0.0", "https://cdn.example/v1.zip"))
        assertThrows(DataIntegrityViolationException::class.java) {
            jdbc.update("""INSERT INTO app_releases(id,platform,version,url,sha256,manifest_signature,is_current)
                VALUES ('direct-duplicate','win-x64','2.0.0','https://cdn.example/v2.zip',?,'AQID',true)""", "b".repeat(64))
        }
        assertEquals("1.0.0", appReleaseService.currentFor("win-x64").version)
    }

    @Test
    fun `retired fleet key is accepted only by signed update manifest route`() {
        mockMvc.perform(
            get("/api/posm/app/version")
                .header("X-Posm-Key", POSM_KEY)
                .param("deviceId", "legacy-register"),
        ).andExpect(status().isOk)

        mockMvc.perform(
            post("/api/posm/heartbeat")
                .header("X-Posm-Key", POSM_KEY)
                .param("deviceId", "legacy-register")
                .param("pharmacyId", "ph_legacy"),
        ).andExpect(status().isUnauthorized)
    }

    private fun getVersion(): AppVersionDto {
        val body = mockMvc.perform(get("/api/posm/app/version").header("X-Posm-Key", POSM_KEY))
            .andExpect(status().isOk)
            .andReturn().response.getContentAsString(Charsets.UTF_8)
        return objectMapper.readValue(body, AppVersionDto::class.java)
    }

    private fun release(version: String, url: String) = RegisterReleaseRequest(
        version = version,
        url = url,
        sha256 = "a".repeat(64),
        manifestSignature = "AQID",
    )
}
