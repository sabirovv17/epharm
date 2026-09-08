package kz.epharm.shared.media

import kz.epharm.shared.error.AppException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

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
}
