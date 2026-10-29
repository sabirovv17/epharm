package kz.epharm.merchtasks

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kz.epharm.merch.delivery.NativeMerchTaskDeliveryService
import kz.epharm.merchtasks.dto.*
import kz.epharm.merchtasks.service.MerchTaskClient
import kz.epharm.merchtasks.service.MerchTaskDelivery
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessResourceFailureException

class MerchTaskDeliveryTest {
    private val legacy = mockk<MerchTaskClient>()
    private val native = mockk<NativeMerchTaskDeliveryService>()
    private val request = MerchTaskShownRequest("dispatch-synthetic", "pharmacy-synthetic", "device-synthetic", "synthetic-delivery-token")

    @Test fun `disabled issuance retains legacy and old unknown ACK contract`() {
        val expected = MerchTaskEnvelope()
        every { legacy.activeTask("pharmacy-synthetic") } returns expected
        every { native.ownsNativeDeliveryToken(request.deliveryToken) } returns false
        every { legacy.markShown(request) } returns MerchTaskDeliveryResult(true)
        val bridge = MerchTaskDelivery(legacy, native, false)
        assertThat(bridge.activeTask("pharmacy-synthetic")).isSameAs(expected)
        assertThat(bridge.markShown(request).accepted).isTrue()
        verify(exactly=0) { native.activeTask(any()); native.markShown(any()) }
    }

    @Test fun `native owned ACK still routes native after issuance rollback without legacy fallback`() {
        every { native.ownsNativeDeliveryToken(request.deliveryToken) } returns true
        every { native.markShown(request) } returns MerchTaskDeliveryResult(false)
        assertThat(MerchTaskDelivery(legacy, native, false).markShown(request).accepted).isFalse()
        verify(exactly=0) { legacy.markShown(any()) }
    }

    @Test fun `native unavailable signals optional failure and never changes writer`() {
        every { native.activeTask(any()) } throws DataAccessResourceFailureException("synthetic unavailable")
        assertThat(MerchTaskDelivery(legacy, native, true).activeTask("pharmacy-synthetic").available).isFalse()
        verify(exactly=0) { legacy.activeTask(any()) }
    }

    @Test fun `unknown ownership during database outage never guesses legacy delivery`() {
        every { native.ownsNativeDeliveryToken(any()) } throws DataAccessResourceFailureException("synthetic unavailable")
        assertThat(MerchTaskDelivery(legacy, native, false).markShown(request).available).isFalse()
        verify(exactly=0) { legacy.markShown(any()) }
    }
}
