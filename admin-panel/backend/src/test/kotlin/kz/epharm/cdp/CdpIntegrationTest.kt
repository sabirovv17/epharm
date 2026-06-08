package kz.epharm.cdp

import com.fasterxml.jackson.databind.ObjectMapper
import kz.epharm.cdp.dto.CdpLookupRequest
import kz.epharm.cdp.dto.CdpLookupResponse
import kz.epharm.cdp.dto.CdpProfileDto
import kz.epharm.cdp.dto.CdpRegisterRequest
import kz.epharm.cdp.repository.CdpProfileRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
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
class CdpIntegrationTest {

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
    @Autowired private lateinit var repository: CdpProfileRepository

    @BeforeEach
    fun clean() = repository.deleteAll()

    @Test
    fun `lookup неизвестного телефона → found=false`() {
        val resp = lookup("+77001112233")
        assertFalse(resp.found)
        assertEquals(null, resp.profileId)
    }

    @Test
    fun `register создаёт профиль, повторный register идемпотентен`() {
        val first = register(CdpRegisterRequest(phone = "+7 700 111 22 33", name = "Иван"))
        assertTrue(first.isNew)
        assertEquals("Иван", first.name)
        assertEquals("Bronze", first.tier)

        // повторно тот же телефон (в другом формате) → не дублируем
        val second = register(CdpRegisterRequest(phone = "77001112233"))
        assertFalse(second.isNew)
        assertEquals(first.id, second.id)
        assertEquals(1, repository.count())
    }

    @Test
    fun `после register lookup находит профиль (телефон нормализуется)`() {
        register(CdpRegisterRequest(phone = "+7 700 444 55 66", name = "Айгуль"))
        val resp = lookup("+77004445566")
        assertTrue(resp.found)
        assertEquals("Айгуль", resp.name)
        assertEquals("Bronze", resp.tier)
    }

    @Test
    fun `без device-key → 401`() {
        mockMvc.perform(
            post("/api/posm/cdp/lookup").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(CdpLookupRequest("+77000000000"))),
        ).andExpect(status().isUnauthorized)
    }

    private fun lookup(phone: String): CdpLookupResponse {
        val body = mockMvc.perform(
            post("/api/posm/cdp/lookup").header("X-Posm-Key", POSM_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(CdpLookupRequest(phone))),
        ).andExpect(status().isOk).andReturn().response.getContentAsString(Charsets.UTF_8)
        return objectMapper.readValue(body, CdpLookupResponse::class.java)
    }

    private fun register(req: CdpRegisterRequest): CdpProfileDto {
        val body = mockMvc.perform(
            post("/api/posm/cdp/register").header("X-Posm-Key", POSM_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)),
        ).andExpect(status().isOk).andReturn().response.getContentAsString(Charsets.UTF_8)
        return objectMapper.readValue(body, CdpProfileDto::class.java)
    }
}
