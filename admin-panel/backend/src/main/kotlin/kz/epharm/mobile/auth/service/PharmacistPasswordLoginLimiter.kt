package kz.epharm.mobile.auth.service

import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest

/** Both POSM and /learn authenticate the same password, so they share one attempt budget. */
@Service
class PharmacistPasswordLoginLimiter(private val jdbc: JdbcTemplate) {
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = [AppException::class])
    fun check(iin: String) {
        val scope = MessageDigest.getInstance("SHA-256").digest("login-account:$iin".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val attempt = takeFixedWindowAttempt(jdbc, scope, 300)
        if (attempt.attempts > 10) throw AppException(ErrorCode.OTP_TOO_MANY_ATTEMPTS,
            "Слишком много попыток — попробуйте позже", HttpStatus.TOO_MANY_REQUESTS,
            retryAfterSeconds = attempt.retryAfterSeconds)
    }
}
