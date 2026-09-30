package kz.epharm.merchtasks

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kz.epharm.fulfillment.service.FulfillmentService
import kz.epharm.pharmacies.entity.ChainEntity
import kz.epharm.pharmacies.entity.PharmacyEntity
import kz.epharm.pharmacies.entity.PharmacyGroup
import kz.epharm.pharmacies.repository.ChainRepository
import kz.epharm.pharmacies.repository.PharmacyRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
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

/** HTTP-level isolation: a broken optional CRM ACK must not take down core POSM paths. */
@SpringBootTest(properties = [
    "app.merch-tasks.enabled=true",
    "app.merch-tasks.integration-key=test-internal-key",
    "app.merch-tasks.timeout-ms=250",
    "app.fulfillment.enabled=true",
    "app.fulfillment.shared-secret=test-only-integration-secret-1234567890",
])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Transactional
class MerchTaskBridgeHttpIntegrationTest {
    companion object {
        private const val DISPATCH_ID = "123e4567-e89b-42d3-a456-426614174000"
        private const val DELIVERY_TOKEN = "test-opaque-delivery-token-00000001"

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("epharm_test").withUsername("epharm").withPassword("epharm_test")
            .apply { start() }

        private val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                exchange.requestBody.close()
                exchange.sendResponseHeaders(400, -1)
                exchange.close()
            }
            start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun props(reg: DynamicPropertyRegistry) {
            reg.add("spring.datasource.url") { postgres.jdbcUrl }
            reg.add("spring.datasource.username") { postgres.username }
            reg.add("spring.datasource.password") { postgres.password }
            reg.add("app.merch-tasks.base-url") { "http://127.0.0.1:${upstream.address.port}" }
        }
    }

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var chains: ChainRepository
    @Autowired private lateinit var pharmacies: PharmacyRepository
    @Autowired private lateinit var fulfillment: FulfillmentService

    @Test
    fun `CRM ACK failure is HTTP 200 unavailable and recommendation plus orders remain available`() {
        chains.save(ChainEntity(id = "ch_merch_bridge", name = "Test", color = "#16C97A", points = 1)
            .also { it.group = PharmacyGroup.pilot })
        pharmacies.saveAndFlush(PharmacyEntity(
            id = "ph_merch_bridge", name = "Test pharmacy", chainId = "ch_merch_bridge",
            chainName = "Test", city = "Almaty", district = "", addr = "",
        ).also { it.group = PharmacyGroup.pilot })
        val device = fulfillment.provisionDevice("POS-MERCH-BRIDGE", "ph_merch_bridge", "test")

        mockMvc.perform(post("/api/posm/tasks/shown")
            .header("X-Posm-Key", device.token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"dispatchId":"$DISPATCH_ID","pharmacyId":"ph_merch_bridge","deviceId":"POS-MERCH-BRIDGE","deliveryToken":"$DELIVERY_TOKEN"}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.available").value(false))
            .andExpect(jsonPath("$.accepted").value(false))

        mockMvc.perform(post("/api/posm/recommend")
            .header("X-Posm-Key", device.token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"pharmacyId":"ph_merch_bridge","sessionId":"receipt-1","cart":[]}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.recommendations").isArray)

        mockMvc.perform(get("/api/posm/fulfillment/orders")
            .header("X-Fulfillment-Device", device.token))
            .andExpect(status().isOk)
    }
}
