package kz.epharm.dashboard.controller

import kz.epharm.dashboard.dto.DashboardSummaryDto
import kz.epharm.dashboard.service.DashboardService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin/dashboard")
class DashboardController(private val dashboardService: DashboardService) {

    @GetMapping("/summary")
    fun summary(): DashboardSummaryDto = dashboardService.summary()
}
