package kz.epharm.medusa.service

import kz.epharm.medusa.client.MedusaClient
import kz.epharm.medusa.MedusaCatalogSnapshotRepository
import kz.epharm.medusa.dto.MedusaProduct
import kz.epharm.medusa.dto.toRetailPriceSummary
import kz.epharm.medusa.dto.scanBarcodes
import kz.epharm.storefront.client.StorefrontCatalogClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import kotlin.math.roundToLong

/**
 * Цена и идентификаторы товара из того же источника, что и каталог админки.
 * При включённой витрине сайта старый Medusa origin не участвует даже при сбое.
 * Имя класса сохранено для существующих кампаний и планировщика обновления цен.
 */
@Service
class MedusaPriceService(
    private val medusa: MedusaClient,
    private val storefront: StorefrontCatalogClient? = null,
    private val snapshot: MedusaCatalogSnapshotRepository? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    val active: Boolean get() = storefront?.active == true || medusa.active || snapshot?.hasCompleteSnapshot() == true

    /**
     * null сохраняет прежнюю цену кампании при недоступном источнике или отсутствии цены.
     */
    fun priceOf(medusaProductId: String?): Long? {
        val id = medusaProductId?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val nativePrice = priceOf(productOf(id))
        return nativePrice ?: if (storefront?.active != true && medusa.active) retailPriceOf(id) else null
    }

    /** Цена из уже загруженного товара (первый вариант с calculatedPrice). */
    fun priceOf(product: MedusaProduct?): Long? =
        product?.variants
            ?.firstNotNullOfOrNull { it.calculatedPrice?.calculatedAmount }
            ?.roundToLong()

    /**
     * Свежий снимок для кампаний: цена, обложка и явный штрихкод; отсутствующие поля
     * остаются nullable, чтобы вызывающий не затирал ранее сохранённые значения.
     */
    fun snapshotOf(medusaProductId: String?): MedusaSnapshot? {
        val id = medusaProductId?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val p = productOf(id) ?: return null
        val barcodes = p.scanBarcodes()
        return MedusaSnapshot(
            price = priceOf(p) ?: if (storefront?.active != true && medusa.active) retailPriceOf(id) else null,
            cover = coverOf(p),
            barcode = barcodes.firstOrNull(),
            barcodes = barcodes,
            barcodeSourceComplete = p.metadata?.get("barcode_source_complete") == true,
        )
    }

    private fun productOf(id: String): MedusaProduct? {
        val local = snapshot?.takeIf { it.hasCompleteSnapshot() }?.findById(id)
        val site = storefront?.takeIf { it.active }
        if (site != null) {
            val fresh = runCatching { site.getProduct(id, local?.handle) }
                .onFailure { log.warn("Не удалось обновить товар витрины {}: {}", id, it.message) }
                .getOrNull()
            // The historical snapshot must not overwrite a website campaign during an outage.
            return fresh ?: site.cachedProduct(id)
        }
        val legacy = if (medusa.active) runCatching { medusa.getProduct(id) }
            .onFailure { log.warn("Не удалось получить товар Medusa {}: {}", id, it.message) }
            .getOrNull() else null
        return legacy ?: local
    }

    private fun retailPriceOf(medusaProductId: String): Long? =
        runCatching { medusa.getProductPharmacies(medusaProductId)?.toRetailPriceSummary()?.price?.toLong() }
            .onFailure { log.warn("Не удалось получить розничную цену Medusa {}: {}", medusaProductId, it.message) }
            .getOrNull()

    /** Обложка товара: thumbnail, иначе первое непустое фото. */
    private fun coverOf(product: MedusaProduct): String? =
        product.thumbnail?.trim()?.takeIf { it.isNotBlank() }
            ?: product.images.firstNotNullOfOrNull { it.url?.trim()?.takeIf { u -> u.isNotBlank() } }

    /** Снимок витрины: цена (тенге) + URL обложки + штрих-код. Любое поле может быть null. */
    data class MedusaSnapshot(
        val price: Long?, val cover: String?, val barcode: String?, val barcodes: List<String> = emptyList(),
        val barcodeSourceComplete: Boolean = false,
    )
}
