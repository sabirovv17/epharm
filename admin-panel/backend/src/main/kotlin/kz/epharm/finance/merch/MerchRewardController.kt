package kz.epharm.finance.merch

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import kz.epharm.mobile.auth.security.PharmacistPrincipal
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class MerchRewardController(private val service: MerchRewardService, private val security: MerchRewardSecurity, private val json: ObjectMapper) {
    @PostMapping("/api/mobile/merch/executor-grants/context")
    fun context(@AuthenticationPrincipal principal: PharmacistPrincipal, @RequestBody request: MerchContextRequest) =
        privateResponse(service.context(principal.pharmacistId, request.crmChallenge))

    @PostMapping("/api/mobile/merch/executor-grants/otp/request")
    fun otp(@AuthenticationPrincipal principal: PharmacistPrincipal, @RequestBody request: MerchContextRequest) =
        privateResponse(service.requestOtp(principal.pharmacistId, request.crmChallenge))

    @PostMapping("/api/mobile/merch/executor-grants")
    fun grant(@AuthenticationPrincipal principal: PharmacistPrincipal, @RequestBody request: MerchGrantRequest) =
        privateResponse(service.issueGrant(principal.pharmacistId, request))

    @PostMapping("/api/integrations/merch/executor-grants/redeem")
    fun redeem(request: HttpServletRequest): ResponseEntity<MerchBindingResponse> {
        val bytes = signedBody(request)
        return privateResponse(service.redeem(parse(bytes, MerchRedeemRequest::class.java)))
    }

    @PostMapping("/api/integrations/merch/reward-events")
    fun reward(request: HttpServletRequest): ResponseEntity<MerchRewardAck> {
        val bytes = signedBody(request)
        val result = service.reward(parse(bytes, MerchRewardEvent::class.java), bytes)
        return ResponseEntity.status(if (result.duplicate) HttpStatus.OK else HttpStatus.CREATED)
            .cacheControl(CacheControl.noStore()).body(result)
    }

    private fun signedBody(request: HttpServletRequest): ByteArray {
        security.requireEnabled()
        val body = request.inputStream.readNBytes(MerchRewardSecurity.MAX_BODY + 1)
        val path = request.requestURI + (request.queryString?.let { "?$it" } ?: "")
        security.verifyRequest(request.getHeader("X-Merch-Timestamp"), request.getHeader("X-Merch-Signature"), request.method, path, body)
        return body
    }
    private fun <T> parse(bytes: ByteArray, type: Class<T>): T = try { json.readValue(bytes, type) } catch (_: Exception) {
        throw AppException(ErrorCode.VALIDATION_FAILED, "Некорректное подтверждение задания")
    }
    private fun <T> privateResponse(body: T): ResponseEntity<T> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)
}
