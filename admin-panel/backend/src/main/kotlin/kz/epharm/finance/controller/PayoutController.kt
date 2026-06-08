package kz.epharm.finance.controller

import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.finance.dto.PayoutBatchDto
import kz.epharm.finance.dto.PayoutItemDto
import kz.epharm.finance.entity.PayoutBatchStatus
import kz.epharm.finance.service.PayoutService
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin/payouts")
class PayoutController(private val payoutService: PayoutService) {

    @GetMapping
    fun list(@RequestParam(required = false) status: PayoutBatchStatus?): List<PayoutBatchDto> =
        payoutService.list(status = status)

    @GetMapping("/{id}")
    fun get(@PathVariable id: String): PayoutBatchDto = payoutService.get(id)

    @GetMapping("/{id}/items")
    fun items(@PathVariable id: String): List<PayoutItemDto> = payoutService.listItems(id)

    @PostMapping("/{id}/approve")
    fun approve(
        @PathVariable id: String,
        @AuthenticationPrincipal principal: AdminPrincipal?,
    ): PayoutBatchDto {
        val approverId = principal?.userId?.toString()
            ?: throw AppException(ErrorCode.UNAUTHORIZED, "Not authenticated", HttpStatus.UNAUTHORIZED)
        return payoutService.approve(id, approverId)
    }

    /**
     * Сформировать батч выплат вручную: собрать накопленные бонусы фармацевтов в payout_batch.
     * Тот же механизм, что и cron 1-го числа. period опционален (по умолчанию — текущий месяц).
     */
    @PostMapping("/generate")
    fun generate(
        @RequestParam(required = false) period: String?,
        @AuthenticationPrincipal principal: AdminPrincipal?,
    ): PayoutBatchDto {
        principal?.userId
            ?: throw AppException(ErrorCode.UNAUTHORIZED, "Not authenticated", HttpStatus.UNAUTHORIZED)
        return payoutService.generateBatch(period ?: currentPeriodLabel())
    }

    private fun currentPeriodLabel(): String {
        val ym = java.time.YearMonth.now()
        val months = listOf(
            "Январь", "Февраль", "Март", "Апрель", "Май", "Июнь",
            "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь",
        )
        return "${months[ym.monthValue - 1]} ${ym.year}"
    }
}
