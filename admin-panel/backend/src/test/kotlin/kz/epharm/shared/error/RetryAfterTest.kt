package kz.epharm.shared.error

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.time.Instant

class RetryAfterTest {
    @Test fun `deadline rounds up and HTTP and JSON expose the same actual cooldown`() {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        assertEquals(2L, retryAfterSeconds(now, now.plusMillis(1001)))
        assertEquals(1L, retryAfterSeconds(now, now.plusMillis(1)))
        assertEquals(60L, retryAfterSeconds(now, now.plusSeconds(60)))
        val response = GlobalExceptionHandler().handleApp(AppException(ErrorCode.OTP_RESEND_TOO_SOON,
            "wait", HttpStatus.TOO_MANY_REQUESTS, retryAfterSeconds = 17))
        assertEquals("17", response.headers.getFirst("Retry-After"))
        assertEquals(17L, response.body!!.retryAfterSeconds)
        assertEquals("no-store", response.headers.cacheControl)
    }
    @Test fun `exhausted proof and unknown provider availability do not invent a deadline`() {
        for (status in listOf(HttpStatus.TOO_MANY_REQUESTS, HttpStatus.SERVICE_UNAVAILABLE)) {
            val response = GlobalExceptionHandler().handleApp(AppException(ErrorCode.OTP_TOO_MANY_ATTEMPTS,"new code needed",status))
            assertNull(response.headers.getFirst("Retry-After")); assertNull(response.body!!.retryAfterSeconds)
        }
        for (invalid in listOf(0L, -1L)) {
            val response = GlobalExceptionHandler().handleApp(AppException(ErrorCode.OTP_TOO_MANY_ATTEMPTS,
                "wait", HttpStatus.TOO_MANY_REQUESTS, retryAfterSeconds = invalid))
            assertNull(response.headers.getFirst("Retry-After"))
        }
    }
    @Test fun `exhausted OTP proof asks for a new code instead of an invented pause`() {
        val response = GlobalExceptionHandler().handleApp(AppException(ErrorCode.OTP_TOO_MANY_ATTEMPTS,
            "new code needed", HttpStatus.TOO_MANY_REQUESTS, retryAction = RetryAction.REQUEST_NEW_OTP))
        assertEquals(RetryAction.REQUEST_NEW_OTP, response.body!!.retryAction)
        assertNull(response.headers.getFirst("Retry-After"))
        assertNull(response.body!!.retryAfterSeconds)
    }
}
