package kz.epharm.fulfillment.dto

import java.math.BigDecimal
import java.time.Instant

data class StorefrontOrderLineRequest(
    val productId: String,
    val sku: String = "",
    val title: String,
    val quantity: Int,
    val unitPrice: BigDecimal?,
)

data class StorefrontOrderCreatedRequest(
    val eventId: String,
    val orderId: String,
    val number: String,
    val pharmacyExternalId: String,
    val createdAt: Instant,
    val total: BigDecimal,
    val currency: String,
    val delivery: String,
    val paymentMethod: String,
    val paymentStatus: String,
    val demo: Boolean = false,
    val pickupCode: String,
    val lines: List<StorefrontOrderLineRequest>,
)

data class StorefrontOrderAck(
    val orderId: String,
    val accepted: Boolean,
    val status: String,
    val version: Long,
    val assigned: Boolean,
)

data class FulfillmentUpdateDto(
    val cursor: Long,
    val orderId: String,
    val status: String,
    val version: Long,
    val changedAt: Instant,
)

data class FulfillmentUpdateFeedDto(
    val after: Long,
    val nextCursor: Long,
    val hasMore: Boolean,
    val updates: List<FulfillmentUpdateDto>,
)

data class RegisterFulfillmentDeviceRequest(
    val deviceId: String,
    val pharmacyId: String,
)

data class RegisterFulfillmentDeviceResponse(
    val deviceId: String,
    val pharmacyId: String,
    val token: String,
)

data class FulfillmentLineDto(
    val productId: String,
    val sku: String,
    val title: String,
    val quantity: Int,
    val unitPrice: BigDecimal?,
)

data class FulfillmentOrderDto(
    val orderId: String,
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
    val status: String,
    val version: Long,
    val pickupAttempts: Int,
    val pickupLockedUntil: Instant?,
    val cancellationReason: String?,
    val completedAt: Instant?,
    val updatedAt: Instant,
    val lines: List<FulfillmentLineDto>,
)

data class FulfillmentOrderPageDto(
    val items: List<FulfillmentOrderDto>,
    val offset: Int,
    val limit: Int,
    val hasMore: Boolean,
)

data class FulfillmentActionRequest(
    val action: String,
    val expectedVersion: Long,
    val code: String? = null,
    val reason: String? = null,
    val cashCollected: Boolean = false,
)

data class FulfillmentAssignRequest(
    val pharmacyId: String,
    val expectedVersion: Long,
)

data class FulfillmentPharmacyLinkRequest(
    val externalId: String,
    val pharmacyId: String,
    val crmBranchId: String? = null,
    val active: Boolean = true,
)

data class FulfillmentPharmacyLinkDto(
    val externalId: String,
    val pharmacyId: String,
    val pharmacyName: String,
    val pharmacyAddress: String,
    val crmBranchId: String?,
    val active: Boolean,
    val updatedAt: Instant,
)

data class FulfillmentDeviceDto(
    val id: String,
    val deviceId: String,
    val pharmacyId: String,
    val pharmacyName: String,
    val active: Boolean,
    val secretHint: String,
    val registeredAt: Instant,
    val lastSeenAt: Instant,
    val revokedAt: Instant?,
)

data class FulfillmentFeatureStatusDto(
    val enabled: Boolean,
    val deviceRegistrationEnabled: Boolean,
)
