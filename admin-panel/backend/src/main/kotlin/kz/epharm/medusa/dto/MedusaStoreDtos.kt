package kz.epharm.medusa.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import kotlin.math.roundToInt

/**
 * DTO ответов Medusa Store API (v2.15.2) — витрина inkar.kz (Module 3).
 *
 * Мы парсим ТОЛЬКО нужные поля; всё прочее игнорируется (`ignoreUnknown = true`) —
 * чужой бэкенд эволюционирует независимо, добавление полей не должно ронять прокси.
 *
 * Реальный каталог сейчас в стадии наполнения: у части товаров нет цены
 * (`calculated_price = null`), фото (`images = []`) и привязки к категориям
 * (`categories = []`), зато богатый `metadata` (бренд/МНН/ATC/штрихкод/производитель).
 * Поэтому все поля nullable/с дефолтами — маппинг обязан переживать пустоты.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class MedusaProductListResponse(
    val products: List<MedusaProduct> = emptyList(),
    val count: Int = 0,
    val offset: Int = 0,
    val limit: Int = 0,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MedusaProductWrapper(
    val product: MedusaProduct? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MedusaProduct(
    val id: String = "",
    val title: String? = null,
    val subtitle: String? = null,
    val description: String? = null,
    val handle: String? = null,
    val thumbnail: String? = null,
    val status: String? = null,
    val images: List<MedusaImage> = emptyList(),
    val categories: List<MedusaCategory> = emptyList(),
    val tags: List<MedusaTag> = emptyList(),
    val variants: List<MedusaVariant> = emptyList(),
    val metadata: Map<String, Any?>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MedusaImage(
    val id: String? = null,
    val url: String? = null,
    val rank: Int? = null,
    val metadata: Map<String, Any?>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MedusaCategory(
    val id: String = "",
    val name: String = "",
    val handle: String? = null,
    @JsonProperty("parent_category_id") val parentCategoryId: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MedusaTag(
    val id: String? = null,
    val value: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MedusaVariant(
    val id: String = "",
    val title: String? = null,
    val sku: String? = null,
    val barcode: String? = null,
    val metadata: Map<String, Any?>? = null,
    @JsonProperty("calculated_price") val calculatedPrice: MedusaCalculatedPrice? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MedusaCalculatedPrice(
    @JsonProperty("calculated_amount") val calculatedAmount: Double? = null,
    @JsonProperty("currency_code") val currencyCode: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MedusaProductPharmaciesResponse(
    @JsonProperty("product_id") val productId: String? = null,
    val currency: String? = null,
    @JsonProperty("price_range") val priceRange: MedusaPriceRange? = null,
    @JsonProperty("pharmacy_count") val pharmacyCount: Int = 0,
    val pharmacies: List<MedusaPharmacyPrice> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MedusaPriceRange(
    val min: Double? = null,
    val max: Double? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MedusaPharmacyPrice(
    val price: Double? = null,
)

/**
 * Единая цена для HQ/POSM из per-pharmacy retail-снимков.
 *
 * В Medusa у многих товаров `variant.calculated_price` ещё пустой, но импорт остатков
 * уже наполнил `pharmacy_pricing`. Для одного числа берём медиану по аптекам: она
 * устойчивее минимума/максимума и не выглядит как точная цена конкретной точки.
 */
data class MedusaRetailPriceSummary(
    val price: Int,
    val min: Int?,
    val max: Int?,
    val pharmacyCount: Int,
    val currency: String,
)

fun MedusaProductPharmaciesResponse.toRetailPriceSummary(): MedusaRetailPriceSummary? {
    val prices = pharmacies.mapNotNull { it.price?.takeIf { p -> p > 0 } }
    val median = medianRounded(prices)
    val min = priceRange?.min?.takeIf { it > 0 }?.roundToInt()
        ?: prices.minOrNull()?.roundToInt()
    val max = priceRange?.max?.takeIf { it > 0 }?.roundToInt()
        ?: prices.maxOrNull()?.roundToInt()
    val primary = median ?: min ?: max ?: return null
    val cur = currency?.trim()?.takeIf { it.isNotBlank() }?.uppercase() ?: "KZT"
    return MedusaRetailPriceSummary(
        price = primary,
        min = min,
        max = max,
        pharmacyCount = pharmacyCount.takeIf { it > 0 } ?: prices.size,
        currency = cur,
    )
}

private fun medianRounded(values: List<Double>): Int? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val mid = sorted.size / 2
    val median = if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    return median.roundToInt()
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class MedusaCategoryListResponse(
    @JsonProperty("product_categories") val productCategories: List<MedusaCategory> = emptyList(),
    val count: Int = 0,
)
