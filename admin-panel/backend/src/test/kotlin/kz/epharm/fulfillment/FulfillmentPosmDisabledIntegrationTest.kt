package kz.epharm.fulfillment

import com.fasterxml.jackson.databind.ObjectMapper
import kz.epharm.fulfillment.dto.FulfillmentPharmacyLinkRequest
import kz.epharm.fulfillment.dto.FulfillmentActionRequest
import kz.epharm.fulfillment.dto.StorefrontOrderCreatedRequest
import kz.epharm.fulfillment.dto.StorefrontOrderLineRequest
import kz.epharm.fulfillment.service.FulfillmentService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@SpringBootTest(properties = ["app.fulfillment.enabled=true", "app.fulfillment.posm-enabled=false",
    "app.fulfillment.device-registration-enabled=true", "app.fulfillment.shared-secret=synthetic-fulfillment-disabled-32-bytes"])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class FulfillmentPosmDisabledIntegrationTest {
    companion object {
        private const val SECRET = "synthetic-fulfillment-disabled-32-bytes"
        private const val PHARMACY = "fulfillment-disabled-pharmacy"
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("fulfillment_disabled").withUsername("test").withPassword("synthetic-only").apply { start() }
        @JvmStatic @DynamicPropertySource fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var fulfillment: FulfillmentService
    private lateinit var token: String

    @BeforeEach fun seed() {
        // This database is disposable and owned solely by this test class.
        jdbc.execute("TRUNCATE fulfillment_audit,fulfillment_order_updates,fulfillment_order_lines,fulfillment_devices,fulfillment_orders,fulfillment_pharmacy_links,pharmacies,chains CASCADE")
        jdbc.update("""insert into chains(id,name,color,"group",points) values('disabled-chain','Test','#000000','pilot',0)""")
        jdbc.update("""insert into pharmacies(id,name,chain_id,chain_name,city,district,addr,"group")
            values(?,'Test','disabled-chain','Test','Test','','','pilot')""", PHARMACY)
        fulfillment.saveLink(FulfillmentPharmacyLinkRequest("external-disabled-test", PHARMACY), "test-admin")
        token = fulfillment.provisionDevice("POSM-DISABLED-TEST", PHARMACY, "test-admin").token
    }

    private fun order() = StorefrontOrderCreatedRequest("11111111-1111-4111-8111-111111111111", "disabled-order", "10001",
        "external-disabled-test", Instant.parse("2026-11-01T00:00:00Z"), BigDecimal(1590), "KZT", "pickup", "cash", "pending",
        pickupCode = "123456", lines = listOf(StorefrontOrderLineRequest("product", "sku", "Test", 1, BigDecimal(1590))))

    @Test fun `disabled POSM denies list get actions registration while preserving orders and device credentials`() {
        val order = order()
        fulfillment.ingest(json.writeValueAsBytes(order), order)
        val before = jdbc.queryForMap("select secret_sha256,active,registered_at,revoked_at from fulfillment_devices where device_id='POSM-DISABLED-TEST'")
        for (path in listOf("/api/posm/fulfillment/orders", "/api/posm/fulfillment/orders/disabled-order")) {
            mvc.perform(get(path).header("X-Fulfillment-Device", token)).andExpect(status().isServiceUnavailable)
        }
        mvc.perform(post("/api/posm/fulfillment/orders/disabled-order/actions").header("X-Fulfillment-Device", token)
            .contentType(MediaType.APPLICATION_JSON).content("""{"action":"assemble","expectedVersion":1}"""))
            .andExpect(status().isServiceUnavailable)
        mvc.perform(post("/api/posm/fulfillment/devices/register").header("X-Posm-Key", "dev-posm-key")
            .contentType(MediaType.APPLICATION_JSON).content("""{"deviceId":"not-enrolled","pharmacyId":"$PHARMACY"}"""))
            .andExpect(status().isServiceUnavailable)
        assertThat(fulfillment.getForAdmin("disabled-order").status).isEqualTo("submitted")
        assertThat(fulfillment.getForAdmin("disabled-order").version).isEqualTo(1)
        assertThat(jdbc.queryForMap("select secret_sha256,active,registered_at,revoked_at from fulfillment_devices where device_id='POSM-DISABLED-TEST'"))
            .isEqualTo(before)
        assertThat(jdbc.queryForObject("select count(*) from fulfillment_devices", Long::class.java)).isEqualTo(1)
    }

    @Test fun `capabilities require an individual token and expose only disabled state`() {
        val response = mvc.perform(get("/api/posm/fulfillment/capabilities").header("X-Fulfillment-Device", token))
            .andExpect(status().isOk).andExpect(jsonPath("$.enabled").value(false)).andReturn().response
        assertThat(json.readTree(response.contentAsByteArray).fieldNames().asSequence().toList()).containsExactly("enabled")
        mvc.perform(get("/api/posm/fulfillment/capabilities")).andExpect(status().isUnauthorized)
        mvc.perform(get("/api/posm/fulfillment/capabilities").header("X-Fulfillment-Device", "dev-posm-key"))
            .andExpect(status().isUnauthorized)
        mvc.perform(get("/api/posm/fulfillment/capabilities").header("X-Fulfillment-Device", "x".repeat(43)))
            .andExpect(status().isUnauthorized)
    }

    @Test @WithMockUser(username = "11111111-1111-4111-8111-111111111111", roles = ["HQ_HEAD"])
    fun `storefront ingress feed admin and main POSM authentication remain available`() {
        val body = json.writeValueAsString(order())
        signed("POST", "/api/integrations/storefront/orders", body).andExpect(status().isOk)
        fulfillment.actAsAdmin("disabled-order", FulfillmentActionRequest("assemble", 1), "test-admin")
        signed("GET", "/api/integrations/storefront/order-updates?after=0&limit=10", "")
            .andExpect(status().isOk).andExpect(jsonPath("$.updates[0].orderId").value("disabled-order"))
        mvc.perform(get("/api/admin/fulfillment/orders")).andExpect(status().isOk)
        mvc.perform(get("/api/posm/playlists/active").header("X-Posm-Key", token).param("pharmacyId", PHARMACY))
            .andExpect(status().isOk)
        mvc.perform(post("/api/posm/recommend").header("X-Posm-Key", token).contentType(MediaType.APPLICATION_JSON)
            .content("""{"pharmacyId":"$PHARMACY","sessionId":"synthetic","cart":[]}"""))
            .andExpect(status().isOk)
    }

    private fun signed(method: String, path: String, body: String): org.springframework.test.web.servlet.ResultActions {
        val timestamp = Instant.now().epochSecond
        val hash = MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).joinToString("") { "%02x".format(it) }
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(SECRET.toByteArray(), "HmacSHA256")) }
        val signature = mac.doFinal("$timestamp\n$method\n$path\n$hash".toByteArray()).joinToString("") { "%02x".format(it) }
        val request = if (method == "GET") get(path) else post(path).contentType(MediaType.APPLICATION_JSON).content(body)
        return mvc.perform(request.header("X-Fulfillment-Timestamp", timestamp).header("X-Fulfillment-Signature", signature))
    }
}
