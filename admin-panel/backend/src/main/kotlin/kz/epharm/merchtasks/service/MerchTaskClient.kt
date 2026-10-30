package kz.epharm.merchtasks.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong
import kz.epharm.merchtasks.dto.MerchTaskDeliveryResult
import kz.epharm.merchtasks.dto.MerchTaskDto
import kz.epharm.merchtasks.dto.MerchTaskEnvelope
import kz.epharm.merchtasks.dto.MerchTaskShownRequest
import kz.epharm.merchtasks.dto.MerchProductBalance
import kz.epharm.merchtasks.dto.MerchRecommendationCandidate
import kz.epharm.merchtasks.dto.MerchRecommendationContextEnvelope
import kz.epharm.merchtasks.dto.MerchRecommendationContextRequest
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException

/**
 * Protected server-to-server gateway from ePharm to the merchandising service.
 * The integration credential stays on the backend and is never returned to POSM clients.
 */
@Component
class MerchTaskClient(
    private val objectMapper: ObjectMapper,
    @Value("\${app.merch-tasks.enabled:false}") private val enabled: Boolean,
    @Value("\${app.merch-tasks.base-url:}") private val baseUrl: String,
    @Value("\${app.merch-tasks.integration-key:}") private val integrationKey: String,
    @Value("\${app.merch-tasks.timeout-ms:7000}") timeoutMs: Int,
    @Value("\${app.public-base-url:https://epharm.inkar.kz}") publicBaseUrl: String,
    meterRegistry: MeterRegistry,
    @Value("\${app.merch-tasks.public-staff-url:}") publicStaffUrl: String = "",
    @Value("\${app.merch-tasks.max-concurrent:16}") maxConcurrent: Int = 16,
    @Value("\${app.merch-tasks.recommend-timeout-ms:900}") recommendTimeoutMs: Int = 900,
    private val nativeContext: NativeRecommendationContextReader? = null,
    @Value("\${app.merch.legacy-portal-cutover-enabled:false}") private val legacyCutover: Boolean = false,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val requestTimeoutMs = timeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
    private val recommendationTimeoutMs = recommendTimeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_RECOMMEND_TIMEOUT_MS)
    private val configured get() = baseUrl.isNotBlank() && integrationKey.isNotBlank() &&
        trustedTransport(baseUrl) && trustedStaffUrls != null
    // During migration accept both exact first-party staff pages; do not allow arbitrary /merch/* URLs.
    private val trustedStaffUrls = runCatching {
        listOfNotNull(
            publicBaseUrl.trimEnd('/') + "/merch/staff",
            publicStaffUrl.takeIf { it.isNotBlank() },
            publicBaseUrl.trimEnd('/') + "/tasks/staff",
        ).distinct().map { raw ->
            URI.create(raw).also { uri ->
                require(uri.scheme.equals("https", ignoreCase = true) && uri.host != null &&
                    uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                    uri.path.endsWith("/staff")) { "invalid public staff URL" }
            }
        }
    }.getOrNull()
    private val bulkhead = Semaphore(maxConcurrent.coerceIn(1, 64), true)
    private val lastWarningAt = ConcurrentHashMap<String, AtomicLong>()
    private val counters = mutableMapOf<Pair<String, String>, Counter>()
    private val registry = meterRegistry

    init {
        Gauge.builder("epharm.merch.tasks.enabled") { if (enabled) 1.0 else 0.0 }.register(registry)
        Gauge.builder("epharm.merch.tasks.configured") { if (configured) 1.0 else 0.0 }.register(registry)
    }

    private fun restClient(timeoutMs: Int): RestClient {
        val factory = object : SimpleClientHttpRequestFactory() {
            override fun prepareConnection(connection: HttpURLConnection, httpMethod: String) {
                super.prepareConnection(connection, httpMethod)
                // The custom integration header would otherwise survive a cross-host GET redirect.
                connection.instanceFollowRedirects = false
            }
        }.apply {
            setConnectTimeout(timeoutMs)
            setReadTimeout(timeoutMs)
        }
        return RestClient.builder()
            .baseUrl(baseUrl.trimEnd('/'))
            .requestFactory(factory)
            .defaultHeader(INTEGRATION_HEADER, integrationKey)
            .defaultStatusHandler({ it.is3xxRedirection }) { _, _ ->
                throw IllegalArgumentException("upstream redirect is not permitted")
            }
            .build()
    }

    private val rest: RestClient by lazy { restClient(requestTimeoutMs) }
    private val recommendationRest: RestClient by lazy { restClient(recommendationTimeoutMs) }

    fun activeTask(pharmacyId: String): MerchTaskEnvelope {
        val normalizedPharmacyId = pharmacyId.trim()
        if (normalizedPharmacyId.isEmpty() ||
            normalizedPharmacyId.length > 128 ||
            !normalizedPharmacyId.matches(SAFE_PHARMACY_ID)
        ) {
            throw AppException(
                ErrorCode.VALIDATION_FAILED,
                "Некорректный идентификатор аптеки",
                HttpStatus.BAD_REQUEST,
            )
        }
        if (!enabled) {
            counter("active", "disabled").increment()
            return MerchTaskEnvelope()
        }
        if (!configured) {
            counter("active", "misconfigured").increment()
            return MerchTaskEnvelope(available = false)
        }
        return upstream("active", MerchTaskEnvelope(available = false)) {
            rest.get().uri { builder ->
                builder.path("/api/integrations/pharmapay/tasks/active")
                    .queryParam("pharmacyId", normalizedPharmacyId)
                    .build()
            }.retrieve().body(MerchTaskEnvelope::class.java)
                ?.let(::normalizeTaskEnvelope)
                ?: MerchTaskEnvelope()
        }
    }

    private fun normalizeTaskEnvelope(response: MerchTaskEnvelope): MerchTaskEnvelope {
        if (!response.available) return response.copy(task = null)
        val task = response.task ?: return response
        validateTask(task)
        val now = Instant.now()
        return if (task.dueAt?.isAfter(now) == false || task.expiresAt?.isAfter(now) == false) {
            response.copy(task = null)
        } else {
            response
        }
    }

    fun markShown(payload: MerchTaskShownRequest): MerchTaskDeliveryResult {
        if (!enabled) {
            counter("shown", "disabled").increment()
            return MerchTaskDeliveryResult()
        }
        if (!configured) {
            counter("shown", "misconfigured").increment()
            return MerchTaskDeliveryResult(available = false)
        }
        if (!payload.dispatchId.matches(CANONICAL_UUID) ||
            payload.deliveryToken.length !in 32..256 || !payload.deliveryToken.matches(SAFE_TOKEN)
        ) {
            counter("shown", "invalid_receipt").increment()
            return MerchTaskDeliveryResult(available = false)
        }
        return upstream("shown", MerchTaskDeliveryResult(available = false), outcome = {
            if (it.accepted) "accepted" else if (!it.available) "unavailable" else "rejected"
        }) {
            // The fallback merchandising service uses Python's BaseHTTPRequestHandler, which
            // consumes bodies by Content-Length and does not decode HTTP chunked bodies. An
            // explicit byte array keeps this contract deterministic on both deployment targets.
            val body = objectMapper.writeValueAsBytes(payload)
            rest.post()
                .uri("/api/integrations/pharmapay/tasks/shown")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(MerchTaskDeliveryResult::class.java)
                ?: MerchTaskDeliveryResult()
        }
    }

    /**
     * Pharmacy-scoped recommendation and stock feed. It is intentionally fail-open and uses a
     * tighter timeout than QR tasks because a cashier scan must never wait on merchandising.
     */
    fun recommendationContext(payload: MerchRecommendationContextRequest): MerchRecommendationContextEnvelope {
        validatePharmacyId(payload.pharmacyId)
        if (payload.cart.size > MAX_CART_ITEMS || payload.requestedProducts.size > MAX_REQUESTED_PRODUCTS) {
            counter("recommendations", "invalid_request").increment()
            return MerchRecommendationContextEnvelope(available = false)
        }
        if (!enabled) {
            counter("recommendations", "disabled").increment()
            return MerchRecommendationContextEnvelope()
        }
        if (legacyCutover) {
            // Same bulkhead/metrics as legacy transport; native ownership never falls back to HTTP.
            return upstream("recommendations", MerchRecommendationContextEnvelope(available = false)) {
                nativeContext?.context(payload)?.also(::validateRecommendationContext)
                    ?: MerchRecommendationContextEnvelope(available = false)
            }
        }
        if (!configured) {
            counter("recommendations", "misconfigured").increment()
            return MerchRecommendationContextEnvelope(available = false)
        }
        return upstream("recommendations", MerchRecommendationContextEnvelope(available = false)) {
            val body = objectMapper.writeValueAsBytes(payload)
            recommendationRest.post()
                .uri("/api/integrations/pharmapay/recommendations/context")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(MerchRecommendationContextEnvelope::class.java)
                ?.also(::validateRecommendationContext)
                ?: MerchRecommendationContextEnvelope()
        }
    }

    private fun validatePharmacyId(value: String) {
        val normalized = value.trim()
        if (normalized.isEmpty() || normalized.length > 128 || !normalized.matches(SAFE_PHARMACY_ID)) {
            throw AppException(
                ErrorCode.VALIDATION_FAILED,
                "Некорректный идентификатор аптеки",
                HttpStatus.BAD_REQUEST,
            )
        }
    }

    private fun validateRecommendationContext(context: MerchRecommendationContextEnvelope) {
        require(context.recommendations.size <= MAX_RECOMMENDATIONS) { "too many recommendations" }
        require(context.balances.size <= MAX_BALANCES) { "too many balances" }
        context.recommendations.forEach(::validateRecommendation)
        context.balances.forEach(::validateBalance)
    }

    private fun validateRecommendation(candidate: MerchRecommendationCandidate) {
        require(candidate.id.length in 1..48 && candidate.id.matches(SAFE_EXTERNAL_ID)) {
            "invalid recommendation id"
        }
        require(candidate.kind == "substitution" || candidate.kind == "crosssell") {
            "invalid recommendation kind"
        }
        require(!candidate.triggerBarcode.isNullOrBlank() || !candidate.triggerIpartId.isNullOrBlank()) {
            "recommendation has no exact trigger"
        }
        require(!candidate.recommendProductId.isNullOrBlank() || !candidate.recommendBarcode.isNullOrBlank() ||
            !candidate.recommendIpartId.isNullOrBlank()) { "recommendation has no product key" }
        require(safeProductKey(candidate.triggerBarcode, 32) && safeProductKey(candidate.triggerIpartId, 64) &&
            safeProductKey(candidate.recommendProductId, 64) && safeProductKey(candidate.recommendBarcode, 32) &&
            safeProductKey(candidate.recommendIpartId, 64)) { "invalid recommendation product key" }
        require(candidate.script.length <= 1_200 && candidate.partnerLabel.orEmpty().length <= 80) {
            "recommendation text is too long"
        }
        require(candidate.advantages.size <= 12 && candidate.advantages.all { it.length <= 240 }) {
            "invalid recommendation advantages"
        }
        require(candidate.bonus in 0..1_000_000 && candidate.priority in -1_000..1_000) {
            "invalid recommendation rank"
        }
    }

    private fun validateBalance(balance: MerchProductBalance) {
        require(!balance.productId.isNullOrBlank() || !balance.barcode.isNullOrBlank() ||
            !balance.ipartId.isNullOrBlank()) { "balance has no product key" }
        require(safeProductKey(balance.productId, 64) && safeProductKey(balance.barcode, 32) &&
            safeProductKey(balance.ipartId, 64)) { "invalid balance product key" }
        require(balance.status in setOf("confirmed", "estimated", "stale", "unknown")) {
            "invalid balance status"
        }
        require(listOf(balance.pharmacyAvailableQty, balance.networkAvailableQty,
            balance.warehouseAvailableQty, balance.reservedQty).filterNotNull().all { it.signum() >= 0 }) {
            "negative balance"
        }
        require(balance.source.orEmpty().length <= 80) { "balance source is too long" }
    }

    private fun safeProductKey(value: String?, maxLength: Int): Boolean =
        value == null || (value.isNotBlank() && value.length <= maxLength && value.matches(SAFE_PRODUCT_KEY))

    private fun validateTask(task: MerchTaskDto) {
        require(task.id.matches(CANONICAL_UUID)) { "invalid task id" }
        require(task.deliveryToken.length in 32..256 && task.deliveryToken.matches(SAFE_TOKEN)) {
            "invalid delivery token"
        }
        require(task.title.length <= 512) { "task title is too long" }
        require(task.priority == null || task.priority.length <= 32) { "task priority is too long" }

        val publicUrl = URI.create(task.publicUrl)
        val trustedStaff = requireNotNull(trustedStaffUrls) { "unconfigured public staff URL" }
        require(
            trustedStaff.any { staff ->
                publicUrl.scheme.equals(staff.scheme, ignoreCase = true) &&
                    publicUrl.host.equals(staff.host, ignoreCase = true) &&
                    effectivePort(publicUrl) == effectivePort(staff) &&
                    publicUrl.rawPath == staff.rawPath &&
                    if (staff == trustedStaff.first()) {
                        // Legacy ePharm proxy links carry the token in the query until retired.
                        publicUrl.rawFragment == null && taskToken(publicUrl.rawQuery) == task.deliveryToken
                    } else {
                        // CRM's fixed marker avoids putting the bearer token in HTTP access logs.
                        publicUrl.rawQuery == "task=1" && taskToken(publicUrl.rawFragment) == task.deliveryToken
                    }
            } &&
                publicUrl.userInfo == null,
        ) { "untrusted task public URL" }
    }

    private fun taskToken(raw: String?): String? {
        if (raw == null || raw.length !in 6..512 || !raw.startsWith("task=")) return null
        val value = raw.substringAfter("task=")
        if (value.isEmpty() || value.contains('&')) return null
        return runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8) }.getOrNull()
    }

    private fun <T> upstream(operation: String, fallback: T, outcome: (T) -> String = { "success" }, call: () -> T): T {
        if (!bulkhead.tryAcquire()) {
            counter(operation, "saturated").increment()
            return fallback
        }
        return try {
            call().also { counter(operation, outcome(it)).increment() }
        } catch (e: Exception) {
            counter(operation, "failure").increment()
            if (shouldWarn(operation)) {
                log.warn("Merchandising operation={} failed-open: {}", operation, failureSummary(e))
            }
            log.debug("Merchandising failure details for operation={}", operation, e)
            fallback
        } finally {
            bulkhead.release()
        }
    }

    private fun shouldWarn(operation: String): Boolean {
        val now = System.currentTimeMillis()
        val last = lastWarningAt.computeIfAbsent(operation) { AtomicLong(0) }
        val previous = last.get()
        return now - previous >= WARNING_INTERVAL_MS && last.compareAndSet(previous, now)
    }

    private fun failureSummary(error: Exception): String = when (error) {
        is RestClientResponseException -> "HTTP ${error.statusCode.value()}"
        is IllegalArgumentException -> "invalid upstream payload"
        else -> error.javaClass.simpleName
    }

    private fun counter(operation: String, outcome: String): Counter = synchronized(counters) {
        counters.getOrPut(operation to outcome) {
            Counter.builder("epharm.merch.tasks.requests")
                .description("Calls from ePharm to the optional merchandising task service")
                .tag("operation", operation)
                .tag("outcome", outcome)
                .register(registry)
        }
    }

    private companion object {
        const val INTEGRATION_HEADER = "X-Pharmapay-Key"
        const val MIN_TIMEOUT_MS = 250
        const val MAX_TIMEOUT_MS = 30_000
        const val MAX_RECOMMEND_TIMEOUT_MS = 2_500
        const val WARNING_INTERVAL_MS = 60_000L
        const val MAX_CART_ITEMS = 200
        const val MAX_REQUESTED_PRODUCTS = 50
        const val MAX_RECOMMENDATIONS = 100
        const val MAX_BALANCES = 200
        val SAFE_PHARMACY_ID = Regex("[0-9A-Za-z._:+@/-]+")
        val CANONICAL_UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        val SAFE_TOKEN = Regex("[0-9A-Za-z._~+/=-]+")
        val SAFE_EXTERNAL_ID = Regex("[0-9A-Za-z._:-]+")
        val SAFE_PRODUCT_KEY = Regex("[0-9A-Za-z._:+@/-]+")
        val IPV4_OCTET = Regex("0|[1-9][0-9]{0,2}")

        /** A server credential must never cross the public Internet over plain HTTP. */
        fun trustedTransport(rawBaseUrl: String): Boolean {
            return try {
                val uri = URI.create(rawBaseUrl)
                val host = uri.host ?: return false
                if (uri.userInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
                    (uri.rawPath != null && uri.rawPath !in setOf("", "/"))
                ) return false
                when (uri.scheme?.lowercase()) {
                    "https" -> true
                    "http" -> host.equals("localhost", ignoreCase = true) ||
                        host == "127.0.0.1" || host == "[::1]" ||
                        host.split('.').let { labels ->
                            if (labels.size != 4 || !labels.all { it.matches(IPV4_OCTET) }) false
                            else labels.map(String::toInt).let { octets ->
                                octets.all { it in 0..255 } &&
                                    (octets[0] == 10 ||
                                        octets[0] == 172 && octets[1] in 16..31 ||
                                        octets[0] == 192 && octets[1] == 168)
                            }
                        }
                    else -> false
                }
            } catch (_: IllegalArgumentException) {
                false
            }
        }

        fun effectivePort(uri: URI): Int = when {
            uri.port >= 0 -> uri.port
            uri.scheme.equals("https", ignoreCase = true) -> 443
            else -> 80
        }
    }
}
