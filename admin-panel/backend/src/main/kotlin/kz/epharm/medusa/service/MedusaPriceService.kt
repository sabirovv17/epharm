package kz.epharm.medusa.service

import kz.epharm.medusa.client.MedusaClient
import kz.epharm.medusa.dto.MedusaProduct
import kz.epharm.medusa.dto.toRetailPriceSummary
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
        val nativePrice = try {
            priceOf(medusa.getProduct(medusaProductId))
        } catch (e: Exception) {
            log.warn("Не удалось получить цену Medusa для {}: {}", medusaProductId, e.message)
            null
        }
        return nativePrice ?: retailPriceOf(medusaProductId)
    }

    /** Цена из уже загруженного товара (первый вариант с calculatedPrice). */
    fun priceOf(product: MedusaProduct?): Long? =
        product?.variants
            ?.firstNotNullOfOrNull { it.calculatedPrice?.calculatedAmount }
            ?.roundToLong()

    /**
     * Снимок товара для ежедневного рефреша: актуальные цена + обложка одним
     * запросом к Medusa (фото в витрине тоже меняют, не только цены). null —
     * Medusa выключена/недоступна. Поля внутри тоже nullable: чего нет — не трогаем.
     */
    fun snapshotOf(medusaProductId: String?): MedusaSnapshot? {
        if (medusaProductId.isNullOrBlank() || !medusa.active) return null
        return try {
            val p = medusa.getProduct(medusaProductId) ?: return null
            MedusaSnapshot(
                price = priceOf(p) ?: retailPriceOf(medusaProductId),
                cover = coverOf(p),
                barcode = barcodeOf(p),
            )
        } catch (e: Exception) {
            log.warn("Не удалось получить снимок Medusa для {}: {}", medusaProductId, e.message)
            null
        }
    }

    private fun retailPriceOf(medusaProductId: String): Long? =
        medusa.getProductPharmacies(medusaProductId)?.toRetailPriceSummary()?.price?.toLong()

    /** Обложка товара: thumbnail, иначе первое непустое фото. */
    private fun coverOf(product: MedusaProduct): String? =
        product.thumbnail?.trim()?.takeIf { it.isNotBlank() }
            ?: product.images.firstNotNullOfOrNull { it.url?.trim()?.takeIf { u -> u.isNotBlank() } }

    /**
     * Штрих-код EAN-13 товара: первый вариант с непустым barcode (зеркало
     * [kz.epharm.mobile.catalog.service.MobileCatalogService.barcodeOf]). Без metadata-fallback —
     * для матчинга кассы нужен именно EAN-13 из variant.barcode.
     */
    private fun barcodeOf(product: MedusaProduct): String? =
        product.variants.firstOrNull { !it.barcode.isNullOrBlank() }?.barcode?.trim()

    /** Снимок витрины: цена (тенге) + URL обложки + штрих-код. Любое поле может быть null. */
    data class MedusaSnapshot(val price: Long?, val cover: String?, val barcode: String?)
}
