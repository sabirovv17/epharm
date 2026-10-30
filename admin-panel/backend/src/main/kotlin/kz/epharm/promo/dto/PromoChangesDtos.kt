package kz.epharm.promo.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import java.time.Instant

data class PromoChangesBundleDto(
    @field:Valid val campaign: UpdatePromoRequest,
    @field:Valid val rules: PromoRulesConfigDto,
)

data class SavePromoChangesRequest(
    @field:Min(0) val expectedRevision: Long,
    @field:Valid val bundle: PromoChangesBundleDto? = null,
    val discard: Boolean = false,
)

data class ApplyPromoChangesRequest(@field:Min(0) val expectedRevision: Long)

data class PromoChangesViewDto(
    val promoId: String,
    val revision: Long,
    val hasPendingChanges: Boolean,
    val bundle: PromoChangesBundleDto,
    val conflict: Boolean,
    val updatedAt: Instant?,
    val updatedBy: String?,
    val canSave: Boolean,
    val canApply: Boolean,
    val readinessIssues: List<String>,
)
