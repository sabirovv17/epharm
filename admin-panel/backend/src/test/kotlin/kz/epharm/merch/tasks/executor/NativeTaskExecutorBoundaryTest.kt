package kz.epharm.merch.tasks.executor

import io.mockk.*
import java.time.Clock
import java.util.UUID
import kz.epharm.finance.merch.MerchRewardSecurity
import kz.epharm.finance.merch.VerifiedMerchRecipientPolicy
import kz.epharm.merch.delivery.NativeCapabilityAuthorization
import kz.epharm.mobile.auth.service.OtpProvider
import kz.epharm.posm.staff.StaffPhoneLock
import kz.epharm.shared.error.AppException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate

class NativeTaskExecutorBoundaryTest {
    @Test fun `disabled native execution dev OTP or weak server key cannot reach provider capability or database`() {
        val jdbc=mockk<JdbcTemplate>();val caps=mockk<NativeCapabilityAuthorization>();val recipients=mockk<VerifiedMerchRecipientPolicy>()
        val phones=mockk<StaffPhoneLock>();val provider=mockk<OtpProvider>();val security=mockk<MerchRewardSecurity>()
        for((enabled,dev,secret) in listOf(Triple(false,false,"synthetic-server-key-of-more-than-32-bytes"),
            Triple(true,true,"synthetic-server-key-of-more-than-32-bytes"),Triple(true,false,"weak"))) {
            val service=NativeTaskExecutorService(jdbc,caps,recipients,phones,provider,security,enabled,dev,secret,Clock.systemUTC())
            val error=assertThrows(AppException::class.java) {service.requestSms(UUID.randomUUID(),"synthetic","synthetic-token")}
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE,error.status)
        }
        verify {listOf(jdbc,caps,recipients,phones,provider,security) wasNot Called}
    }
}
