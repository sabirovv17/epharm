package kz.epharm.merchtasks.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
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

private const val SAFE_ID = "[0-9A-Za-z._:+@/-]+"
private const val SAFE_DEVICE_ID = "[0-9A-Za-z._:+@() -]+"
private const val SAFE_TOKEN = "[0-9A-Za-z._~+/=-]+"
