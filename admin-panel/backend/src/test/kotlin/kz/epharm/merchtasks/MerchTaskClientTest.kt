package kz.epharm.merchtasks

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kz.epharm.merchtasks.dto.MerchTaskShownRequest
import kz.epharm.merchtasks.service.MerchTaskClient
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class MerchTaskClientTest {
    private var server: HttpServer? = null

    @AfterEach
    fun stopServer() {
        server?.stop(0)
    }

    @Test
    fun `gateway keeps integration key server-side and preserves the upstream contract`() {
        val seenMethod = AtomicReference<String>()
        val seenPath = AtomicReference<String>()
        val seenKey = AtomicReference<String>()
        val seenBody = AtomicReference<String>()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                seenMethod.set(exchange.requestMethod)
                seenPath.set(exchange.requestURI.toString())
                seenKey.set(exchange.requestHeaders.getFirst("X-Pharmapay-Key"))
                seenBody.set(exchange.requestBody.bufferedReader().readText())
                val body = if (exchange.requestMethod == "GET") {
                    """{"task":{"id":"dispatch-1","publicUrl":"https://epharm.inkar.kz/merch/staff"}}"""
                } else {
                    """{"accepted":true}"""
                }.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        val client = client(enabled = true, integrationKey = "server-secret")

        val active = client.activeTask(" pharmacy-7 ")

        assertThat(active.path("task").path("id").asText()).isEqualTo("dispatch-1")
        assertThat(seenMethod.get()).isEqualTo("GET")
        assertThat(seenPath.get()).isEqualTo(
            "/api/integrations/pharmapay/tasks/active?pharmacyId=pharmacy-7",
        )
        assertThat(seenKey.get()).isEqualTo("server-secret")

        val shown = client.markShown(
            MerchTaskShownRequest(
                dispatchId = "dispatch-1",
                pharmacyId = "pharmacy-7",
                deviceId = "POS-02",
                deliveryToken = "delivery-token",
            ),
        )

        assertThat(shown.path("accepted").asBoolean()).isTrue()
        assertThat(seenMethod.get()).isEqualTo("POST")
        assertThat(seenPath.get()).isEqualTo("/api/integrations/pharmapay/tasks/shown")
        assertThat(seenKey.get()).isEqualTo("server-secret")
        assertThat(seenBody.get()).contains(
            "\"dispatchId\":\"dispatch-1\"",
            "\"pharmacyId\":\"pharmacy-7\"",
            "\"deviceId\":\"POS-02\"",
            "\"deliveryToken\":\"delivery-token\"",
        )
    }

    @Test
    fun `disabled or incompletely configured gateway fails closed without network access`() {
        val disabled = MerchTaskClient(jacksonObjectMapper(), false, "", "", 7_000)
        val missingKey = MerchTaskClient(jacksonObjectMapper(), true, "http://127.0.0.1:1", "", 7_000)
        val receipt = MerchTaskShownRequest("dispatch-1", "pharmacy-7", "POS-02", "delivery-token")

        assertThat(disabled.activeTask("pharmacy-7").path("task").isNull).isTrue()
        assertThat(disabled.markShown(receipt).path("accepted").asBoolean()).isFalse()
        assertThat(missingKey.activeTask("pharmacy-7").path("task").isNull).isTrue()
    }

    @Test
    fun `invalid pharmacy identifier is rejected before contacting upstream`() {
        val client = MerchTaskClient(jacksonObjectMapper(), false, "", "", 7_000)

        assertThatThrownBy { client.activeTask("pharmacy\nspoof") }
            .isInstanceOf(AppException::class.java)
            .extracting("code")
            .isEqualTo(ErrorCode.VALIDATION_FAILED)
    }

    @Test
    fun `upstream failures use the stable unavailable error contract`() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                exchange.sendResponseHeaders(503, -1)
                exchange.close()
            }
            start()
        }
        val client = client(enabled = true, integrationKey = "server-secret")

        assertThatThrownBy { client.activeTask("pharmacy-7") }
            .isInstanceOf(AppException::class.java)
            .extracting("code")
            .isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE)
    }

    private fun client(enabled: Boolean, integrationKey: String): MerchTaskClient {
        val port = requireNotNull(server).address.port
        return MerchTaskClient(
            objectMapper = jacksonObjectMapper(),
            enabled = enabled,
            baseUrl = "http://127.0.0.1:$port",
            integrationKey = integrationKey,
            timeoutMs = 1_000,
        )
    }
}
