package kz.epharm.fulfillment

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kz.epharm.fulfillment.dto.FulfillmentActionRequest
import kz.epharm.fulfillment.dto.FulfillmentPharmacyLinkRequest
import kz.epharm.fulfillment.service.FulfillmentService
import kz.epharm.pharmacies.entity.ChainEntity
import kz.epharm.pharmacies.entity.PharmacyEntity
import kz.epharm.pharmacies.repository.ChainRepository
import kz.epharm.pharmacies.repository.PharmacyRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Transactional
class FulfillmentIntegrationTest {

    companion object {
        private const val SECRET = "test-fulfillment-secret-32-bytes-long"
        private const val POSM_KEY = "dev-posm-key"

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("epharm_test")
            .withUsername("epharm")
            .withPassword("epharm_test")
            .apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("app.fulfillment.enabled") { "true" }
            registry.add("app.fulfillment.shared-secret") { SECRET }
            registry.add("app.fulfillment.device-registration-enabled") { "true" }
        }
    }

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var service: FulfillmentService
    @Autowired private lateinit var pharmacyRepository: PharmacyRepository
    @Autowired private lateinit var chainRepository: ChainRepository

    @BeforeEach
    fun seed() {
        jdbc.update("DELETE FROM fulfillment_audit")
        jdbc.update("DELETE FROM fulfillment_order_updates")
        jdbc.update("DELETE FROM fulfillment_order_lines")
        jdbc.update("DELETE FROM fulfillment_devices")
        jdbc.update("DELETE FROM fulfillment_orders")
        jdbc.update("DELETE FROM fulfillment_pharmacy_links")
        pharmacyRepository.deleteAll()
        chainRepository.deleteAll()
        chainRepository.save(ChainEntity(id = "chain_f", name = "Fulfillment", color = "#16C97A"))
        pharmacyRepository.save(pharmacy("ph_1", "Аптека 1"))
        pharmacyRepository.save(pharmacy("ph_2", "Аптека 2"))
        pharmacyRepository.flush()
    }

    @Test
    fun `worker HMAC rejects missing stale and invalid signatures`() {
        val body = orderBody("order_auth", "event-auth", "ch:84")
        mockMvc.perform(
            post("/api/integrations/storefront/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        ).andExpect(status().isUnauthorized)

        performSignedPost(body, Instant.now().minusSeconds(301).epochSecond)
            .andExpect(status().isUnauthorized)

        mockMvc.perform(
            post("/api/integrations/storefront/orders")
                .header("X-Fulfillment-Timestamp", Instant.now().epochSecond)
                .header("X-Fulfillment-Signature", "0".repeat(64))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        ).andExpect(status().isUnauthorized)

        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment_orders", Long::class.java)).isZero()
    }

    @Test
    fun `strict ingest maps pharmacy and enforces exact-body idempotency`() {
        link("ch:84", "ph_1")
        val body = orderBody("order_1", "11111111-1111-4111-8111-111111111111", "ch:84")

        performSignedPost(body)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accepted").value(true))
            .andExpect(jsonPath("$.assigned").value(true))

        performSignedPost(body)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accepted").value(false))

        performSignedPost(body.replace("1590", "1591"))
            .andExpect(status().isConflict)

        val storedColumns = jdbc.queryForList(
            "SELECT column_name FROM information_schema.columns WHERE table_name = 'fulfillment_orders'",
            String::class.java,
        )
        assertThat(storedColumns).doesNotContain("pickup_code", "frozen_request")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment_orders", Long::class.java)).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment_order_lines", Long::class.java)).isEqualTo(1)
    }

    @Test
    fun `strict JSON contract rejects unknown customer fields`() {
        val body = orderBody("order_pii", "22222222-2222-4222-8222-222222222222", "ch:84")
            .dropLast(1) + ",\"phone\":\"+77000000000\"}"
        performSignedPost(body).andExpect(status().isBadRequest)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment_orders", Long::class.java)).isZero()
    }

    @Test
    fun `unit price may be absent but a negative price is rejected`() {
        link("ch:84", "ph_1")
        performSignedPost(
            orderBody(
                "order_null_price",
                "88888888-8888-4888-8888-888888888888",
                "ch:84",
                unitPrice = null,
            ),
        ).andExpect(status().isOk)
        assertThat(
            jdbc.queryForObject(
                "SELECT unit_price FROM fulfillment_order_lines WHERE order_id = 'order_null_price'",
                java.math.BigDecimal::class.java,
            ),
        ).isNull()

        performSignedPost(
            orderBody(
                "order_negative_price",
                "99999999-9999-4999-8999-999999999999",
                "ch:84",
                unitPrice = -1,
            ),
        ).andExpect(status().isBadRequest)
    }

    @Test
    @WithMockUser(roles = ["HQ_HEAD"])
    fun `HQ head can access fulfillment administration`() {
        mockMvc.perform(get("/api/admin/fulfillment/status"))
            .andExpect(status().isOk)
    }

    @Test
    @WithMockUser(roles = ["CATEGORY_LEAD"])
    fun `business roles outside HQ cannot access fulfillment administration`() {
        mockMvc.perform(get("/api/admin/fulfillment/status"))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `new link does not silently assign an existing unassigned order`() {
        performSignedPost(
            orderBody(
                "order_unassigned",
                "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                "ch:new",
            ),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.assigned").value(false))

        link("ch:new", "ph_1")

        assertThat(
            jdbc.queryForObject(
                "SELECT pharmacy_id FROM fulfillment_orders WHERE order_id = 'order_unassigned'",
                String::class.java,
            ),
        ).isNull()
    }

    @Test
    fun `inactive pharmacy blocks processing but HQ can cancel with a reason`() {
        link("ch:84", "ph_1")
        performSignedPost(
            orderBody(
                "order_inactive",
                "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                "ch:84",
            ),
        ).andExpect(status().isOk)
        val pharmacy = pharmacyRepository.findById("ph_1").orElseThrow()
        pharmacy.active = false
        pharmacyRepository.saveAndFlush(pharmacy)

        assertThatThrownBy {
            service.actAsAdmin(
                "order_inactive",
                FulfillmentActionRequest(action = "assemble", expectedVersion = 1),
                "hq-test",
            )
        }.hasMessageContaining("Inactive pharmacy")

        val cancelled = service.actAsAdmin(
            "order_inactive",
            FulfillmentActionRequest(action = "cancel", expectedVersion = 1, reason = "Аптека отключена"),
            "hq-test",
        )
        assertThat(cancelled.status).isEqualTo("cancelled")
    }

    @Test
    fun `per-device token only exposes its own pharmacy orders`() {
        link("ch:84", "ph_1")
        performSignedPost(orderBody("order_scope", "33333333-3333-4333-8333-333333333333", "ch:84"))
            .andExpect(status().isOk)
        val first = service.registerDevice("KASSA-1", "ph_1").token
        val second = service.registerDevice("KASSA-2", "ph_2").token

        mockMvc.perform(
            get("/api/posm/fulfillment/orders").param("status", "active")
                .header("X-Fulfillment-Device", first),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].orderId").value("order_scope"))

        mockMvc.perform(
            get("/api/posm/fulfillment/orders").param("status", "active")
                .header("X-Fulfillment-Device", second),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(0))
    }

    @Test
    fun `pickup failures persist and lock after five attempts`() {
        val token = readyCashOrder("order_lock", "44444444-4444-4444-8444-444444444444")
        repeat(4) {
            action("order_lock", token, "issue", 3, code = "000000")
                .andExpect(status().isBadRequest)
        }
        action("order_lock", token, "issue", 3, code = "000000")
            .andExpect(status().isTooManyRequests)

        mockMvc.perform(
            get("/api/posm/fulfillment/orders/order_lock")
                .header("X-Fulfillment-Device", token),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.pickupAttempts").value(5))
            .andExpect(jsonPath("$.pickupLockedUntil").isNotEmpty)
    }

    @Test
    fun `cash issue requires confirmation and reverse feed is monotonic`() {
        val token = readyCashOrder("order_cash", "55555555-5555-4555-8555-555555555555")
        action("order_cash", token, "issue", 3, code = "123456")
            .andExpect(status().isConflict)
        action("order_cash", token, "issue", 3, code = "123456", cashCollected = true)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("completed"))
            .andExpect(jsonPath("$.version").value(4))

        val result = performSignedGet("/api/integrations/storefront/order-updates?after=0&limit=200")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.updates.length()").value(3))
            .andReturn()
        val feed = objectMapper.readTree(result.response.contentAsString)
        val cursors = feed["updates"].map { it["cursor"].asLong() }
        assertThat(cursors).isSorted.doesNotHaveDuplicates()
        assertThat(feed["updates"].last()["status"].asText()).isEqualTo("completed")
        assertThat(feed["nextCursor"].asLong()).isEqualTo(cursors.last())
    }

    @Test
    fun `card order cannot be issued before trusted paid status`() {
        link("ch:84", "ph_1")
        performSignedPost(
            orderBody(
                "order_card",
                "66666666-6666-4666-8666-666666666666",
                "ch:84",
                paymentMethod = "card",
            ),
        ).andExpect(status().isOk)
        val token = service.registerDevice("KASSA-CARD", "ph_1").token
        action("order_card", token, "assemble", 1).andExpect(status().isOk)
        action("order_card", token, "ready", 2).andExpect(status().isOk)
        action("order_card", token, "issue", 3, code = "123456")
            .andExpect(status().isConflict)
    }

    private fun readyCashOrder(orderId: String, eventId: String): String {
        link("ch:84", "ph_1")
        performSignedPost(orderBody(orderId, eventId, "ch:84")).andExpect(status().isOk)
        val token = service.registerDevice("KASSA-$orderId", "ph_1").token
        action(orderId, token, "assemble", 1).andExpect(status().isOk)
        action(orderId, token, "ready", 2).andExpect(status().isOk)
        return token
    }

    private fun action(
        orderId: String,
        token: String,
        action: String,
        version: Long,
        code: String? = null,
        cashCollected: Boolean = false,
    ) = mockMvc.perform(
        post("/api/posm/fulfillment/orders/$orderId/actions")
            .header("X-Fulfillment-Device", token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                objectMapper.writeValueAsString(
                    mapOf(
                        "action" to action,
                        "expectedVersion" to version,
                        "code" to code,
                        "cashCollected" to cashCollected,
                    ),
                ),
            ),
    )

    private fun link(externalId: String, pharmacyId: String) {
        service.saveLink(FulfillmentPharmacyLinkRequest(externalId, pharmacyId), "test-admin")
    }

    private fun pharmacy(id: String, name: String) = PharmacyEntity(
        id = id,
        name = name,
        chainId = "chain_f",
        chainName = "Fulfillment",
        city = "Алматы",
        district = "",
        addr = "Тестовый адрес",
    )

    private fun orderBody(
        orderId: String,
        eventId: String,
        externalId: String,
        paymentMethod: String = "cash",
        unitPrice: Any? = 1590,
    ): String = objectMapper.writeValueAsString(
        linkedMapOf<String, Any?>(
            "eventId" to if (eventId.startsWith("event")) "77777777-7777-4777-8777-777777777777" else eventId,
            "orderId" to orderId,
            "number" to "10001",
            "pharmacyExternalId" to externalId,
            "createdAt" to "2026-09-03T10:00:00Z",
            "total" to 1590,
            "currency" to "KZT",
            "delivery" to "pickup",
            "paymentMethod" to paymentMethod,
            "paymentStatus" to "pending",
            "demo" to false,
            "pickupCode" to "123456",
            "lines" to listOf(
                linkedMapOf(
                    "productId" to "prod_1",
                    "sku" to "SKU-1",
                    "title" to "Товар 1",
                    "quantity" to 1,
                    "unitPrice" to unitPrice,
                ),
            ),
        ),
    )

    private fun performSignedPost(body: String, timestamp: Long = Instant.now().epochSecond) = mockMvc.perform(
        post("/api/integrations/storefront/orders")
            .header("X-Fulfillment-Timestamp", timestamp)
            .header("X-Fulfillment-Signature", signature(timestamp, "POST", "/api/integrations/storefront/orders", body))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body),
    )

    private fun performSignedGet(pathAndQuery: String): org.springframework.test.web.servlet.ResultActions {
        val timestamp = Instant.now().epochSecond
        return mockMvc.perform(
            get(pathAndQuery).header("X-Fulfillment-Timestamp", timestamp)
                .header("X-Fulfillment-Signature", signature(timestamp, "GET", pathAndQuery, "")),
        )
    }

    private fun signature(timestamp: Long, method: String, pathAndQuery: String, body: String): String {
        val bodyHash = MessageDigest.getInstance("SHA-256")
            .digest(body.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val canonical = "$timestamp\n$method\n$pathAndQuery\n$bodyHash"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(SECRET.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
