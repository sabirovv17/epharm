package kz.epharm.dashboard.controller

import kz.epharm.dashboard.dto.DashboardSummaryDto
import kz.epharm.dashboard.dto.RecommendationAnalyticsDto
import kz.epharm.dashboard.service.DashboardService
import kz.epharm.dashboard.service.RecommendationAnalyticsService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin/dashboard")
class DashboardController(
    private val dashboardService: DashboardService,
    private val recommendationAnalyticsService: RecommendationAnalyticsService,
) {

    @GetMapping("/summary")
    fun summary(): DashboardSummaryDto = dashboardService.summary()

    /**
     * Аналитика «Показано рекомендаций» (Задача 1 + 1.2): конверсия показ→продажа, время до
     * продажи и постраничный журнал показов/продаж. `limit` оставлен как совместимый alias
     * размера первой страницы для старых интеграций.
     */
    @GetMapping("/recommendations")
    fun recommendations(
        @RequestParam(required = false, defaultValue = "0") page: Int,
        @RequestParam(required = false, defaultValue = "50") size: Int,
        @RequestParam(required = false) limit: Int?,
    ): RecommendationAnalyticsDto = recommendationAnalyticsService.analytics(page, limit ?: size)
}
