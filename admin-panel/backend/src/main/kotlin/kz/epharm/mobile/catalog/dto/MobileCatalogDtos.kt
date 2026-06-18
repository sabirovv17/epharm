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
    val category: String?,            // первая категория (для краткого показа)
    val categories: List<String>,    // все категории товара — для клиентского фильтра в ленте
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

/** Вопрос-ответ из ПИМ (Medusa metadata.faq) для карточки товара. */
data class MobileCatalogQaDto(
    val q: String,
    val a: String,
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
    /** Вопрос-ответ из ПИМ (Medusa metadata.faq). Пусто — секции Q&A нет. */
    val qa: List<MobileCatalogQaDto>,
    /**
     * Есть ли на этот товар активная кампания (PromoEntity со статусом active). Отдаём всем
     * (анониму тоже) — мобилка по нему решает, кликабельна ли карточка/кнопка «акция в чек».
     */
    val hasActiveCampaign: Boolean = false,
    /** Id активной кампании (pr_*) — мобилка шлёт его с чеком. null — активной кампании нет. */
    val promoId: String? = null,
    /** Название активной кампании. null — активной кампании нет. */
    val campaignTitle: String? = null,
    /**
     * Бонус фармацевту за продажу по активной кампании, ₸. Коммерчески чувствителен —
     * отдаётся ТОЛЬКО авторизованному фармацевту (includeIncentive); аноним → null.
     */
    val bonus: Int? = null,
)

data class MobileCategoryDto(
    val id: String,
    val name: String,
    val handle: String?,
    val parentId: String?,
)

/**
 * Одна рекомендация в карточке товара (ДОП.3b): сам товар-рекомендация (карточка витрины)
 * + короткая подсказка фармацевту (script правила) + бонус за рекомендацию.
 */
data class MobileRecommendationDto(
    val product: MobileCatalogProductDto,
    val note: String?,   // короткий скрипт/подсказка из правила; null — нет текста
    val bonus: Int?,     // бонус фармацевту, ₸; null — не показываем
    /**
     * Есть ли своя активная кампания у товара-рекомендации (п.7). Компаньон кросс-селла
     * БЕЗ своей кампании всё равно показывается — мобилка делает его некликабельным.
     * substitution (замены) → всегда false (продвигаемый — это сам recommend кампании-триггера).
     */
    val hasActiveCampaign: Boolean = false,
    /**
     * Группировка для мобилки (п.1/п.7):
     *  - "alternative"               — замена (substitution-правило);
     *  - "crosssell_with_campaign"   — допродажа, у компаньона есть своя активная кампания (кликабелен);
     *  - "crosssell_no_campaign"     — допродажа без кампании у компаньона (показываем, но не кликабелен).
     */
    val group: String = "alternative",
)

/**
 * Рекомендации к товару карточки (ДОП.3b):
 *  - alternatives — «Альтернативы» (замены): чем продвигаемым можно заменить этот товар;
 *  - crosssells   — «Дополнения» (кросс-селл): что предложить вместе с этим товаром.
 * Источник — активные правила активных кампаний (тот же набор, что у POSM-кассы).
 */
data class MobileRecommendationsDto(
    val alternatives: List<MobileRecommendationDto>,
    val crosssells: List<MobileRecommendationDto>,
)

/**
 * Глобальные пулы рекомендаций для ленты каталога (пилюли «Альтернативы»/«Дополнения»):
 *  - alternatives — товары, продвигаемые как ЗАМЕНЫ (recommend активных substitution-правил);
 *  - crosssells   — товары, продвигаемые как ДОПОЛНЕНИЯ (recommend активных crosssell-правил).
 * В отличие от [MobileRecommendationsDto] (привязана к товару карточки), это весь ассортимент
 * замен/допов из правил кампаний. Отдаём только товары — без бонуса/скрипта.
 */
data class MobileRecommendationPoolsDto(
    val alternatives: List<MobileCatalogProductDto>,
    val crosssells: List<MobileCatalogProductDto>,
)
