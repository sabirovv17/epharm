package kz.epharm.mobile.auth

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kz.epharm.mobile.auth.entity.MobileOtpEntity
import kz.epharm.mobile.auth.repository.MobileOtpRepository
import kz.epharm.mobile.auth.service.OtpProvider
import kz.epharm.mobile.auth.service.OtpService
import kz.epharm.mobile.auth.service.OtpVerificationMode
import kz.epharm.mobile.auth.service.OtpVerificationResult
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional

class OtpServiceExternalProviderTest {
    private val repository = mockk<MobileOtpRepository>()
    private val provider = mockk<OtpProvider>()

    init {
        every { provider.id } returns "daribar"
        every { provider.verificationMode } returns OtpVerificationMode.EXTERNAL
    }

    @Test
    fun `external request не генерирует локальный код и сохраняет provider`() {
        val saved = slot<MobileOtpEntity>()
        every { repository.findById(PHONE) } returns Optional.empty()
        every { repository.save(capture(saved)) } answers { saved.captured }
        every { provider.requestOtp(PHONE, null) } returns Unit

        val result = service().request(PHONE, NOW)

        assertThat(result.devCode).isNull()
        assertThat(saved.captured.verificationProvider).isEqualTo("daribar")
        assertThat(saved.captured.codeHash).isNotBlank()
        verify(exactly = 1) { provider.requestOtp(PHONE, null) }
    }

    @Test
    fun `external verify success отмечает номер подтверждённым`() {
        val entity = pendingEntity()
        every { repository.findById(PHONE) } returns Optional.of(entity)
        every { repository.save(entity) } returns entity
        every { provider.verifyOtp(PHONE, "123456") } returns OtpVerificationResult.VERIFIED

        service().verify(PHONE, "123456", NOW)

        assertThat(entity.verifiedAt).isEqualTo(NOW)
    }

    @Test
    fun `external invalid увеличивает локальный счётчик попыток`() {
        val entity = pendingEntity()
        every { repository.findById(PHONE) } returns Optional.of(entity)
        every { repository.save(entity) } returns entity
        every { provider.verifyOtp(PHONE, "000000") } returns OtpVerificationResult.INVALID

        assertThatThrownBy { service().verify(PHONE, "000000", NOW) }
            .isInstanceOf(AppException::class.java)
            .extracting("code").isEqualTo(ErrorCode.OTP_INVALID)
        assertThat(entity.attempts).isEqualTo(1)
    }

    @Test
    fun `смена provider требует запросить новый код и не вызывает внешний auth`() {
        val entity = pendingEntity().also { it.verificationProvider = "local" }
        every { repository.findById(PHONE) } returns Optional.of(entity)

        assertThatThrownBy { service().verify(PHONE, "544544", NOW) }
            .isInstanceOf(AppException::class.java)
            .extracting("code").isEqualTo(ErrorCode.OTP_NOT_REQUESTED)
        verify(exactly = 0) { provider.verifyOtp(any(), any()) }
    }

    @Test
    fun `недоступность gateway не расходует попытку`() {
        val entity = pendingEntity()
        every { repository.findById(PHONE) } returns Optional.of(entity)
        every { provider.verifyOtp(PHONE, "123456") } throws AppException(
            ErrorCode.OTP_PROVIDER_UNAVAILABLE,
            "unavailable",
        )

        assertThatThrownBy { service().verify(PHONE, "123456", NOW) }
            .isInstanceOf(AppException::class.java)
            .extracting("code").isEqualTo(ErrorCode.OTP_PROVIDER_UNAVAILABLE)
        assertThat(entity.attempts).isZero()
    }

    private fun service() = OtpService(
        otpRepository = repository,
        otpProvider = provider,
        devMode = false,
        devFixed = "544544",
        ttlSeconds = 300,
        maxAttempts = 5,
        registerWindowSeconds = 900,
        resendCooldownSeconds = 60,
    )

    private fun pendingEntity() = MobileOtpEntity(
        phone = PHONE,
        codeHash = "opaque",
        verificationProvider = "daribar",
        expiresAt = NOW.plusSeconds(300),
        attempts = 0,
        createdAt = NOW,
    )

    companion object {
        private const val PHONE = "+77011112233"
        private val NOW = Instant.parse("2026-08-20T10:00:00Z")
    }
}
