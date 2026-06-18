package kz.epharm.banners.controller

import kz.epharm.banners.dto.MobileBannerDto
import kz.epharm.banners.service.BannerService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Лента баннеров для главного экрана мобильного приложения (п.5).
 *
 * ПУБЛИЧНЫЙ (permitAll в SecurityConfig, рядом с /api/mobile/promotions): баннеры
 * фармацевт видит на главной ещё до входа. Отдаёт только активные баннеры из админки
 * в порядке position. Тап по баннеру → детальный экран внутри приложения (detailMd).
 */
@RestController
@RequestMapping("/api/mobile/banners")
class MobileBannersController(
    private val bannerService: BannerService,
) {
    @GetMapping
    fun feed(): List<MobileBannerDto> = bannerService.activeFeed()
}
