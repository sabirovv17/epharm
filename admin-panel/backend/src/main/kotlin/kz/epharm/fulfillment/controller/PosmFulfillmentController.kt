package kz.epharm.fulfillment.controller

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kz.epharm.fulfillment.dto.FulfillmentActionRequest
import kz.epharm.fulfillment.dto.FulfillmentOrderDto
import kz.epharm.fulfillment.dto.FulfillmentOrderPageDto
import kz.epharm.fulfillment.dto.RegisterFulfillmentDeviceRequest
import kz.epharm.fulfillment.dto.RegisterFulfillmentDeviceResponse
import kz.epharm.fulfillment.service.FulfillmentService
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/posm/fulfillment")
class PosmFulfillmentController(
    private val fulfillment: FulfillmentService,
    @Value("\${app.posm.device-key:dev-posm-key}") private val legacyPosmKey: String,
) {
    @PostMapping("/devices/register")
    fun register(
        @RequestHeader(name = "X-Posm-Key", required = false) key: String?,
        @RequestBody request: RegisterFulfillmentDeviceRequest,
    ): RegisterFulfillmentDeviceResponse {
        requireLegacyPosmKey(key)
        return fulfillment.registerDevice(request.deviceId, request.pharmacyId)
    }

    @GetMapping("/orders")
    fun orders(
        @RequestHeader(name = "X-Fulfillment-Device", required = false) token: String?,
        @RequestParam(defaultValue = "active") status: String,
        @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(defaultValue = "50") limit: Int,
    ): FulfillmentOrderPageDto {
        val device = fulfillment.authenticateDevice(token)
        return fulfillment.listForDevice(device, status, offset, limit)
    }

    @GetMapping("/orders/{orderId}")
    fun order(
        @PathVariable orderId: String,
        @RequestHeader(name = "X-Fulfillment-Device", required = false) token: String?,
    ): FulfillmentOrderDto {
        val device = fulfillment.authenticateDevice(token)
        return fulfillment.getForDevice(orderId, device)
    }

    @PostMapping("/orders/{orderId}/actions")
    fun action(
        @PathVariable orderId: String,
        @RequestHeader(name = "X-Fulfillment-Device", required = false) token: String?,
        @RequestBody request: FulfillmentActionRequest,
    ): FulfillmentOrderDto {
        val device = fulfillment.authenticateDevice(token)
        return fulfillment.actAsDevice(orderId, request, device)
    }

    private fun requireLegacyPosmKey(key: String?) {
        val supplied = key?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)
        val expected = legacyPosmKey.toByteArray(StandardCharsets.UTF_8)
        if (key.isNullOrBlank() || !MessageDigest.isEqual(supplied, expected)) {
            throw AppException(
                ErrorCode.UNAUTHORIZED,
                "Invalid or missing POSM device key",
                HttpStatus.UNAUTHORIZED,
            )
        }
    }
}
