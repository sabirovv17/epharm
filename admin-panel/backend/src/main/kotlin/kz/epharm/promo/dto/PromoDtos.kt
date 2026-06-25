package kz.epharm.promo.dto

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import kz.epharm.promo.entity.PromoEntity
import kz.epharm.promo.entity.PromoStatus
import kz.epharm.promo.entity.PromoTier
import java.time.Instant
import java.time.LocalDate

/**
 * Ценовой порог акции (зеркало [PromoTier]). С T1 — один порог на кампанию
 * (1 кампания = 1 товар): {minQty:1, price:<из Medusa>, bonus:<бонус фармацевту>}.
 * Оставлен для совместимости мобильной ленты, которая рендерит tiers.
 */
data class PromoTierDto(
    val minQty: Int,
    val price: Long,
    val bonus: Long = 0,
) {
    fun toEntity() = PromoTier(minQty = minQty, price = price, bonus = bonus)

    companion object {
        fun of(t: PromoTier) = PromoTierDto(minQty = t.minQty, price = t.price, bonus = t.bonus)
    }
}

/**
 * Полный DTO промо-кампании. С T1 модель упрощена: 1 кампания = 1 товар Medusa,
 * цена read-only (из Medusa, обновляется планировщиком), бонус фармацевту задаётся в админке,
 * фото/описание можно переопределить вручную (override-поля).
 * Старые campaign-поля (brand/period/budget/spent/kpi/cover/pharmacies) — для совместимости.
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
    /** Штрих-код EAN-13 продвигаемого товара (из Medusa). */
    val barcode: String?,
    /** iPartID продвигаемого товара в кассе Стандарт-Н. */
    val ipartId: String?,
    /** Ручная замена фото (приоритет над Medusa). */
    val overrideImage: String?,
    /** Ручная замена описания (приоритет над Medusa). */
    val overrideDescription: String?,
    /** Ручная замена характеристик (по строке; приоритет над keyFacts Medusa). */
    val overrideCharacteristics: String?,
    /** Цена товара (read-only, из Medusa). */
    val price: Long,
    /** Бонус фармацевту за продажу (задаётся в админке). */
    val pharmacistBonus: Long,
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
            barcode = e.barcode,
            ipartId = e.ipartId,
            overrideImage = e.overrideImage,
            overrideDescription = e.overrideDescription,
            overrideCharacteristics = e.overrideCharacteristics,
            price = e.price,
            pharmacistBonus = e.pharmacistBonus,
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
 * Создание промо-кампании (T1: 1 кампания = 1 товар).
 *  - medusaProductId + productName/productImage — выбранный товар витрины (снимок для деградации);
 *  - pharmacistBonus — сколько получит фармацевт за продажу (единственный задаваемый бонус);
 *  - overrideImage/overrideDescription — ручная замена фото/описания (необязательно);
 *  - ЦЕНА не принимается из запроса — берётся из Medusa (read-only), кладётся в порог сервисом.
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
    /** Штрих-код EAN-13 продвигаемого товара (из Medusa, для матчинга кассы). */
    @field:Size(max = 32)
    val barcode: String? = null,
    /** iPartID продвигаемого товара в кассе Стандарт-Н (ручной ключ матчинга). */
    @field:Size(max = 64)
    val ipartId: String? = null,
    @field:Size(max = 1024)
    val overrideImage: String? = null,
    val overrideDescription: String? = null,
    val overrideCharacteristics: String? = null,
    @field:Min(0)
    val pharmacistBonus: Long = 0,
    val dateStart: LocalDate? = null,
    val dateEnd: LocalDate? = null,
)

/**
 * Partial-update. Метрики (spent, pharmacies) не патчатся вручную. Цена не патчится
 * (read-only из Medusa). Для очистки override-полей: передать пустую строку.
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
    /** Штрих-код EAN-13 продвигаемого товара (из Medusa, для матчинга кассы). */
    @field:Size(max = 32)
    val barcode: String? = null,
    /** iPartID продвигаемого товара в кассе Стандарт-Н (ручной ключ матчинга). */
    @field:Size(max = 64)
    val ipartId: String? = null,
    @field:Size(max = 1024)
    val overrideImage: String? = null,
    val overrideDescription: String? = null,
    val overrideCharacteristics: String? = null,
    @field:Min(0)
    val pharmacistBonus: Long? = null,
    val dateStart: LocalDate? = null,
    val dateEnd: LocalDate? = null,
)
