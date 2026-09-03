package kz.epharm.fulfillment.controller

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import kz.epharm.fulfillment.dto.FulfillmentUpdateFeedDto
import kz.epharm.fulfillment.dto.StorefrontOrderAck
import kz.epharm.fulfillment.dto.StorefrontOrderCreatedRequest
import kz.epharm.fulfillment.security.FulfillmentCrypto
import kz.epharm.fulfillment.service.FulfillmentService
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/integrations/storefront")
class StorefrontFulfillmentController(
    objectMapper: ObjectMapper,
    private val crypto: FulfillmentCrypto,
    private val fulfillment: FulfillmentService,
) {
    private val strictReader = objectMapper.copy()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .readerFor(StorefrontOrderCreatedRequest::class.java)

    @PostMapping("/orders")
    fun ingest(
        request: HttpServletRequest,
        @RequestHeader(name = "X-Fulfillment-Timestamp", required = false) timestamp: String?,
        @RequestHeader(name = "X-Fulfillment-Signature", required = false) signature: String?,
        @RequestBody body: ByteArray,
    ): StorefrontOrderAck {
        if (body.isEmpty() || body.size > 512 * 1024) {
            throw AppException(ErrorCode.VALIDATION_FAILED, "Order body must contain at most 512 KiB")
        }
        crypto.verifyRequest(timestamp, signature, "POST", pathAndQuery(request), body)
        val payload = try {
            strictReader.readValue<StorefrontOrderCreatedRequest>(body)
        } catch (_: Exception) {
            throw AppException(
                ErrorCode.VALIDATION_FAILED,
                "Order body does not match the fulfillment contract",
                HttpStatus.BAD_REQUEST,
            )
        }
        return fulfillment.ingest(body, payload)
    }

    @GetMapping("/order-updates")
    fun updates(
        request: HttpServletRequest,
        @RequestHeader(name = "X-Fulfillment-Timestamp", required = false) timestamp: String?,
        @RequestHeader(name = "X-Fulfillment-Signature", required = false) signature: String?,
        @RequestParam(defaultValue = "0") after: Long,
        @RequestParam(defaultValue = "200") limit: Int,
    ): FulfillmentUpdateFeedDto {
        crypto.verifyRequest(timestamp, signature, "GET", pathAndQuery(request), ByteArray(0))
        return fulfillment.updates(after, limit)
    }

    private fun pathAndQuery(request: HttpServletRequest): String =
        request.requestURI + request.queryString?.let { "?$it" }.orEmpty()
}
