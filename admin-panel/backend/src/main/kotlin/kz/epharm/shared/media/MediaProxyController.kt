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
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.time.Duration
import javax.imageio.ImageIO

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
    fun img(
        @RequestParam("u") raw: String,
        // Опц. ширина превью в px: фото ресайзится server-side в JPEG (быстрая первая
        // загрузка + меньше трафика на ленте/каталоге). Без `w` — отдаём оригинал.
        @RequestParam(value = "w", required = false) w: Int? = null,
    ): ResponseEntity<ByteArray> {
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
        // Превью: если задана разумная ширина и фото её больше — ресайзим в JPEG.
        // Любая осечка (не картинка / уже мельче) → отдаём оригинал без ошибки.
        if (w != null && w in 50..2000) {
            resizeToWidth(body, w)?.let { thumb ->
                return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG)
                    .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
                    .body(thumb)
            }
        }

        val contentType = resp.headers.contentType ?: MediaType.APPLICATION_OCTET_STREAM
        return ResponseEntity.ok()
            .contentType(contentType)
            .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
            .body(body)
    }

    /** Ресайз до ширины targetW (сохраняя пропорции) → JPEG. null, если не картинка
     *  или исходник уже не шире targetW (не апскейлим — смысла нет). */
    private fun resizeToWidth(src: ByteArray, targetW: Int): ByteArray? = runCatching {
        val img = ImageIO.read(ByteArrayInputStream(src)) ?: return null
        if (img.width <= targetW) return null
        val targetH = (img.height.toLong() * targetW / img.width).toInt().coerceAtLeast(1)
        val scaled = BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB)
        scaled.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            drawImage(img, 0, 0, targetW, targetH, null)
            dispose()
        }
        val out = ByteArrayOutputStream()
        if (!ImageIO.write(scaled, "jpg", out)) return null
        out.toByteArray()
    }.getOrNull()

    private fun badRequest(msg: String) =
        AppException(ErrorCode.VALIDATION_FAILED, msg, HttpStatus.BAD_REQUEST)

    private companion object {
        val HTTP_SCHEMES = setOf("http", "https")
    }
}
