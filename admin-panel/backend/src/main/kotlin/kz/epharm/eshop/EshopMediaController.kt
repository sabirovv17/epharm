package kz.epharm.eshop

import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/** Public image proxy with a fixed origin and fixed path, never an arbitrary URL. */
@RestController
@RequestMapping("/api/media")
class EshopMediaController(
    @Value("\${app.eshop.catalog.media-base-url:https://aptekasosklada.kz}") mediaBaseUrl: String,
    private val catalog: EshopCatalogSnapshotRepository,
) {
    private val origin = URI(mediaBaseUrl.trimEnd('/')).also { uri ->
        check(uri.scheme == "https" && uri.host.equals("aptekasosklada.kz", ignoreCase = true) &&
            uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.path.orEmpty() in setOf("", "/")) {
            "ESHOP_CATALOG_MEDIA_BASE_URL must be https://aptekasosklada.kz"
        }
    }
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    @GetMapping("/eshop")
    fun image(@RequestParam sku: String): ResponseEntity<ByteArray> {
        if (!SKU.matches(sku)) {
            throw AppException(ErrorCode.VALIDATION_FAILED, "Некорректный SKU изображения", HttpStatus.BAD_REQUEST)
        }
        val storedSku = catalog.publishedSku(sku)
            ?: throw AppException(ErrorCode.NOT_FOUND, "Изображение товара не найдено", HttpStatus.NOT_FOUND)
        val encoded = URLEncoder.encode(storedSku, StandardCharsets.UTF_8)
        val uri = origin.resolve("/api/media/daribar?sku=$encoded")
        val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).GET().build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (e: Exception) {
            throw AppException(ErrorCode.UPSTREAM_UNAVAILABLE, "Изображение недоступно", HttpStatus.BAD_GATEWAY, e)
        }
        response.body().use { body ->
            if (response.statusCode() != 200) {
                throw AppException(ErrorCode.UPSTREAM_UNAVAILABLE, "Изображение недоступно", HttpStatus.BAD_GATEWAY)
            }
            val type = response.headers().firstValue("Content-Type").orElse("").substringBefore(';').trim()
            if (type !in ALLOWED_TYPES) {
                throw AppException(ErrorCode.UPSTREAM_UNAVAILABLE, "Недопустимый тип изображения", HttpStatus.BAD_GATEWAY)
            }
            val bytes = body.readNBytes(MAX_BYTES + 1)
            if (bytes.isEmpty() || bytes.size > MAX_BYTES) {
                throw AppException(ErrorCode.UPSTREAM_UNAVAILABLE, "Недопустимый размер изображения", HttpStatus.BAD_GATEWAY)
            }
            return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(type))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
                .body(bytes)
        }
    }

    companion object {
        private val SKU = Regex("[A-Za-z0-9._:-]{1,96}")
        private val ALLOWED_TYPES = setOf("image/jpeg", "image/png", "image/webp")
        private const val MAX_BYTES = 5 * 1024 * 1024
    }
}
