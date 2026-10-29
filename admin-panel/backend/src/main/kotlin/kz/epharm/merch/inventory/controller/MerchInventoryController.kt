package kz.epharm.merch.inventory.controller

import jakarta.validation.Valid
import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.merch.inventory.dto.MerchAvailabilityRequest
import kz.epharm.merch.inventory.service.MerchInventoryService
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/admin/merch/branches")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','HQ_HEAD','CATEGORY_LEAD','BRAND_MANAGER','FINANCE_REVIEWER','REGIONAL_MANAGER','TRAINING_MANAGER','TRAINER')")
class MerchInventoryController(private val inventory: MerchInventoryService) {
    @GetMapping
    fun branches(@RequestParam(defaultValue = "50") limit: Int, @RequestParam(defaultValue = "0") offset: Int) = inventory.branches(limit, offset)
    @GetMapping("/{id}/inventory")
    fun inventory(@PathVariable id: UUID) = inventory.inventory(id)
    @GetMapping("/{id}/availability")
    fun availability(@PathVariable id: UUID, @RequestParam startMonth: LocalDate, @RequestParam(defaultValue = "6") monthCount: Int) =
        inventory.calendar(id, startMonth, monthCount)
    @PostMapping("/{id}/availability")
    fun setAvailability(@PathVariable id: UUID, @Valid @RequestBody request: MerchAvailabilityRequest,
        @AuthenticationPrincipal principal: AdminPrincipal?) = inventory.setAvailability(id, request,
        principal?.userId ?: throw AppException(ErrorCode.UNAUTHORIZED, "Требуется вход администратора", HttpStatus.UNAUTHORIZED))
}
