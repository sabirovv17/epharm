package kz.epharm.merchtasks

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
    private val dispatchId = "123e4567-e89b-42d3-a456-426614174000"
    private val deliveryToken = "test-opaque-delivery-token-00000001"
    private var server: HttpServer? = null
    private var serverExecutor: java.util.concurrent.ExecutorService? = null

    @AfterEach
    fun stopServer() {
        server?.stop(0)
        serverExecutor?.shutdownNow()
    }

    @Test
    fun `gateway keeps integration key server-side and preserves the upstream contract`() {
        val seenMethod = AtomicReference<String>()
        val seenPath = AtomicReference<String>()
        val seenKey = AtomicReference<String>()
        val seenBody = AtomicReference<String>()
        val seenContentLength = AtomicReference<String>()
        val seenTransferEncoding = AtomicReference<String?>()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                seenMethod.set(exchange.requestMethod)
                seenPath.set(exchange.requestURI.toString())
                seenKey.set(exchange.requestHeaders.getFirst("X-Pharmapay-Key"))
                seenContentLength.set(exchange.requestHeaders.getFirst("Content-Length"))
                seenTransferEncoding.set(exchange.requestHeaders.getFirst("Transfer-Encoding"))
                seenBody.set(exchange.requestBody.bufferedReader().readText())
                val body = if (exchange.requestMethod == "GET") {
                    """{"task":{"id":"$dispatchId","publicUrl":"https://epharm.inkar.kz/merch/staff?task=$deliveryToken","deliveryToken":"$deliveryToken"}}"""
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

        assertThat(active.available).isTrue()
        assertThat(active.task?.id).isEqualTo(dispatchId)
        assertThat(seenMethod.get()).isEqualTo("GET")
        assertThat(seenPath.get()).isEqualTo(
            "/api/integrations/pharmapay/tasks/active?pharmacyId=pharmacy-7",
        )
        assertThat(seenKey.get()).isEqualTo("server-secret")

        val shown = client.markShown(
            MerchTaskShownRequest(
                dispatchId = dispatchId,
                pharmacyId = "pharmacy-7",
                deviceId = "POS-02",
                deliveryToken = deliveryToken,
            ),
        )

        assertThat(shown.accepted).isTrue()
        assertThat(shown.available).isTrue()
        assertThat(seenMethod.get()).isEqualTo("POST")
        assertThat(seenPath.get()).isEqualTo("/api/integrations/pharmapay/tasks/shown")
        assertThat(seenKey.get()).isEqualTo("server-secret")
        assertThat(seenContentLength.get().toLong()).isGreaterThan(0)
        assertThat(seenTransferEncoding.get()).isNull()
        assertThat(seenBody.get()).contains(
            "\"dispatchId\":\"$dispatchId\"",
            "\"pharmacyId\":\"pharmacy-7\"",
            "\"deviceId\":\"POS-02\"",
            "\"deliveryToken\":\"$deliveryToken\"",
        )
    }

    @Test
    fun `disabled gateway stays quiet while incomplete configuration reports unavailable`() {
        val disabled = clientWithoutServer(enabled = false, baseUrl = "", integrationKey = "")
        val missingKey = clientWithoutServer(
            enabled = true,
            baseUrl = "http://127.0.0.1:1",
            integrationKey = "",
        )
        val receipt = MerchTaskShownRequest(dispatchId, "pharmacy-7", "POS-02", deliveryToken)

        assertThat(disabled.activeTask("pharmacy-7").available).isTrue()
        assertThat(disabled.activeTask("pharmacy-7").task).isNull()
        assertThat(disabled.markShown(receipt).accepted).isFalse()
        assertThat(disabled.markShown(receipt).available).isTrue()
        assertThat(missingKey.activeTask("pharmacy-7").available).isFalse()
    }

    @Test
    fun `public HTTP upstream fails closed before sending the server key`() {
        val publicHttp = clientWithoutServer(
            enabled = true,
            baseUrl = "http://203.0.113.10:8080",
            integrationKey = "server-secret",
        )
        val receipt = MerchTaskShownRequest(dispatchId, "pharmacy-7", "POS-02", deliveryToken)

        assertThat(publicHttp.activeTask("pharmacy-7").available).isFalse()
        assertThat(publicHttp.markShown(receipt).available).isFalse()
    }

    @Test
    fun `invalid pharmacy identifier is rejected before contacting upstream`() {
        val client = clientWithoutServer(enabled = false, baseUrl = "", integrationKey = "")

        assertThatThrownBy { client.activeTask("pharmacy\nspoof") }
            .isInstanceOf(AppException::class.java)
            .extracting("code")
            .isEqualTo(ErrorCode.VALIDATION_FAILED)
    }

    @Test
    fun `upstream failures fail open without returning a gateway error to POSM`() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                exchange.sendResponseHeaders(503, -1)
                exchange.close()
            }
            start()
        }
        val client = client(enabled = true, integrationKey = "server-secret")

        val result = client.activeTask("pharmacy-7")

        assertThat(result.available).isFalse()
        assertThat(result.task).isNull()
    }

    @Test
    fun `untrusted public task link is rejected without exposing an upstream failure`() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val body = """{"task":{"id":"$dispatchId","publicUrl":"https://evil.example/merch/staff?task=$deliveryToken","deliveryToken":"$deliveryToken"}}"""
                    .toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }

        val result = client(enabled = true, integrationKey = "server-secret").activeTask("pharmacy-7")

        assertThat(result.available).isFalse()
        assertThat(result.task).isNull()
    }

    @Test
    fun `direct CRM staff link is accepted only when explicitly configured`() {
        val requests = java.util.concurrent.atomic.AtomicInteger()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val url = when (requests.incrementAndGet()) {
                    3 -> "https://epharm.inkar.kz/merch/staff?task=$deliveryToken"
                    4 -> "https://crm.inkar.kz/staff?task=1#task=different-opaque-delivery-token-00"
                    5 -> "https://crm.inkar.kz/staff?task=$deliveryToken"
                    6 -> "https://crm.inkar.kz/%73taff?task=1#task=$deliveryToken"
                    else -> "https://crm.inkar.kz/staff?task=1#task=$deliveryToken"
                }
                val body = """{"task":{"id":"$dispatchId","publicUrl":"$url","deliveryToken":"$deliveryToken"}}"""
                    .toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }

        assertThat(client(enabled = true, integrationKey = "server-secret").activeTask("pharmacy-7").available).isFalse()
        val bridge = client(enabled = true, integrationKey = "server-secret", publicStaffUrl = "https://crm.inkar.kz/staff")
        val configured = bridge.activeTask("pharmacy-7")
        assertThat(configured.available).isTrue()
        assertThat(configured.task?.id).isEqualTo(dispatchId)
        assertThat(configured.task?.publicUrl).contains("?task=1#task=")
        val legacy = bridge.activeTask("pharmacy-7")
        assertThat(legacy.available).isTrue()
        assertThat(legacy.task?.publicUrl).startsWith("https://epharm.inkar.kz/merch/staff?")
        assertThat(bridge.activeTask("pharmacy-7").available).isFalse() // token mismatch
        assertThat(bridge.activeTask("pharmacy-7").available).isFalse() // bearer token in CRM query
        assertThat(bridge.activeTask("pharmacy-7").available).isFalse() // encoded path alias
    }

    @Test
    fun `malformed dispatch identifier does not contact upstream`() {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                calls.incrementAndGet()
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
            start()
        }
        val client = client(enabled = true, integrationKey = "server-secret")
        val result = client.markShown(MerchTaskShownRequest("dispatch-1", "pharmacy-7", "POS-02", deliveryToken))
        assertThat(result.available).isFalse()
        assertThat(result.accepted).isFalse()
        val shortToken = client.markShown(MerchTaskShownRequest(dispatchId, "pharmacy-7", "POS-02", "too-short"))
        assertThat(shortToken.available).isFalse()
        assertThat(shortToken.accepted).isFalse()
        assertThat(calls.get()).isZero()
    }

    @Test
    fun `upstream acknowledgement error remains isolated from POSM core`() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                exchange.sendResponseHeaders(400, -1)
                exchange.close()
            }
            start()
        }
        val result = client(enabled = true, integrationKey = "server-secret")
            .markShown(MerchTaskShownRequest(dispatchId, "pharmacy-7", "POS-02", deliveryToken))
        assertThat(result.available).isFalse()
        assertThat(result.accepted).isFalse()
    }

    @Test
    fun `stalled CRM polls are bounded and excess calls fail open without waiting`() {
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        serverExecutor = Executors.newCachedThreadPool { runnable -> Thread(runnable, "merch-test-http").apply { isDaemon = true } }
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = serverExecutor
            createContext("/") { exchange ->
                entered.countDown()
                release.await(3, TimeUnit.SECONDS)
                exchange.sendResponseHeaders(503, -1)
                exchange.close()
            }
            start()
        }
        val client = client(enabled = true, integrationKey = "server-secret", maxConcurrent = 2, timeoutMs = 2_500)
        val first = CompletableFuture.supplyAsync { client.activeTask("pharmacy-7") }
        val second = CompletableFuture.supplyAsync { client.activeTask("pharmacy-7") }
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue()
            val start = System.nanoTime()
            val excess = client.activeTask("pharmacy-7")
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            assertThat(excess.available).isFalse()
            assertThat(elapsedMs).isLessThan(200)
        } finally {
            release.countDown()
        }
        assertThat(first.get(3, TimeUnit.SECONDS).available).isFalse()
        assertThat(second.get(3, TimeUnit.SECONDS).available).isFalse()
    }

    private fun client(
        enabled: Boolean,
        integrationKey: String,
        publicStaffUrl: String = "",
        maxConcurrent: Int = 16,
        timeoutMs: Int = 1_000,
    ): MerchTaskClient {
        val port = requireNotNull(server).address.port
        return MerchTaskClient(
            objectMapper = jacksonObjectMapper(),
            enabled = enabled,
            baseUrl = "http://127.0.0.1:$port",
            integrationKey = integrationKey,
            timeoutMs = timeoutMs,
            publicBaseUrl = "https://epharm.inkar.kz",
            meterRegistry = SimpleMeterRegistry(),
            publicStaffUrl = publicStaffUrl,
            maxConcurrent = maxConcurrent,
        )
    }

    private fun clientWithoutServer(
        enabled: Boolean,
        baseUrl: String,
        integrationKey: String,
    ) = MerchTaskClient(
        objectMapper = jacksonObjectMapper(),
        enabled = enabled,
        baseUrl = baseUrl,
        integrationKey = integrationKey,
        timeoutMs = 1_000,
        publicBaseUrl = "https://epharm.inkar.kz",
        meterRegistry = SimpleMeterRegistry(),
    )
}
