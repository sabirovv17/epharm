package kz.epharm.mobile.promotions.service

import kz.epharm.promo.entity.PromoEntity
import kz.epharm.promo.entity.PromoStatus
import java.time.LocalDate
import java.time.ZoneId

/** One visibility rule for the mobile catalogue and promotion feed. */
object MobilePromoEligibility {
    private val zone = ZoneId.of("Asia/Almaty")

    fun today(): LocalDate = LocalDate.now(zone)

    fun includes(promo: PromoEntity, today: LocalDate): Boolean =
        promo.status == PromoStatus.active && !promo.medusaProductId.isNullOrBlank() &&
            (promo.dateStart == null || !today.isBefore(promo.dateStart)) &&
            (promo.dateEnd == null || !today.isAfter(promo.dateEnd)) &&
            promo.tiers.isNotEmpty() && promo.tiers.all {
                it.minQty > 0 && it.price > 0 && it.bonus >= 0
            } && promo.tiers.zipWithNext().all { (left, right) -> left.minQty < right.minQty }
}
