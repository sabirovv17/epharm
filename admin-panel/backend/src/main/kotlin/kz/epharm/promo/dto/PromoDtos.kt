package kz.epharm.promo.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import kz.epharm.promo.entity.PromoEntity
import kz.epharm.promo.entity.PromoStatus
import kz.epharm.promo.entity.PromoTier
import java.time.Instant
import java.time.LocalDate

/** Ценовой порог акции (зеркало [PromoTier]). */
data class PromoTierDto(
    @field:Min(1)
    val minQty: Int,
    @field:Min(0)
    val price: Long,
    @field:Min(0)
    val bonus: Long = 0,
) {
    fun toEntity() = PromoTier(minQty = minQty, price = price, bonus = bonus)

    companion object {
        fun of(t: PromoTier) = PromoTierDto(minQty = t.minQty, price = t.price, bonus = t.bonus)
    }
}

/**
 * Полный DTO промо-кампании. С V022 — товарная акция: линк на товар Medusa,
 * снимок товара, диапазон дат и ценовые пороги. Старые campaign-поля
 * (brand/period/budget/spent/kpi/cover/pharmacies) сохранены для совместимости.
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
    // Товарная акция:
    val medusaProductId: String?,
    val productName: String,
    val productImage: String?,
    val dateStart: LocalDate?,
    val dateEnd: LocalDate?,
    val tiers: List<PromoTierDto>,
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
            medusaProductId = e.medusaProductId,
            productName = e.productName,
            productImage = e.productImage,
            dateStart = e.dateStart,
            dateEnd = e.dateEnd,
            tiers = e.tiers.map(PromoTierDto::of),
            createdBy = e.createdBy,
            createdAt = e.createdAt,
            updatedAt = e.updatedAt,
        )
    }
}

/**
 * Создание промо-кампании. Под товарную акцию админ передаёт выбранный товар
 * Medusa (medusaProductId + снимок name/image/brand из витрины) + даты + пороги.
 * Поля товара необязательны — кампанию без товара (старый сценарий) тоже можно
 * создать (она просто не попадёт в мобильную ленту).
 */
data class CreatePromoRequest(
    @field:NotBlank
    @field:Size(max = 255)
    val title: String,
    val status: PromoStatus? = PromoStatus.draft,
    @field:Size(max = 128)
    val brand: String = "",
    @field:Size(max = 64)
    val period: String = "",
    @field:Min(0)
    val budget: Long = 0,
    @field:Size(max = 255)
    val kpi: String = "",
    @field:Size(max = 16)
    val cover: String = "",
    // Товарная акция:
    @field:Size(max = 64)
    val medusaProductId: String? = null,
    @field:Size(max = 255)
    val productName: String = "",
    @field:Size(max = 1024)
    val productImage: String? = null,
    val dateStart: LocalDate? = null,
    val dateEnd: LocalDate? = null,
    @field:Valid
    val tiers: List<PromoTierDto> = emptyList(),
)

/**
 * Partial-update. Метрики (spent, pharmacies) не патчатся вручную — пишутся
 * из других доменов (Reconcile-flow, Pharmacy-link). tiers=null → не трогаем,
 * tiers=[] → очистить пороги.
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
    // Товарная акция:
    @field:Size(max = 64)
    val medusaProductId: String? = null,
    @field:Size(max = 255)
    val productName: String? = null,
    @field:Size(max = 1024)
    val productImage: String? = null,
    val dateStart: LocalDate? = null,
    val dateEnd: LocalDate? = null,
    @field:Valid
    val tiers: List<PromoTierDto>? = null,
)
