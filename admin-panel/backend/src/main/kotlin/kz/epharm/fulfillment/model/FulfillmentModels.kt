package kz.epharm.fulfillment.model

import java.math.BigDecimal
import java.time.Instant

enum class FulfillmentStatus {
    submitted,
    assembling,
    ready,
    completed,
    cancelled,
}

enum class FulfillmentAction {
    assemble,
    ready,
    issue,
    cancel,
}

data class FulfillmentDeviceContext(
    val id: String,
    val deviceId: String,
    val pharmacyId: String,
)
data class FulfillmentOrderRecord(
    val orderId: String,
    val eventId: String,
    val number: String,
    val pharmacyExternalId: String,
    val pharmacyId: String?,
    val pharmacyName: String?,
    val pharmacyAddress: String?,
    val createdAt: Instant,
    val total: BigDecimal,
    val currency: String,
    val delivery: String,
    val paymentMethod: String,
    val paymentStatus: String,
    val demo: Boolean,
    val status: FulfillmentStatus,
    val version: Long,
    val pickupAttempts: Int,
    val pickupLockedUntil: Instant?,
    val cancellationReason: String?,
    val completedAt: Instant?,
    val updatedAt: Instant,
)
