package kz.epharm.mobile.catalog.controller

import kz.epharm.mobile.auth.security.PharmacistPrincipal
import kz.epharm.mobile.catalog.dto.MobileCatalogDetailDto
import kz.epharm.mobile.catalog.dto.MobileCatalogPageDto
import kz.epharm.mobile.catalog.dto.MobileCategoryDto
import kz.epharm.mobile.catalog.dto.MobileRecommendationPoolsDto
import kz.epharm.mobile.catalog.dto.MobileRecommendationsDto
import kz.epharm.mobile.catalog.service.MobileCatalogService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Реальный каталог товаров (витрина inkar.kz через Medusa) для мобильного приложения.
 *
 * ПУБЛИЧНЫЙ эндпоинт (permitAll в SecurityConfig): лента товаров на главной видна БЕЗ
 * логина — фармацевт листает каталог ещё до входа. JWT нужен только для бонусов/чеков
 * (/api/mobile/me, /receipts), а не для просмотра витрины. Идентичность фармацевта в
 * выдаче каталога не используется, поэтому principal здесь не инъектируется.
 *
 * Бэкенд проксирует Medusa, чтобы:
 *  - publishable-ключ Medusa не светился в бинаре телефона;
 *  - телефон ходил только в наш HTTPS (Medusa пока на HTTP);
 *  - можно было кешировать и нормализовать неполные данные.
 */
@RestController
@RequestMapping("/api/mobile/catalog")
class MobileCatalogController(
    private val service: MobileCatalogService,
) {

    /** Список/поиск товаров. `q` — строка поиска, `category` — id категории Medusa. */
    @GetMapping("/products")
    fun products(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) category: String?,
        @RequestParam(defaultValue = "24") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
    ): MobileCatalogPageDto = service.search(q = q, category = category, limit = limit, offset = offset)

    /** Карточка товара по medusa-id. */
    @GetMapping("/products/{id}")
    fun product(
        @PathVariable id: String,
    ): MobileCatalogDetailDto = service.detail(id)

    /**
     * Рекомендации к товару (ДОП.3b): «Альтернативы» (замены) + «Дополнения» (кросс-селл)
     * из активных правил активных кампаний. Грузится отдельно от карточки (не тормозит detail).
     *
     * ИБ: эндпоинт публичный (как весь каталог — товары видны до логина), НО размер бонуса
     * фармацевту и текст скрипта — коммерчески чувствительны, поэтому отдаются ТОЛЬКО
     * авторизованному фармацевту (principal != null). Аноним видит лишь сами товары-рекомендации.
     */
    @GetMapping("/products/{id}/recommendations")
    fun recommendations(
        @PathVariable id: String,
        @AuthenticationPrincipal principal: PharmacistPrincipal?,
    ): MobileRecommendationsDto = service.recommendations(id, includeIncentive = principal != null)

    /**
     * Глобальные пулы рекомендаций для ленты каталога (пилюли «Альтернативы»/«Дополнения»):
     * все товары, продвигаемые как замены/дополнения в активных правилах активных кампаний.
     * Публичный (как весь каталог); отдаёт только товары, без бонуса/скрипта.
     */
    @GetMapping("/recommendation-pools")
    fun recommendationPools(): MobileRecommendationPoolsDto = service.recommendationPools()

    /** Дерево категорий (на будущее — товары к ним линкуются постепенно). */
    @GetMapping("/categories")
    fun categories(): List<MobileCategoryDto> = service.categories()
}
