package kz.epharm.posm.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

data class UpsertPosmPharmacistMappingRequest(
    @field:NotBlank @field:Size(max = 64)
    val pharmacyId: String,
    @field:NotBlank @field:Size(max = 128)
    val externalUserId: String,
    @field:Size(max = 255)
    val externalUserName: String? = null,
    @field:NotBlank @field:Size(max = 64)
    val pharmacistId: String,
)

data class PosmPharmacistMappingDto(
    val id: String,
    val pharmacyId: String,
    val pharmacyName: String,
    val externalUserId: String,
    val externalUserName: String?,
    val pharmacistId: String,
    val pharmacistName: String,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val revokedAt: Instant?,
)

data class UnmappedPosmSellerDto(
    val pharmacyId: String,
    val pharmacyName: String,
    val externalUserId: String,
    val externalUserName: String?,
    val salesCount: Long,
    val lastSeenAt: Instant,
)
