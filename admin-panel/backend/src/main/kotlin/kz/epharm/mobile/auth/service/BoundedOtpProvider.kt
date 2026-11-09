package kz.epharm.mobile.auth.service

import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import java.util.concurrent.Semaphore

/**
 * Never queue a remote SMS call while retaining a transaction/connection. All channels share
 * this budget; their existing phone locks and durable one-use/attempt semantics stay intact.
 */
class BoundedOtpProvider(private val delegate: OtpProvider, maxConcurrent: Int) : OtpProvider {
    init { require(maxConcurrent > 0) { "SMS max-concurrent must be positive" } }
    private val permits = Semaphore(maxConcurrent)
    override val id: String get() = delegate.id
    override val verificationMode: OtpVerificationMode get() = delegate.verificationMode

    override fun requestOtp(phone: String, localCode: String?) = bounded { delegate.requestOtp(phone, localCode) }
    override fun verifyOtp(phone: String, code: String): OtpVerificationResult = bounded { delegate.verifyOtp(phone, code) }

    private inline fun <T> bounded(action: () -> T): T {
        if (!permits.tryAcquire()) {
            // No invented cooldown: the provider has not accepted this request and its availability is unknown.
            throw AppException(ErrorCode.OTP_PROVIDER_UNAVAILABLE,
                "Сервис SMS занят. Повторите попытку позже", HttpStatus.SERVICE_UNAVAILABLE)
        }
        return try { action() } finally { permits.release() }
    }
}
