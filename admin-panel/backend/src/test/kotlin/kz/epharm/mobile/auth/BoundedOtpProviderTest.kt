package kz.epharm.mobile.auth

import kz.epharm.mobile.auth.service.*
import kz.epharm.shared.error.AppException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BoundedOtpProviderTest {
    @Test fun `interrupted provider releases its permit before next task`() {
        val entered = CountDownLatch(1)
        val neverReleased = CountDownLatch(1)
        val provider = BoundedOtpProvider(object : OtpProvider {
            override val id = "test"
            override val verificationMode = OtpVerificationMode.EXTERNAL
            override fun requestOtp(phone: String, localCode: String?) {
                entered.countDown()
                check(neverReleased.await(5, TimeUnit.SECONDS))
            }
            override fun verifyOtp(phone: String, code: String) = OtpVerificationResult.VERIFIED
        }, 1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val interrupted = executor.submit { provider.requestOtp("synthetic", null) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(interrupted.cancel(true))
            // Same worker: execution starts only after the interrupted call unwinds its finally.
            assertEquals(OtpVerificationResult.VERIFIED,
                executor.submit<OtpVerificationResult> { provider.verifyOtp("synthetic", "code") }.get(5, TimeUnit.SECONDS))
        } finally { neverReleased.countDown(); executor.shutdownNow() }
    }

    @Test fun `requests and verification share nonblocking budget and release on failure`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val provider = BoundedOtpProvider(object : OtpProvider {
            override val id = "test"
            override val verificationMode = OtpVerificationMode.EXTERNAL
            override fun requestOtp(phone: String, localCode: String?) {
                calls.incrementAndGet(); entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                throw IllegalStateException("simulated provider error")
            }
            override fun verifyOtp(phone: String, code: String): OtpVerificationResult {
                calls.incrementAndGet(); return OtpVerificationResult.VERIFIED
            }
        }, 1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val first = executor.submit { assertThrows(IllegalStateException::class.java) { provider.requestOtp("synthetic", null) } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val error = assertThrows(AppException::class.java) { provider.verifyOtp("synthetic", "code") }
            assertEquals(503, error.status.value()); assertNull(error.retryAfterSeconds)
            assertEquals(1, calls.get())
            release.countDown(); first.get(5, TimeUnit.SECONDS)
            assertEquals(OtpVerificationResult.VERIFIED, provider.verifyOtp("synthetic", "code"))
            assertEquals(2, calls.get())
        } finally { release.countDown(); executor.shutdownNow() }
    }
}
