package kz.epharm.merchtasks.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.math.BigDecimal
import java.time.Instant
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

/**
 * Stable ePharm <-> merchandising contract. Unknown upstream fields are intentionally ignored so
 * additive changes remain backwards compatible. `available=false` is an ePharm fail-open signal:
 * the optional merchandising service is unavailable, while the POSM core remains healthy.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class MerchTaskEnvelope(
    val task: MerchTaskDto? = null,
    val available: Boolean = true,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MerchTaskDto(
    val id: String = "",
    val title: String = "",
    val priority: String? = null,
    val dueAt: Instant? = null,
    val expiresAt: Instant? = null,
    val publicUrl: String = "",
    val deliveryToken: String = "",
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MerchTaskDeliveryResult(
    val accepted: Boolean = false,
    val available: Boolean = true,
)

/** Receipt sent after a task QR code is actually visible on a pharmacy device. */
data class MerchTaskShownRequest(
    @field:NotBlank
    @field:Size(max = 128)
    @field:Pattern(regexp = SAFE_ID)
    val dispatchId: String,

    @field:NotBlank
    @field:Size(max = 128)
    @field:Pattern(regexp = SAFE_ID)
    val pharmacyId: String,

    @field:NotBlank
    @field:Size(max = 128)
    @field:Pattern(regexp = SAFE_DEVICE_ID)
    val deviceId: String,

    @field:NotBlank
    @field:Size(max = 2048)
    @field:Pattern(regexp = SAFE_TOKEN)
    val deliveryToken: String,
)

/** Minimal product reference exchanged with merchandising; no pharmacist identity leaves ePharm. */
data class MerchRecommendationProductRef(
    val productId: String? = null,
    val barcode: String? = null,
    val ipartId: String? = null,
)

data class MerchRecommendationCartItem(
    val sku: String? = null,
    val barcode: String? = null,
    val name: String? = null,
    val qty: Double = 1.0,
)

data class MerchRecommendationContextRequest(
    val pharmacyId: String,
    val cart: List<MerchRecommendationCartItem>,
    val requestedProducts: List<MerchRecommendationProductRef> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MerchRecommendationContextEnvelope(
    val recommendations: List<MerchRecommendationCandidate> = emptyList(),
    val balances: List<MerchProductBalance> = emptyList(),
    val available: Boolean = true,
    val pharmacyMapped: Boolean = true,
    val generatedAt: Instant? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MerchRecommendationCandidate(
    val id: String = "",
    val kind: String = "",
    val triggerBarcode: String? = null,
    val triggerIpartId: String? = null,
    val recommendProductId: String? = null,
    val recommendBarcode: String? = null,
    val recommendIpartId: String? = null,
    val script: String = "",
    val advantages: List<String> = emptyList(),
    val partnerLabel: String? = null,
    val bonus: Int = 0,
    val priority: Int = 0,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MerchProductBalance(
    val productId: String? = null,
    val barcode: String? = null,
    val ipartId: String? = null,
    val pharmacyAvailableQty: BigDecimal? = null,
    val networkAvailableQty: BigDecimal? = null,
    val warehouseAvailableQty: BigDecimal? = null,
    val reservedQty: BigDecimal? = null,
    val observedAt: Instant? = null,
    val source: String? = null,
    val status: String = "unknown",
)

private const val SAFE_ID = "[0-9A-Za-z._:+@/-]+"
private const val SAFE_DEVICE_ID = "[0-9A-Za-z._:+@() -]+"
private const val SAFE_TOKEN = "[0-9A-Za-z._~+/=-]+"
