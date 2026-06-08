package kz.epharm.promo.dto

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import kz.epharm.promo.entity.PromoEntity
import kz.epharm.promo.entity.PromoStatus
import java.time.Instant

/**
 * Полный DTO промо-кампании. Зеркало frontend Promo type в fixtures.ts.
 */
data class PromoDto(
    val id: String,
    val title: String,
    val status: PromoStatus,
    val brand: String,
    val period: String,
    val pharmacies: Int,
    val budget: Long,
    val spent: Long,
    val kpi: String,
    val cover: String,
    val createdBy: String,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(e: PromoEntity): PromoDto = PromoDto(
            id = e.id,
            title = e.title,
            status = e.status,
            brand = e.brand,
            period = e.period,
            pharmacies = e.pharmacies,
            budget = e.budget,
            spent = e.spent,
            kpi = e.kpi,
            cover = e.cover,
            createdBy = e.createdBy,
            createdAt = e.createdAt,
            updatedAt = e.updatedAt,
        )
    }
}

data class CreatePromoRequest(
    @field:NotBlank
    @field:Size(max = 255)
    val title: String,
    val status: PromoStatus? = PromoStatus.draft,
    @field:NotBlank
    @field:Size(max = 128)
    val brand: String,
    @field:Size(max = 64)
    val period: String = "",
    @field:Min(0)
    val budget: Long = 0,
    @field:Size(max = 255)
    val kpi: String = "",
    @field:Size(max = 16)
    val cover: String = "",
)

/**
 * Partial-update. Метрики (spent, pharmacies) не патчатся вручную — пишутся
 * из других доменов (Reconcile-flow, Pharmacy-link).
 */
data class UpdatePromoRequest(
    val status: PromoStatus? = null,
    @field:Size(max = 255)
    val title: String? = null,
    @field:Size(max = 128)
    val brand: String? = null,
    @field:Size(max = 64)
    val period: String? = null,
    @field:Min(0)
    val budget: Long? = null,
    @field:Size(max = 255)
    val kpi: String? = null,
    @field:Size(max = 16)
    val cover: String? = null,
)
