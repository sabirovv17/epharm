package kz.epharm.screens

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kz.epharm.pharmacies.repository.PharmacyRepository
import kz.epharm.posm.service.DevicePresenceService
import kz.epharm.screens.service.ScreenPresenceService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class ScreenPresenceServiceTest {

    @Test
    fun `summary reads presence without touching pharmacy database`() {
        val presence = mockk<DevicePresenceService>()
        val pharmacies = mockk<PharmacyRepository>()
        val observedAt = Instant.parse("2026-09-15T15:00:00Z")
        every { presence.count(observedAt) } returns 388

        val result = ScreenPresenceService(presence, pharmacies).connectedSummary(observedAt)

        assertEquals(388, result.total)
        assertEquals(observedAt, result.observedAt)
        verify(exactly = 1) { presence.count(observedAt) }
        verify(exactly = 0) { pharmacies.findAllById(any<Iterable<String>>()) }
    }
}
