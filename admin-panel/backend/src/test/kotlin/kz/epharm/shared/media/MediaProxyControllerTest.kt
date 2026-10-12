package kz.epharm.shared.media

import com.sun.net.httpserver.HttpServer
import kz.epharm.shared.error.AppException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * Anti-SSRF проверки image-прокси (без сети): любой источник кроме хоста Medusa
 * из конфига → 400, и до сетевого запроса дело не доходит.
 */
class MediaProxyControllerTest {

    private val controller = MediaProxyController(
        medusaBaseUrl = "https://medusa.example.kz",
        connectTimeoutMs = 1000,
        readTimeoutMs = 1000,
    )

    @Test
    fun `чужой хост отвергается (SSRF-guard)`() {
        val ex = assertThrows(AppException::class.java) {
            controller.img("http://evil.example.com/secret.jpg")
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.status)
    }

    @Test
    fun `некорректный URL отвергается`() {
        val ex = assertThrows(AppException::class.java) { controller.img("::: not a url") }
        assertEquals(HttpStatus.BAD_REQUEST, ex.status)
    }

    @Test
    fun `нехттп-схема отвергается`() {
        val ex = assertThrows(AppException::class.java) { controller.img("file:///etc/passwd") }
        assertEquals(HttpStatus.BAD_REQUEST, ex.status)
    }

    @Test
    fun `тот же хост но другой порт отвергается`() {
        val ex = assertThrows(AppException::class.java) {
            controller.img("https://medusa.example.kz:9999/static/x.jpg")
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.status)
    }

    @Test
    fun `website images are allowed only while configured and enabled`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/image") { exchange ->
            val bytes = byteArrayOf(1, 2, 3)
            exchange.responseHeaders.add("Content-Type", "image/jpeg")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val origin = "http://127.0.0.1:${server.address.port}"
            val enabled = MediaProxyController("https://medusa.example.kz", 1000, 1000, true, origin)
            assertEquals(listOf<Byte>(1, 2, 3), enabled.img("$origin/image").body?.toList())

            val disabled = MediaProxyController("https://medusa.example.kz", 1000, 1000, false, origin)
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(AppException::class.java) {
                disabled.img("$origin/image")
            }.status)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `website redirect does not escape configured origin`() {
        val destinationHits = AtomicInteger()
        val destination = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        destination.createContext("/secret") { exchange ->
            destinationHits.incrementAndGet()
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        destination.start()
        val source = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        source.createContext("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "http://127.0.0.1:${destination.address.port}/secret")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        source.start()
        try {
            val origin = "http://127.0.0.1:${source.address.port}"
            val proxy = MediaProxyController("https://medusa.example.kz", 1000, 1000, true, origin)
            assertThrows(AppException::class.java) { proxy.img("$origin/redirect") }
            assertEquals(0, destinationHits.get())
        } finally {
            source.stop(0)
            destination.stop(0)
        }
    }
}
