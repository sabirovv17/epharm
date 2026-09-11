package kz.epharm.merchtasks.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kz.epharm.merchtasks.dto.MerchTaskShownRequest
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

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
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val requestTimeoutMs = timeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
    private val active get() = enabled && baseUrl.isNotBlank() && integrationKey.isNotBlank()

    private val rest: RestClient by lazy {
        val factory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(requestTimeoutMs)
            setReadTimeout(requestTimeoutMs)
        }
        RestClient.builder()
            .baseUrl(baseUrl.trimEnd('/'))
            .requestFactory(factory)
            .defaultHeader(INTEGRATION_HEADER, integrationKey)
            .build()
    }

    fun activeTask(pharmacyId: String): JsonNode {
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
        if (!active) return objectMapper.createObjectNode().putNull("task")
        return upstream("active task") {
            rest.get().uri { builder ->
                builder.path("/api/integrations/pharmapay/tasks/active")
                    .queryParam("pharmacyId", normalizedPharmacyId)
                    .build()
            }.retrieve().body(JsonNode::class.java)
                ?: objectMapper.createObjectNode().putNull("task")
        }
    }

    fun markShown(payload: MerchTaskShownRequest): JsonNode {
        if (!active) return objectMapper.createObjectNode().put("accepted", false)
        return upstream("delivery receipt") {
            rest.post()
                .uri("/api/integrations/pharmapay/tasks/shown")
                .body(payload)
                .retrieve()
                .body(JsonNode::class.java)
                ?: objectMapper.createObjectNode().put("accepted", false)
        }
    }

    private fun <T> upstream(action: String, call: () -> T): T = try {
        call()
    } catch (e: Exception) {
        log.warn("Merchandising {} failed: {}", action, e.message)
        throw AppException(
            ErrorCode.UPSTREAM_UNAVAILABLE,
            "Сервис заданий временно недоступен",
            HttpStatus.BAD_GATEWAY,
            e,
        )
    }

    private companion object {
        const val INTEGRATION_HEADER = "X-Pharmapay-Key"
        const val MIN_TIMEOUT_MS = 250
        const val MAX_TIMEOUT_MS = 30_000
        val SAFE_PHARMACY_ID = Regex("[0-9A-Za-z._:+@/-]+")
    }
}
