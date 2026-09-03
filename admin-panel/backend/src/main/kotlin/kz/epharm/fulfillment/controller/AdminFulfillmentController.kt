package kz.epharm.fulfillment.controller

import java.util.UUID
import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.fulfillment.dto.FulfillmentActionRequest
import kz.epharm.fulfillment.dto.FulfillmentAssignRequest
import kz.epharm.fulfillment.dto.FulfillmentDeviceDto
import kz.epharm.fulfillment.dto.FulfillmentFeatureStatusDto
import kz.epharm.fulfillment.dto.FulfillmentOrderDto
import kz.epharm.fulfillment.dto.FulfillmentOrderPageDto
import kz.epharm.fulfillment.dto.FulfillmentPharmacyLinkDto
import kz.epharm.fulfillment.dto.FulfillmentPharmacyLinkRequest
import kz.epharm.fulfillment.service.FulfillmentService
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin/fulfillment")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','HQ_HEAD')")
class AdminFulfillmentController(private val fulfillment: FulfillmentService) {
    @GetMapping("/status")
    fun status(): FulfillmentFeatureStatusDto = fulfillment.featureStatus()

    @GetMapping("/orders")
    fun orders(
        @RequestParam(required = false) pharmacyId: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) unassigned: Boolean?,
        @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(defaultValue = "50") limit: Int,
    ): FulfillmentOrderPageDto = fulfillment.listForAdmin(pharmacyId, status, unassigned, offset, limit)

    @GetMapping("/orders/{orderId}")
    fun order(@PathVariable orderId: String): FulfillmentOrderDto = fulfillment.getForAdmin(orderId)

    @PostMapping("/orders/{orderId}/actions")
    fun action(
        @PathVariable orderId: String,
        @RequestBody request: FulfillmentActionRequest,
        @AuthenticationPrincipal principal: AdminPrincipal?,
    ): FulfillmentOrderDto = fulfillment.actAsAdmin(orderId, request, actor(principal))

    @PostMapping("/orders/{orderId}/assign")
    fun assign(
        @PathVariable orderId: String,
        @RequestBody request: FulfillmentAssignRequest,
        @AuthenticationPrincipal principal: AdminPrincipal?,
    ): FulfillmentOrderDto = fulfillment.assign(orderId, request.pharmacyId, request.expectedVersion, actor(principal))

    @GetMapping("/pharmacy-links")
    fun links(): List<FulfillmentPharmacyLinkDto> = fulfillment.listLinks()

    @PutMapping("/pharmacy-links")
    fun saveLink(
        @RequestBody request: FulfillmentPharmacyLinkRequest,
        @AuthenticationPrincipal principal: AdminPrincipal?,
    ): FulfillmentPharmacyLinkDto = fulfillment.saveLink(request, actor(principal))

    @GetMapping("/devices")
    fun devices(): List<FulfillmentDeviceDto> = fulfillment.listDevices()

    @DeleteMapping("/devices/{id}")
    fun revoke(
        @PathVariable id: UUID,
        @AuthenticationPrincipal principal: AdminPrincipal?,
    ) = fulfillment.revokeDevice(id, actor(principal))

    private fun actor(principal: AdminPrincipal?): String = principal?.userId?.toString()
        ?: throw AppException(ErrorCode.UNAUTHORIZED, "Admin identity is required", HttpStatus.UNAUTHORIZED)
}
