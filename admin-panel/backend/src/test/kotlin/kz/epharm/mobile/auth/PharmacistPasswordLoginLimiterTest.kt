package kz.epharm.mobile.auth

import io.mockk.every
import io.mockk.mockk
import kz.epharm.mobile.auth.service.FixedWindowAttempt
import kz.epharm.mobile.auth.service.PharmacistPasswordLoginLimiter
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper

class PharmacistPasswordLoginLimiterTest {
    @Test fun `account attempt keys canonicalize exactly as the validated account lookup`() {
        val jdbc = mockk<JdbcTemplate>()
        val observed = mutableListOf<String>()
        every { jdbc.query(any<String>(), any<RowMapper<FixedWindowAttempt>>(), any<String>(), any<Long>(), any<Long>(), any<Long>()) } answers {
            observed += arg<Array<Any?>>(2)[0] as String
            listOf(FixedWindowAttempt(observed.size, 299))
        }
        val limiter = PharmacistPasswordLoginLimiter(jdbc)
        repeat(10) { index -> limiter.check(" ".repeat(index) + "000101500011" + "\t".repeat(index)) }
        val failure = assertThrows(AppException::class.java) { limiter.check("000101500011") }
        assertEquals(ErrorCode.OTP_TOO_MANY_ATTEMPTS, failure.code)
        assertEquals(299L, failure.retryAfterSeconds)
        assertEquals(1, observed.toSet().size, "Equivalent IIN inputs must use one durable attempt row")
    }
}
