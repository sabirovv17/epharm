package kz.epharm.mobile.auth.service

import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.util.concurrent.Semaphore

/** Apply to each transactional entry point that calls an OTP provider or takes a phone lock. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class OtpTransactionAdmission

/** Runs BEFORE Spring's transaction advisor: rejected work never borrows a DB connection. */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
class OtpTransactionAdmissionAspect(
    @Value("\${app.sms.max-concurrent-transactions:4}") maxConcurrent: Int = 4,
    @Value("\${spring.datasource.hikari.maximum-pool-size:10}") databasePoolSize: Int = 10,
) {
    init {
        require(maxConcurrent > 0) { "SMS max-concurrent-transactions must be positive" }
        require(databasePoolSize >= 4) { "OTP admission requires a database pool of at least 4 connections" }
    }
    // Mobile wrappers can suspend an outer transaction for a REQUIRES_NEW OTP transaction.
    // Budget both connections and reserve at least two for heartbeat/session/other work.
    private val permits = Semaphore(minOf(maxConcurrent, (databasePoolSize - 2) / 2))
    private val admitted = ThreadLocal<Boolean>()

    @Around("@annotation(kz.epharm.mobile.auth.service.OtpTransactionAdmission)")
    fun invoke(call: ProceedingJoinPoint): Any? {
        if (admitted.get() == true) return call.proceed()
        if (!permits.tryAcquire()) throw AppException(ErrorCode.OTP_PROVIDER_UNAVAILABLE,
            "Сервис регистрации и SMS занят. Повторите попытку позже", HttpStatus.SERVICE_UNAVAILABLE)
        admitted.set(true)
        return try { call.proceed() } finally { admitted.remove(); permits.release() }
    }
}
