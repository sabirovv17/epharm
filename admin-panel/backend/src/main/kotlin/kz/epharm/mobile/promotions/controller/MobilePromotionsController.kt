package kz.epharm.mobile.promotions.controller

import kz.epharm.mobile.promotions.dto.MobilePromotionDto
import kz.epharm.mobile.promotions.service.MobilePromotionsService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Лента промо-товаров для мобильного приложения — главная страница фармацевта и
 * выбор акции при загрузке чека.
 *
 * ПУБЛИЧНЫЙ (permitAll в SecurityConfig), как и /api/mobile/catalog: фармацевт
 * листает акции ещё до входа. Отдаёт активные промо-кампании из админки,
 * смерженные с живыми данными витрины Medusa.
 */
@RestController
@RequestMapping("/api/mobile/promotions")
class MobilePromotionsController(
    private val service: MobilePromotionsService,
) {
    @GetMapping
    fun feed(): List<MobilePromotionDto> = service.activeFeed()
}
