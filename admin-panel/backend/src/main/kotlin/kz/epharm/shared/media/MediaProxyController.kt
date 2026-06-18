package kz.epharm.shared.media

import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.RestClient
import java.net.URI
import java.time.Duration

/**
 * Прокси изображений витрины Medusa.
 *
 * Medusa отдаёт фото товаров по голому HTTP (`http://host:9000/static/…`), а
 * админ-консоль работает по HTTPS. Браузер блокирует загрузку http-картинок на
 * https-странице (mixed content) → в галерее «битые» иконки. Поэтому тянем
 * картинку server-to-server и отдаём её клиенту уже по нашему HTTPS-origin.
 *
 * Эндпоинт ПУБЛИЧНЫЙ (фото витрины — не секрет, как и сам каталог), но с защитой
 * от SSRF: проксируем ТОЛЬКО хост Medusa из конфига (`app.medusa.base-url`). Любой
 * другой `u` → 400, чтобы наш сервер не стал открытым прокси к произвольным URL.
 *
 * Кэш: фото витрины статичны (имя файла с timestamp-префиксом), отдаём
 * `Cache-Control: public, max-age=7d` — браузер/Caddy не дёргают бэкенд повторно.
 */
@RestController
@RequestMapping("/api/media")
class MediaProxyController(
    @Value("\${app.medusa.base-url:http://78.140.246.238:9000}") medusaBaseUrl: String,
    @Value("\${app.medusa.timeout-ms:6000}") timeoutMs: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Разрешённый источник: authority (host[:port]) витрины Medusa. */
    private val allowedAuthority: String =
        runCatching { URI(medusaBaseUrl).authority }.getOrNull().orEmpty()

    private val rest: RestClient = RestClient.builder()
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(timeoutMs)
                setReadTimeout(timeoutMs)
            },
        )
        .build()

    @GetMapping("/img")
    fun img(@RequestParam("u") raw: String): ResponseEntity<ByteArray> {
        val uri = runCatching { URI(raw) }.getOrNull()
            ?: throw badRequest("Некорректный URL изображения")

        // Anti-SSRF: только http/https и только хост витрины Medusa.
        if (uri.scheme !in HTTP_SCHEMES || uri.authority != allowedAuthority || allowedAuthority.isBlank()) {
            throw badRequest("Недопустимый источник изображения")
        }

        val resp = try {
            rest.get().uri(uri).retrieve().toEntity(ByteArray::class.java)
        } catch (e: Exception) {
            log.warn("media proxy {} failed: {}", uri, e.message)
            throw AppException(
                ErrorCode.UPSTREAM_UNAVAILABLE,
                "Изображение недоступно",
                HttpStatus.BAD_GATEWAY,
                e,
            )
        }

        val body = resp.body
        if (body == null || body.isEmpty()) {
            throw AppException(ErrorCode.UPSTREAM_UNAVAILABLE, "Пустой ответ", HttpStatus.BAD_GATEWAY)
        }
        val contentType = resp.headers.contentType ?: MediaType.APPLICATION_OCTET_STREAM
        return ResponseEntity.ok()
            .contentType(contentType)
            .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
            .body(body)
    }

    private fun badRequest(msg: String) =
        AppException(ErrorCode.VALIDATION_FAILED, msg, HttpStatus.BAD_REQUEST)

    private companion object {
        val HTTP_SCHEMES = setOf("http", "https")
    }
}
