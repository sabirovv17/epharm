package kz.epharm.lift.controller

import kz.epharm.lift.dto.LiftSummaryDto
import kz.epharm.lift.service.LiftService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin/lift")
class LiftController(private val liftService: LiftService) {

    @GetMapping
    fun summary(): LiftSummaryDto = liftService.summary()
}
