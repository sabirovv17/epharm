package kz.epharm.medusa.service

import kz.epharm.medusa.client.MedusaClient
import kz.epharm.medusa.dto.MedusaProduct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import kotlin.math.roundToLong

/**
 * Единый резолвер цены товара из Medusa (variants.calculatedPrice).
 *
 * В отличие от [kz.epharm.mobile.catalog.service.MobileCatalogService] — БЕЗ кэша:
 * нужен для ежедневного рефреша цен (планировщик) и для подстановки цены при создании
 * кампании, где важна актуальность, а не экономия round-trip'ов.
 *
 * Цена «только из Medusa» — пользователь не может задать её руками (T1).
 */
@Service
class MedusaPriceService(
    private val medusa: MedusaClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Активен, только если активен сам клиент Medusa (иначе цену взять неоткуда). */
    val active: Boolean get() = medusa.active

    /**
     * Цена товара в тенге (целое). null — Medusa выключена/недоступна или у товара нет цены.
     * Бросать наверх не нужно: вызывающий (создание промо / планировщик) деградирует на
     * прошлое значение.
     */
    fun priceOf(medusaProductId: String?): Long? {
        if (medusaProductId.isNullOrBlank() || !medusa.active) return null
        return try {
            priceOf(medusa.getProduct(medusaProductId))
        } catch (e: Exception) {
            log.warn("Не удалось получить цену Medusa для {}: {}", medusaProductId, e.message)
            null
        }
    }

    /** Цена из уже загруженного товара (первый вариант с calculatedPrice). */
    fun priceOf(product: MedusaProduct?): Long? =
        product?.variants
            ?.firstNotNullOfOrNull { it.calculatedPrice?.calculatedAmount }
            ?.roundToLong()
}
