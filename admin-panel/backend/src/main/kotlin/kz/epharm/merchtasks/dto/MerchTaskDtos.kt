package kz.epharm.merchtasks.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

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
