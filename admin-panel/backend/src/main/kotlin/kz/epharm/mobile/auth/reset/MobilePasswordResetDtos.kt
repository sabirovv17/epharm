package kz.epharm.mobile.auth.reset

import com.fasterxml.jackson.annotation.JsonAnySetter
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import kz.epharm.shared.validation.Iin
import java.time.Instant
import java.util.UUID

abstract class RedactedResetRequest {
    @JsonAnySetter fun rejectUnknown(key: String, value: Any?) { throw IllegalArgumentException("Unsupported recovery field") }
    final override fun toString(): String = "${javaClass.simpleName}([REDACTED])"
}
class RequestMobilePasswordReset(@field:NotBlank @field:Iin val iin: String): RedactedResetRequest()
class VerifyMobilePasswordReset(@field:NotBlank @field:Iin val iin: String, val challengeId: UUID,
    @field:Pattern(regexp="[0-9]{4,8}") val code: String): RedactedResetRequest()
class CompleteMobilePasswordReset(@field:NotBlank @field:Iin val iin: String,
    @field:Pattern(regexp="[A-Za-z0-9_-]{43}") val resetToken: String,
    @field:Size(min=8,max=128) val password: String): RedactedResetRequest()
data class MobilePasswordResetRequested(val challengeId: UUID, val phoneMasked: String,
    val ttlSeconds: Long = 300, val retryAfterSeconds: Long = 60)
class MobilePasswordResetVerified(val resetToken: String, val expiresAt: Instant, val phoneMasked: String) {
    override fun toString() = "MobilePasswordResetVerified([REDACTED])"
}
