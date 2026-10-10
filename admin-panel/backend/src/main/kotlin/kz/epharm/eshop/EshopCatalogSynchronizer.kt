package kz.epharm.eshop

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PreDestroy
import kz.epharm.medusa.MedusaCatalogCache
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Streams one transaction-consistent NDJSON export and publishes only a verified full generation. */
@Component
class EshopCatalogSynchronizer(
    private val repository: EshopCatalogSnapshotRepository,
    private val cache: MedusaCatalogCache,
    private val json: ObjectMapper,
    @Value("\${app.eshop.catalog.sync-enabled:false}") private val syncEnabled: Boolean,
    @Value("\${app.eshop.catalog.base-url:}") baseUrl: String,
    @Value("\${app.eshop.catalog.token:}") private val token: String,
    @Value("\${app.eshop.catalog.connect-timeout-ms:3000}") connectTimeoutMs: Int,
    @Value("\${app.eshop.catalog.read-timeout-ms:240000}") readTimeoutMs: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "eshop-catalog-sync").apply { isDaemon = true }
    }
    private val sourceUri: URI? = if (!syncEnabled) null else validateOrigin(baseUrl, token)
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(connectTimeoutMs.toLong().coerceIn(250, 30_000)))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()
    private val requestTimeout = Duration.ofMillis(readTimeoutMs.toLong().coerceIn(5_000, 600_000))

    @Scheduled(
        initialDelayString = "\${app.eshop.catalog.initial-delay-ms:5000}",
        fixedDelayString = "\${app.eshop.catalog.refresh-seconds:900}000",
    )
    fun scheduledRefresh() {
        if (!syncEnabled || !running.compareAndSet(false, true)) return
        executor.execute {
            try {
                refreshNow()
            } catch (e: Exception) {
                log.warn("Shop catalogue sync failed; last complete generation retained: {}", e.message)
            } finally {
                running.set(false)
            }
        }
    }

    internal fun refreshNow(): Int {
        val uri = checkNotNull(sourceUri)
        val request = HttpRequest.newBuilder(uri.resolve("/v1/snapshot"))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/x-ndjson")
            .timeout(requestTimeout)
            .GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        response.body().use { body ->
            check(response.statusCode() == 200) { "Shop exporter returned HTTP ${response.statusCode()}" }
            check(response.headers().firstValue("Content-Type").orElse("").startsWith("application/x-ndjson")) {
                "Shop exporter returned unexpected content type"
            }
            return ingest(body)
        }
    }

    /** Separated from transport so count, digest, ordering and failure retention are testable. */
    internal fun ingest(input: InputStream): Int {
        val generation = UUID.randomUUID()
        val digest = MessageDigest.getInstance("SHA-256")
        var expected = -1
        var count = 0
        var previousSku: String? = null
        var header: JsonNode? = null
        var footerSeen = false
        var published = false
        val batch = ArrayList<EshopCatalogSnapshotRepository.ProductRow>(500)
        try {
            repository.beginSync()
            for (line in lines(input)) {
                val node = json.readTree(line)
                when (node.path("type").asText()) {
                    "header" -> {
                        check(header == null && count == 0) { "Duplicate or late shop header" }
                        check(node.path("schemaVersion").asInt(-1) == 1) { "Unsupported shop schema" }
                        expected = node.path("catalogCount").asInt(-1)
                        check(expected in 1..MAX_PRODUCTS) { "Invalid shop catalog count" }
                        check(node.requiredText("catalogRunId").length <= 128)
                        check((node.optionalText("availabilityRunId")?.length ?: 0) <= 128)
                        Instant.parse(node.requiredText("catalogGeneratedAt"))
                        node.optionalText("availabilityFinishedAt")?.let(Instant::parse)
                        header = node
                    }
                    "product" -> {
                        check(header != null && !footerSeen) { "Shop product outside header/footer" }
                        check(count < expected) { "More shop products than declared" }
                        val sku = node.requiredText("sku")
                        // PostgreSQL's SKU ordering follows its collation, which may
                        // differ from JVM lexical order. The PK catches non-adjacent
                        // duplicates; this check catches adjacent duplicates early.
                        check(previousSku != sku) { "Duplicate adjacent shop SKU" }
                        previousSku = sku
                        val product = node.path("product")
                        check(product.isObject) { "Shop product payload is missing" }
                        val productId = node.requiredText("productId")
                        val variantId = node.optionalText("variantId")
                        check(product.requiredText("id") == productId && product.requiredText("sku") == sku &&
                            product.optionalText("variantId") == variantId) {
                            "Shop product identity differs from envelope for SKU $sku"
                        }
                        val priceNode = node.path("priceAmount")
                        val price = if (priceNode.isNull || priceNode.isMissingNode) null else {
                            check(priceNode.isNumber) { "Invalid shop price" }
                            val exact = BigDecimal(priceNode.asText())
                            check(exact > BigDecimal.ZERO && exact <= BigDecimal.valueOf(100_000_000) &&
                                exact.scale() <= 2) {
                                "Invalid shop price"
                            }
                            exact
                        }
                        val publishedNode = node.path("published")
                        check(publishedNode.isBoolean) { "Shop publication state is missing" }
                        check(publishedNode.booleanValue() == (variantId != null && price != null)) {
                            "Shop publication/price mismatch for SKU $sku"
                        }
                        val aliases = node.path("aliasIds")
                        check(aliases.isArray && aliases.all(JsonNode::isTextual)) { "Invalid verified aliases" }
                        batch += EshopCatalogSnapshotRepository.ProductRow(
                            sku = sku,
                            productId = productId,
                            variantId = variantId,
                            raw = product,
                            priceAmount = price,
                            published = publishedNode.booleanValue(),
                            aliasIds = aliases.map { it.asText() },
                        )
                        digest.update(line)
                        digest.update('\n'.code.toByte())
                        count++
                        if (batch.size == 500) {
                            repository.upsertBatch(generation, count - batch.size, batch)
                            batch.clear()
                        }
                    }
                    "footer" -> {
                        check(header != null && !footerSeen) { "Duplicate or early shop footer" }
                        check(node.path("productCount").asInt(-1) == expected && count == expected) {
                            "Incomplete shop export"
                        }
                        val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                        check(node.requiredText("sha256").equals(actualHash, ignoreCase = true)) {
                            "Shop export checksum mismatch"
                        }
                        footerSeen = true
                    }
                    else -> error("Unknown shop export line type")
                }
            }
            check(footerSeen && count == expected) { "Shop export ended before verified footer" }
            if (batch.isNotEmpty()) repository.upsertBatch(generation, count - batch.size, batch)
            val source = checkNotNull(header)
            repository.completeSync(
                generation = generation,
                expectedCount = expected,
                catalogRunId = source.requiredText("catalogRunId"),
                availabilityRunId = source.optionalText("availabilityRunId"),
                catalogGeneratedAt = Instant.parse(source.requiredText("catalogGeneratedAt")),
                availabilityFinishedAt = source.optionalText("availabilityFinishedAt")?.let(Instant::parse),
            )
            published = true
            runCatching { cache.clear() }
                .onFailure { log.warn("Shop catalogue published but cache clear failed: {}", it.message) }
            log.info("Verified shop catalogue generation: {} products", count)
            return count
        } catch (e: Exception) {
            if (!published) {
                runCatching { repository.failSync(generation, e.message ?: e.javaClass.simpleName) }
            }
            throw e
        }
    }

    private fun lines(input: InputStream): Sequence<ByteArray> = sequence {
        val stream = BufferedInputStream(input)
        var totalBytes = 0L
        val buffer = java.io.ByteArrayOutputStream()
        while (true) {
            val byte = stream.read()
            if (byte == -1) {
                check(buffer.size() == 0) { "Shop export lacks final LF" }
                break
            }
            totalBytes++
            check(totalBytes <= MAX_EXPORT_BYTES) { "Shop export exceeds size limit" }
            if (byte == '\n'.code) {
                val line = buffer.toByteArray()
                check(line.isNotEmpty() && line.last() != '\r'.code.toByte()) { "Invalid NDJSON line ending" }
                yield(line)
                buffer.reset()
            } else {
                buffer.write(byte)
                check(buffer.size() <= MAX_LINE_BYTES) { "Shop export line exceeds size limit" }
            }
        }
    }

    private fun JsonNode.requiredText(field: String): String =
        optionalText(field)
            ?: error("Missing shop export $field")

    private fun JsonNode.optionalText(field: String): String? =
        path(field).takeIf(JsonNode::isTextual)?.asText()?.trim()?.takeIf(String::isNotBlank)

    private fun validateOrigin(raw: String, secret: String): URI {
        check(secret.isNotBlank()) { "ESHOP_CATALOG_TOKEN is required when sync is enabled" }
        val uri = runCatching { URI(raw.trim()) }.getOrElse { error("Invalid ESHOP_CATALOG_BASE_URL") }
        check(uri.isAbsolute && uri.host != null && uri.userInfo == null && uri.rawQuery == null &&
            uri.rawFragment == null && uri.path.orEmpty() in setOf("", "/")) {
            "ESHOP_CATALOG_BASE_URL must be an origin"
        }
        check(uri.scheme == "https" ||
            (uri.scheme == "http" && uri.host == "10.10.1.80" && uri.port == 13105)) {
            "Shop exporter must use HTTPS or pinned private 10.10.1.80:13105"
        }
        return uri
    }

    @PreDestroy
    fun close() = executor.shutdownNow()

    companion object {
        private const val MAX_PRODUCTS = 1_000_000
        private const val MAX_LINE_BYTES = 1_048_576
        private const val MAX_EXPORT_BYTES = 536_870_912L
    }
}
