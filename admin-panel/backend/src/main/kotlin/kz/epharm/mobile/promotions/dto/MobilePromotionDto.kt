package kz.epharm.mobile.promotions.dto

import kz.epharm.promo.dto.PromoTierDto
import java.time.LocalDate

/**
 * Карточка промо-товара для мобильной ленты фармацевта (главная + выбор при
 * загрузке чека). Объединяет данные товара (из Medusa) с полями акции (пороги/даты).
 *
 *  - id        — id промо-кампании (pr_*), его шлём с чеком при выборе акции;
 *  - productId — medusa product id (prod_*), для детали товара и сверки SKU чека;
 *  - tiers     — ценовые пороги [{minQty, price, bonus}] из админ-кампании.
 */
data class MobilePromotionDto(
    val id: String,
    val title: String,
    val productId: String,
    val name: String,
    val brand: String?,
    val mnn: String?,
    val rxOtc: String?,
    val imageUrl: String?,
    val barcode: String?,
    val category: String?,
    val categories: List<String>,
    val dateStart: LocalDate?,
    val dateEnd: LocalDate?,
    val tiers: List<PromoTierDto>,
)
