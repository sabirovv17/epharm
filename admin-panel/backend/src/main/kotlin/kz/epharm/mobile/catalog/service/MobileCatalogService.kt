package kz.epharm.mobile.catalog.service

import kz.epharm.medusa.MedusaCatalogCache
import kz.epharm.medusa.client.MedusaClient
import kz.epharm.medusa.dto.MedusaProduct
import kz.epharm.mobile.catalog.dto.MobileCatalogDetailDto
import kz.epharm.mobile.catalog.dto.MobileCatalogMarketplaceLinkDto
import kz.epharm.mobile.catalog.dto.MobileCatalogPageDto
import kz.epharm.mobile.catalog.dto.MobileCatalogProductDto
import kz.epharm.mobile.catalog.dto.MobileCategoryDto
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import kotlin.math.roundToInt

/**
 * Каталог для мобильного приложения: тянет товары из Medusa-витрины через [MedusaClient],
 * нормализует в наши DTO и кеширует ([MedusaCatalogCache]).
 *
 * Главная сложность — РЕАЛЬНЫЕ данные неполные: цена/фото/категории часто пусты,
 * но есть богатый `metadata`. Маппинг построен на цепочках fallback (бренд из metadata,
 * категория из metadata, штрихкод из варианта или metadata) — чтобы карточка всегда
 * выглядела осмысленно по мере наполнения PIM.
 */
@Service
class MobileCatalogService(
    private val medusa: MedusaClient,
    private val cache: MedusaCatalogCache,
) {

    fun search(q: String?, category: String?, limit: Int, offset: Int): MobileCatalogPageDto {
        val safeLimit = limit.coerceIn(1, MAX_LIMIT)
        val safeOffset = offset.coerceAtLeast(0)
        val key = "list|q=${q?.trim()?.lowercase().orEmpty()}|cat=${category.orEmpty()}|l=$safeLimit|o=$safeOffset"
        return cache.get(key) {
            val resp = medusa.listProducts(q = q, categoryId = category, ids = null, limit = safeLimit, offset = safeOffset)
            MobileCatalogPageDto(
                items = resp.products.map { card(it) },
                total = resp.count,
                limit = safeLimit,
                offset = safeOffset,
            )
        }
    }

    fun detail(id: String): MobileCatalogDetailDto = cache.get("detail|$id") {
        val p = medusa.getProduct(id)
            ?: throw AppException(ErrorCode.NOT_FOUND, "Товар не найден", HttpStatus.NOT_FOUND)
        detailOf(p)
    }

    fun categories(): List<MobileCategoryDto> = cache.get("categories") {
        medusa.listCategories().productCategories.map {
            MobileCategoryDto(id = it.id, name = it.name, handle = it.handle, parentId = it.parentCategoryId)
        }
    }

    /**
     * Карточки витрины по списку Medusa-id (для джойна промо-лента ↔ каталог).
     * Возвращает map id→карточка; отсутствующие/недоступные id в map не попадают.
     * Переиспользует [card] (включая фильтр плейсхолдеров «-») и кеш.
     */
    fun cardsByIds(ids: List<String>): Map<String, MobileCatalogProductDto> {
        val clean = ids.mapNotNull { it.trim().takeIf { s -> s.isNotBlank() } }.distinct()
        if (clean.isEmpty()) return emptyMap()
        return cache.get("cards|" + clean.sorted().joinToString(",")) {
            // Medusa отдаёт максимум `limit` товаров — при >MAX_LIMIT id бьём на чанки и
            // сливаем, иначе часть привязанных к промо товаров потерялась бы из выдачи.
            clean.chunked(MAX_LIMIT)
                .flatMap { chunk -> medusa.listProducts(ids = chunk, limit = chunk.size).products }
                .associate { it.id to card(it) }
        }
    }

    // ── маппинг Medusa → mobile ───────────────────────────────────────────────

    private fun card(p: MedusaProduct) = MobileCatalogProductDto(
        id = p.id,
        name = nameOf(p),
        brand = brandOf(p),
        mnn = metaStr(p, "mnn"),
        rxOtc = metaStr(p, "rx_otc"),
        price = priceOf(p),
        currency = currencyOf(p),
        imageUrl = imageOf(p),
        barcode = barcodeOf(p),
        category = categoryOf(p),
        categories = categoriesOf(p),
    )

    private fun detailOf(p: MedusaProduct) = MobileCatalogDetailDto(
        id = p.id,
        name = nameOf(p),
        brand = brandOf(p),
        mnn = metaStr(p, "mnn"),
        atc = metaStr(p, "atc"),
        rxOtc = metaStr(p, "rx_otc"),
        price = priceOf(p),
        currency = currencyOf(p),
        imageUrl = imageOf(p),
        images = siteImages(p),
        barcode = barcodeOf(p),
        category = categoryOf(p),
        country = metaStr(p, "country_official") ?: metaStr(p, "country"),
        manufacturer = metaStr(p, "manufacturer_official") ?: metaStr(p, "manufacturer"),
        description = p.description?.trim()?.takeIf { it.isNotBlank() }
            ?: p.subtitle?.trim()?.takeIf { it.isNotBlank() },
        keyFacts = metaStrList(p, "key_facts"),
        marketplaceLinks = marketplaceLinks(p),
    )

    private fun nameOf(p: MedusaProduct): String =
        p.title?.trim()?.takeIf { it.isNotBlank() }
            ?: metaStr(p, "full_title")
            ?: "Без названия"

    /** Цепочка из STOREFRONT.md: brand_name ?? brand_raw ?? corporation ?? manufacturer. */
    private fun brandOf(p: MedusaProduct): String? =
        metaStr(p, "brand_name")
            ?: metaStr(p, "brand_raw")
            ?: metaStr(p, "corporation")
            ?: metaStr(p, "manufacturer")

    private fun categoryOf(p: MedusaProduct): String? =
        p.categories.firstOrNull { it.name.isNotBlank() }?.name
            ?: metaStr(p, "category")

    /**
     * Все категории товара (имена) — для клиентского фильтра в ленте каталога.
     * Возвращаем как есть (включая структурную «Сайт»); фильтрацию шумовых категорий
     * делает UI. distinct — у товара бывают дубли-предки.
     */
    private fun categoriesOf(p: MedusaProduct): List<String> =
        p.categories
            .mapNotNull { it.name.trim().takeIf { n -> n.isNotBlank() } }
            .distinct()

    private fun priceOf(p: MedusaProduct): Int? =
        p.variants.firstNotNullOfOrNull { it.calculatedPrice?.calculatedAmount }?.roundToInt()

    private fun currencyOf(p: MedusaProduct): String =
        p.variants.firstNotNullOfOrNull { it.calculatedPrice?.currencyCode }?.uppercase() ?: "KZT"

    private fun barcodeOf(p: MedusaProduct): String? =
        p.variants.firstOrNull { !it.barcode.isNullOrBlank() }?.barcode?.trim()
            ?: metaStr(p, "barcode")

    private fun imageOf(p: MedusaProduct): String? =
        p.thumbnail?.trim()?.takeIf { it.isNotBlank() }
            ?: siteImages(p).firstOrNull()

    /** Картинки витрины (исключаем gallery=marketplace — это фото для маркетплейсов). */
    private fun siteImages(p: MedusaProduct): List<String> =
        p.images
            .filter { (it.metadata?.get("gallery") as? String) != "marketplace" }
            .mapNotNull { it.url?.trim()?.takeIf { u -> u.isNotBlank() } }

    /** Строка из metadata, отбрасывая плейсхолдеры «поле не заполнено» (см. [META_PLACEHOLDERS]). */
    private fun metaStr(p: MedusaProduct, key: String): String? {
        val s = (p.metadata?.get(key) as? String)?.trim() ?: return null
        return s.takeUnless { it.isBlank() || it.lowercase() in META_PLACEHOLDERS }
    }

    private fun metaStrList(p: MedusaProduct, key: String): List<String> =
        (p.metadata?.get(key) as? List<*>)
            ?.mapNotNull { it?.toString()?.trim()?.takeIf { s -> s.isNotBlank() } }
            ?: emptyList()

    private fun marketplaceLinks(p: MedusaProduct): List<MobileCatalogMarketplaceLinkDto> =
        (p.metadata?.get("marketplace_links") as? List<*>)
            ?.mapNotNull { it as? Map<*, *> }
            ?.mapNotNull { m ->
                val platform = (m["platform"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                MobileCatalogMarketplaceLinkDto(
                    platform = platform,
                    url = (m["url"] as? String)?.trim()?.takeIf { it.isNotBlank() },
                    price = (m["price"] as? Number)?.toInt(),
                )
            }
            ?: emptyList()

    companion object {
        // Канал «Сайт» сейчас ~77 товаров — лента грузит весь каталог за 1-2 запроса.
        const val MAX_LIMIT = 100

        // Плейсхолдеры «поле не заполнено» из 1С-выгрузки витрины (товар есть, значения нет):
        // прочерки разных видов, "_", "none", "н/д". Сравнение по lowercase + trim.
        // Без этого фильтра "-" утекал в бренд/АТС/категорию у ~62% товаров (≈470 полей).
        private val META_PLACEHOLDERS = setOf("_", "-", "—", "–", "none", "n/a", "н/д")
    }
}
