package kz.epharm.shared.storage

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.net.URI
import java.util.UUID

/**
 * Реальное S3-совместимое хранилище (MinIO в dev, Yandex Object Storage в prod).
 * Bucket `epharm-receipts` создаётся docker-compose minio-init и помечен
 * anonymous download (public-read) → URL вида `$publicUrl/$bucket/screens/<uuid>.<ext>`
 * проигрывается прямо в браузере (<img>/<video src>), без presigned-URL.
 *
 * ВАЖНО (прод за reverse-proxy): [endpoint] — внутренний адрес для SDK PUT/DELETE
 * (напр. http://minio:9000), а [publicUrl] — ВНЕШНИЙ адрес для ссылки в браузере
 * (напр. https://s3.epharm.kz). По умолчанию publicUrl = endpoint (dev: один localhost).
 *
 * Path-style доступ (pathStyleAccessEnabled) обязателен для MinIO.
 * Активен во всех профилях, КРОМЕ test (там InMemoryMediaStorage).
 */
@Component
@Profile("!test")
class S3MediaStorage(
    @Value("\${app.s3.endpoint}") private val endpoint: String,
    @Value("\${app.s3.public-url}") private val publicUrl: String,
    @Value("\${app.s3.region}") private val region: String,
    @Value("\${app.s3.access-key}") private val accessKey: String,
    @Value("\${app.s3.secret-key}") private val secretKey: String,
    @Value("\${app.s3.bucket-receipts}") private val bucket: String,
) : MediaStorage {

    private val log = LoggerFactory.getLogger(S3MediaStorage::class.java)

    private val client: S3Client = S3Client.builder()
        .endpointOverride(URI.create(endpoint))
        .region(Region.of(region))
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .build()

    override fun upload(bytes: ByteArray, contentType: String, originalName: String): String {
        val ext = extOf(originalName, contentType)
        val key = "screens/${UUID.randomUUID()}$ext"
        client.putObject(
            PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .build(),
            RequestBody.fromBytes(bytes),
        )
        // Ссылка для браузера строится по ВНЕШНЕМУ publicUrl (а PUT шёл на внутренний endpoint).
        val url = "${publicUrl.trimEnd('/')}/$bucket/$key"
        log.info("Загружен медиа-объект: {}", url)
        return url
    }

    override fun delete(url: String) {
        val marker = "/$bucket/"
        val idx = url.indexOf(marker)
        if (idx < 0) return
        val key = url.substring(idx + marker.length)
        runCatching {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build())
        }.onFailure { log.warn("Не удалось удалить объект {}: {}", key, it.message) }
    }

    private fun extOf(name: String, contentType: String): String {
        val dot = name.lastIndexOf('.')
        if (dot in 0 until name.length - 1) return name.substring(dot).lowercase()
        return when {
            contentType.contains("mp4") -> ".mp4"
            contentType.contains("webm") -> ".webm"
            contentType.contains("png") -> ".png"
            contentType.contains("jpeg") || contentType.contains("jpg") -> ".jpg"
            else -> ""
        }
    }
}
