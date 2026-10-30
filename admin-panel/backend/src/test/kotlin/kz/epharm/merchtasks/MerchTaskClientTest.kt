package kz.epharm.merchtasks

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kz.epharm.merchtasks.service.NativeRecommendationContextReader
import kz.epharm.merchtasks.dto.MerchRecommendationContextEnvelope
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.net.InetSocketAddress
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kz.epharm.merchtasks.dto.MerchTaskShownRequest
import kz.epharm.merchtasks.dto.MerchRecommendationCartItem
import kz.epharm.merchtasks.dto.MerchRecommendationContextRequest
import kz.epharm.merchtasks.dto.MerchRecommendationProductRef
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
    fun `global disabled flag prevents native reads too`() {
        val native=mockk<NativeRecommendationContextReader>()
        val registry=SimpleMeterRegistry()
        val client=MerchTaskClient(jacksonObjectMapper(),false,"","",900,"https://epharm.inkar.kz",registry,
            nativeContext=native,legacyCutover=true)
        assertThat(client.recommendationContext(MerchRecommendationContextRequest("pharmacy-7",emptyList())).recommendations).isEmpty()
        verify(exactly=0){native.context(any())}
        assertThat(registry.get("epharm.merch.tasks.requests").tags("operation","recommendations","outcome","disabled").counter().count()).isEqualTo(1.0)
    }

    @Test
    fun `native requests share the bounded legacy bulkhead and saturation metrics`() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val native=mockk<NativeRecommendationContextReader>()
        every { native.context(any()) } answers { entered.countDown();check(release.await(5,TimeUnit.SECONDS));MerchRecommendationContextEnvelope() }
        val registry=SimpleMeterRegistry()
        val client=MerchTaskClient(jacksonObjectMapper(),true,"","",900,"https://epharm.inkar.kz",registry,
            maxConcurrent=1,nativeContext=native,legacyCutover=true)
        val req=MerchRecommendationContextRequest("pharmacy-7",emptyList())
        val first=CompletableFuture.supplyAsync { client.recommendationContext(req) }
        try {
            check(entered.await(5,TimeUnit.SECONDS))
            assertThat(client.recommendationContext(req).available).isFalse()
            verify(exactly=1){native.context(any())}
            assertThat(registry.get("epharm.merch.tasks.requests").tags("operation","recommendations","outcome","saturated").counter().count()).isEqualTo(1.0)
        } finally { release.countDown() }
        assertThat(first.get(5,TimeUnit.SECONDS).available).isTrue()
    }

    @Test
    fun `native cutover never calls configured CRM even when native reader is absent`() {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange -> calls.incrementAndGet(); exchange.sendResponseHeaders(503, -1); exchange.close() }
            start()
        }
        val client = MerchTaskClient(jacksonObjectMapper().findAndRegisterModules(), true,
            "http://127.0.0.1:${requireNotNull(server).address.port}", "synthetic-only", 900,
            "https://epharm.inkar.kz", SimpleMeterRegistry(), legacyCutover=true)
        assertThat(client.recommendationContext(MerchRecommendationContextRequest("pharmacy-7",emptyList())).available).isFalse()
        assertThat(calls.get()).isZero()
    }

    @Test
    fun `expired deadlines and QR tokens return a healthy empty task queue`() {
        val dates = AtomicReference("")
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val body = """{"task":{"id":"$dispatchId","publicUrl":"https://epharm.inkar.kz/merch/staff?task=$deliveryToken","deliveryToken":"$deliveryToken",${dates.get()}}}"""
                    .toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        val bridge = client(enabled = true, integrationKey = "server-secret")
        val now = Instant.now()
        val future = now.plusSeconds(3_600)
        for (dateFields in listOf(
            """"dueAt":"${now.minusSeconds(60)}","expiresAt":"$future"""",
            """"dueAt":"$now","expiresAt":"$future"""",
            """"dueAt":"$future","expiresAt":"${now.minusSeconds(60)}"""",
            """"dueAt":null,"expiresAt":"$now"""",
        )) {
            dates.set(dateFields)

            val response = bridge.activeTask("pharmacy-7")

            assertThat(response.available).describedAs(dateFields).isTrue()
            assertThat(response.task).describedAs(dateFields).isNull()
        }
    }

    @Test
    fun `future deadlines and legacy nullable dates remain deliverable`() {
        val dates = AtomicReference("")
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val body = """{"task":{"id":"$dispatchId","publicUrl":"https://epharm.inkar.kz/merch/staff?task=$deliveryToken","deliveryToken":"$deliveryToken",${dates.get()}}}"""
                    .toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        val bridge = client(enabled = true, integrationKey = "server-secret")
        val future = Instant.now().plusSeconds(3_600)
        for (dateFields in listOf(
            """"dueAt":"$future","expiresAt":"$future"""",
            """"dueAt":null,"expiresAt":null""",
            """"dueAt":"$future","expiresAt":null""",
        )) {
            dates.set(dateFields)

            val response = bridge.activeTask("pharmacy-7")

            assertThat(response.available).describedAs(dateFields).isTrue()
            assertThat(response.task?.id).describedAs(dateFields).isEqualTo(dispatchId)
        }
    }

    @Test
    fun `unavailable envelope never forwards its task to older POSM clients`() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val body = """{"available":false,"task":{"id":"$dispatchId","publicUrl":"https://epharm.inkar.kz/merch/staff?task=$deliveryToken","deliveryToken":"$deliveryToken"}}"""
                    .toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }

        val response = client(enabled = true, integrationKey = "server-secret").activeTask("pharmacy-7")

        assertThat(response.available).isFalse()
        assertThat(response.task).isNull()
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
    fun `recommendation context sends no pharmacist identity and preserves nullable balances`() {
        val seenBody = AtomicReference<String>()
        val seenKey = AtomicReference<String>()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                seenKey.set(exchange.requestHeaders.getFirst("X-Pharmapay-Key"))
                seenBody.set(exchange.requestBody.bufferedReader().readText())
                val body = """{
                  "recommendations":[{
                    "id":"rec-1","kind":"crosssell","triggerBarcode":"4600000000018",
                    "recommendProductId":"p-2","script":"Предложите вместе","priority":10
                  }],
                  "balances":[{
                    "productId":"p-2","pharmacyAvailableQty":4,"networkAvailableQty":18.5,
                    "warehouseAvailableQty":120,"observedAt":"2026-10-19T10:30:00Z",
                    "source":"warehouse","status":"confirmed"
                  }]
                }""".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }

        val result = client(enabled = true, integrationKey = "server-secret").recommendationContext(
            MerchRecommendationContextRequest(
                pharmacyId = "pharmacy-7",
                cart = listOf(MerchRecommendationCartItem(barcode = "4600000000018", name = "Товар")),
                requestedProducts = listOf(MerchRecommendationProductRef(productId = "p-2")),
            ),
        )

        assertThat(result.recommendations).hasSize(1)
        assertThat(result.balances.single().pharmacyAvailableQty).isEqualByComparingTo("4")
        assertThat(result.balances.single().warehouseAvailableQty).isEqualByComparingTo("120")
        assertThat(seenKey.get()).isEqualTo("server-secret")
        assertThat(seenBody.get()).contains("\"pharmacyId\":\"pharmacy-7\"")
        assertThat(seenBody.get()).doesNotContain("pharmacist", "sessionId")
    }

    @Test
    fun `HTTP error and malformed recommendation contexts return an empty unavailable fallback`() {
        val response = AtomicReference(503 to "service unavailable")
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                exchange.requestBody.use { it.readBytes() }
                val (status, text) = response.get()
                val body = text.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        val client = client(enabled = true, integrationKey = "server-secret")
        for (reply in listOf(
            503 to "service unavailable",
            200 to "{malformed",
            200 to """{"recommendations":[{"id":"invalid","kind":"unknown"}]}""",
        )) {
            response.set(reply)

            val context = client.recommendationContext(MerchRecommendationContextRequest("pharmacy-7", emptyList()))

            assertThat(context.available).isFalse()
            assertThat(context.recommendations).isEmpty()
            assertThat(context.balances).isEmpty()
        }
    }

    @Test
    fun `recommendation timeout is bounded separately from the longer QR timeout`() {
        val release = CountDownLatch(1)
        serverExecutor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "merch-recommend-timeout-test").apply { isDaemon = true }
        }
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = serverExecutor
            createContext("/") { exchange ->
                exchange.requestBody.use { it.readBytes() }
                release.await(5, TimeUnit.SECONDS)
                runCatching { exchange.sendResponseHeaders(503, -1) }
                exchange.close()
            }
            start()
        }
        val client = client(
            enabled = true, integrationKey = "server-secret", timeoutMs = 5_000, recommendTimeoutMs = 250,
        )
        try {
            val start = System.nanoTime()

            val context = client.recommendationContext(MerchRecommendationContextRequest("pharmacy-7", emptyList()))

            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            assertThat(context.available).isFalse()
            assertThat(context.recommendations).isEmpty()
            assertThat(elapsedMs).isLessThan(2_500)
        } finally {
            release.countDown()
        }
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
    fun `private-looking DNS names and ambiguous IPv4 cannot carry the server key over HTTP`() {
        for (host in listOf("10.10.1.76.evil.example", "evil.172.16.0.1.example", "192.168.0.1.example",
            "10.00.0.1", "010.0.0.1", "10.0.0.256", "10.0.0.1.", "10.0.1")) {
            val bridge = clientWithoutServer(true, "http://$host:8080", "server-secret")
            assertThat(bridge.activeTask("pharmacy-7").available).describedAs(host).isFalse()
            assertThat(bridge.markShown(MerchTaskShownRequest(dispatchId, "pharmacy-7", "POS-02", deliveryToken)).available)
                .describedAs(host).isFalse()
        }
    }

    @Test
    fun `redirect never forwards the integration key to another endpoint`() {
        val redirectedRequests = java.util.concurrent.atomic.AtomicInteger()
        val destination = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                redirectedRequests.incrementAndGet()
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
            start()
        }
        try {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { exchange ->
                    exchange.responseHeaders.add("Location", "http://127.0.0.1:${destination.address.port}/capture")
                    exchange.sendResponseHeaders(302, -1)
                    exchange.close()
                }
                start()
            }
            val bridge = client(true, "synthetic-server-key")
            assertThat(bridge.activeTask("pharmacy-7").available).isFalse()
            assertThat(bridge.markShown(MerchTaskShownRequest(dispatchId, "pharmacy-7", "POS-02", deliveryToken)).available)
                .isFalse()
            assertThat(redirectedRequests.get()).isZero()
        } finally {
            destination.stop(0)
        }
    }

    @Test
    fun `metrics distinguish disabled misconfiguration and rejected shown receipts`() {
        val registry = SimpleMeterRegistry()
        val disabled = clientWithoutServer(false, "", "", registry)
        disabled.activeTask("pharmacy-7")
        assertThat(registry.get("epharm.merch.tasks.enabled").gauge().value()).isZero()
        assertThat(registry.get("epharm.merch.tasks.requests").tags("operation", "active", "outcome", "disabled")
            .counter().count()).isEqualTo(1.0)
        val brokenRegistry = SimpleMeterRegistry()
        val broken = clientWithoutServer(true, "http://10.0.0.1.evil.example", "server-secret", brokenRegistry)
        broken.activeTask("pharmacy-7")
        assertThat(brokenRegistry.get("epharm.merch.tasks.configured").gauge().value()).isZero()
        assertThat(brokenRegistry.get("epharm.merch.tasks.requests").tags("operation", "active", "outcome", "misconfigured")
            .counter().count()).isEqualTo(1.0)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val body = """{"accepted":false}""".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        val rejectedRegistry = SimpleMeterRegistry()
        val rejected = clientWithoutServer(true, "http://127.0.0.1:${requireNotNull(server).address.port}",
            "server-secret", rejectedRegistry)
        assertThat(rejected.markShown(MerchTaskShownRequest(dispatchId, "pharmacy-7", "POS-02", deliveryToken)).accepted).isFalse()
        assertThat(rejectedRegistry.get("epharm.merch.tasks.requests").tags("operation", "shown", "outcome", "rejected")
            .counter().count()).isEqualTo(1.0)
        assertThat(rejectedRegistry.find("epharm.merch.tasks.requests").tags("operation", "shown", "outcome", "success")
            .counter()).isNull()
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
        recommendTimeoutMs: Int = 900,
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
            recommendTimeoutMs = recommendTimeoutMs,
        )
    }

    private fun clientWithoutServer(
        enabled: Boolean,
        baseUrl: String,
        integrationKey: String,
        meterRegistry: SimpleMeterRegistry = SimpleMeterRegistry(),
    ) = MerchTaskClient(
        objectMapper = jacksonObjectMapper(),
        enabled = enabled,
        baseUrl = baseUrl,
        integrationKey = integrationKey,
        timeoutMs = 1_000,
        publicBaseUrl = "https://epharm.inkar.kz",
        meterRegistry = meterRegistry,
    )
}
