package kz.epharm.mobile.catalog.service

import kz.epharm.medusa.MedusaCatalogCache
import kz.epharm.medusa.client.MedusaClient
import kz.epharm.medusa.dto.MedusaProduct
import kz.epharm.mobile.catalog.dto.MobileCatalogDetailDto
import kz.epharm.mobile.catalog.dto.MobileCatalogMarketplaceLinkDto
import kz.epharm.mobile.catalog.dto.MobileCatalogPageDto
import kz.epharm.mobile.catalog.dto.MobileCatalogProductDto
import kz.epharm.mobile.catalog.dto.MobileCatalogQaDto
import kz.epharm.mobile.catalog.dto.MobileCategoryDto
import kz.epharm.mobile.catalog.dto.MobileRecommendationDto
import kz.epharm.mobile.catalog.dto.MobileRecommendationsDto
import kz.epharm.promo.entity.PromoStatus
import kz.epharm.promo.repository.PromoRepository
import kz.epharm.rules.entity.RuleEntity
import kz.epharm.rules.entity.RuleStatus
import kz.epharm.rules.entity.RuleType
import kz.epharm.rules.repository.RuleRepository
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
    private val promoRepository: PromoRepository,
    private val ruleRepository: RuleRepository,
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
        // Товар обязан существовать в Medusa — никаких карточек «из воздуха» (T5).
        val p = medusa.getProduct(id)
            ?: throw AppException(ErrorCode.NOT_FOUND, "Товар не найден", HttpStatus.NOT_FOUND)
        applyPromoOverride(id, detailOf(p))
    }

    /**
     * Накладывает ручные override-поля кампании (T1): если на этот товар есть акция с
     * override-фото/описанием — показываем их вместо данных Medusa. «Заменить на своё
     * в крайнем случае», когда PIM-данные не устраивают.
     */
    private fun applyPromoOverride(medusaProductId: String, base: MobileCatalogDetailDto): MobileCatalogDetailDto {
        val promo = promoRepository.findAllByMedusaProductId(medusaProductId)
            .firstOrNull {
                it.overrideImage != null || it.overrideDescription != null ||
                    it.overrideCharacteristics != null
            }
            ?: return base
        // Свои характеристики (по строке) заменяют keyFacts из Medusa, если заданы.
        val keyFacts = promo.overrideCharacteristics
            ?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
            ?: base.keyFacts
        return base.copy(
            imageUrl = promo.overrideImage ?: base.imageUrl,
            images = promo.overrideImage?.let { listOf(it) } ?: base.images,
            description = promo.overrideDescription ?: base.description,
            keyFacts = keyFacts,
        )
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

    // ── Рекомендации к товару (ДОП.3b) ────────────────────────────────────────

    /**
     * «Альтернативы» (замены) и «Дополнения» (кросс-селл) для товара карточки.
     * Источник — те же активные правила активных кампаний, что и у POSM-кассы:
     *  - alternatives: substitution-правила, чей триггер = этот товар → рекомендуют продвигаемый;
     *  - crosssells:   crosssell-правила, чей триггер = этот товар → рекомендуют компаньона.
     * Рекомендованные товары резолвятся к карточкам витрины ([cardsByIds]); недоступные пропускаем.
     * Пусто (нет правил) → обе секции пустые, мобилка их не рисует.
     */
    fun recommendations(productId: String): MobileRecommendationsDto {
        val active = activeRules()
        val subs = active.filter {
            it.type == RuleType.substitution && it.recommend != productId && triggerMatches(it, productId)
        }
        val cross = active.filter {
            it.type == RuleType.crosssell && it.recommend != productId && triggerMatches(it, productId)
        }
        val ids = (subs + cross).map { it.recommend }.distinct()
        if (ids.isEmpty()) return MobileRecommendationsDto(emptyList(), emptyList())
        val cards = cardsByIds(ids)
        return MobileRecommendationsDto(
            alternatives = toRecommendations(subs, cards),
            crosssells = toRecommendations(cross, cards),
        )
    }

    /**
     * Активные правила, гейтнутые статусом кампании (мастер-выключатель) — копия логики
     * RulesEngineService: правило из неактивной кампании не показываем, legacy без promoId проходят.
     */
    private fun activeRules(): List<RuleEntity> {
        val activeRules = ruleRepository.findAllByStatusRawOrderByUpdatedAtDesc(RuleStatus.active.name)
        val promoIds = activeRules.mapNotNull { it.promoId }.toSet()
        val activePromoIds =
            if (promoIds.isEmpty()) emptySet()
            else promoRepository.findAllById(promoIds)
                .filter { it.status == PromoStatus.active }
                .map { it.id }
                .toSet()
        return activeRules.filter { it.promoId == null || it.promoId in activePromoIds }
    }

    /** Триггер правила указывает на этот товар? (mnn-триггеры пропускаем — нужен mnn товара, редкий legacy.) */
    private fun triggerMatches(rule: RuleEntity, productId: String): Boolean = when (rule.trigger.kind) {
        "product" -> (rule.trigger.value as? String) == productId
        "product_any" -> (rule.trigger.value as? List<*>)?.any { it == productId } == true
        else -> false
    }

    /** Правила → рекомендации: сортировка по бонусу (выше — раньше), дедуп по товару, резолв карточки. */
    private fun toRecommendations(
        rules: List<RuleEntity>,
        cards: Map<String, MobileCatalogProductDto>,
    ): List<MobileRecommendationDto> =
        rules.sortedByDescending { it.bonus }
            .distinctBy { it.recommend }
            .mapNotNull { r ->
                val card = cards[r.recommend] ?: return@mapNotNull null
                MobileRecommendationDto(
                    product = card,
                    note = r.script.trim().takeIf { it.isNotBlank() },
                    bonus = r.bonus.takeIf { it > 0 },
                )
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
        qa = faqList(p),
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

    /** Вопрос-ответ из metadata.faq: список объектов {q, a}; пустые/битые пропускаем. */
    private fun faqList(p: MedusaProduct): List<MobileCatalogQaDto> =
        (p.metadata?.get("faq") as? List<*>)
            ?.mapNotNull { it as? Map<*, *> }
            ?.mapNotNull { m ->
                val q = (m["q"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val a = (m["a"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                MobileCatalogQaDto(q = q, a = a)
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
