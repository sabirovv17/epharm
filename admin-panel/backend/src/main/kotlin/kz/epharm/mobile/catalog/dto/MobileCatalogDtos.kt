package kz.epharm.mobile.catalog.dto

/**
 * Мобильный контракт каталога — НАШ чистый формат поверх Medusa Store API.
 * Мобилка не знает про Medusa: ходит в `/api/mobile/catalog/…`, получает эти DTO.
 *
 * `price`/`imageUrl`/`brand`/`category` nullable: реальный каталог наполняется
 * постепенно (часть товаров пока без цены/фото) — приложение показывает заглушки.
 */
data class MobileCatalogProductDto(
    val id: String,
    val name: String,
    val brand: String?,
    val mnn: String?,
    val rxOtc: String?,   // "OTC" | "Rx" | null
    val price: Int?,      // в тенге; null → «уточняйте в аптеке»
    val currency: String, // "KZT"
    val imageUrl: String?,
    val barcode: String?,
    val category: String?,
)

data class MobileCatalogPageDto(
    val items: List<MobileCatalogProductDto>,
    val total: Int,
    val limit: Int,
    val offset: Int,
)

data class MobileCatalogMarketplaceLinkDto(
    val platform: String,
    val url: String?,
    val price: Int?,
)

data class MobileCatalogDetailDto(
    val id: String,
    val name: String,
    val brand: String?,
    val mnn: String?,
    val atc: String?,
    val rxOtc: String?,
    val price: Int?,
    val currency: String,
    val imageUrl: String?,
    val images: List<String>,
    val barcode: String?,
    val category: String?,
    val country: String?,
    val manufacturer: String?,
    val description: String?,
    val keyFacts: List<String>,
    val marketplaceLinks: List<MobileCatalogMarketplaceLinkDto>,
)

data class MobileCategoryDto(
    val id: String,
    val name: String,
    val handle: String?,
    val parentId: String?,
)
