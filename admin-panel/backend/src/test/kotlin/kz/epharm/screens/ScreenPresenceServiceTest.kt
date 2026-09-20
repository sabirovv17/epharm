package kz.epharm.screens

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kz.epharm.pharmacies.entity.PharmacyEntity
import kz.epharm.pharmacies.repository.PharmacyRepository
import kz.epharm.posm.service.DevicePresenceService
import kz.epharm.screens.service.ScreenPresenceService
import org.springframework.jdbc.core.JdbcTemplate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class ScreenPresenceServiceTest {

    @Test
    fun `summary reads presence without touching pharmacy database`() {
        val presence = mockk<DevicePresenceService>()
        val pharmacies = mockk<PharmacyRepository>()
        val jdbc = mockk<JdbcTemplate>()
        val observedAt = Instant.parse("2026-09-15T15:00:00Z")
        every { presence.count(observedAt) } returns 388

        val result = ScreenPresenceService(presence, pharmacies, jdbc).connectedSummary(observedAt)

        assertEquals(388, result.total)
        assertEquals(observedAt, result.observedAt)
        verify(exactly = 1) { presence.count(observedAt) }
        verify(exactly = 0) { pharmacies.findAllById(any<Iterable<String>>()) }
    }

    @Test
    fun `coverage counts only active pharmacies and their online registers`() {
        val presence = mockk<DevicePresenceService>()
        val pharmacies = mockk<PharmacyRepository>()
        val jdbc = mockk<JdbcTemplate>()
        val observedAt = Instant.parse("2026-09-20T12:00:00Z")
        every { pharmacies.findAllByActiveTrueOrderByNameAsc() } returns listOf(
            pharmacy("ph_ready", "Готовая"),
            pharmacy("ph_gap", "Без POSM"),
        )
        every { jdbc.queryForList(any<String>(), String::class.java) } returns listOf("ph_ready")
        every { presence.connected(observedAt) } returns listOf(
            DevicePresenceService.Presence("kassa-1", "ph_ready", observedAt),
            DevicePresenceService.Presence("kassa-2", "ph_ready", observedAt),
            DevicePresenceService.Presence("orphan", "ph_inactive", observedAt),
        )

        val result = ScreenPresenceService(presence, pharmacies, jdbc).coverage(observedAt)

        assertEquals(2, result.activePharmacies)
        assertEquals(1, result.provisionedPharmacies)
        assertEquals(1, result.onlinePharmacies)
        assertEquals(2, result.onlineRegisters)
        assertEquals(listOf("ph_gap"), result.unprovisionedPharmacies.map { it.pharmacyId })
    }

    private fun pharmacy(id: String, name: String) = PharmacyEntity(
        id = id,
        name = name,
        chainId = "ch",
        chainName = "Сеть",
        city = "Алматы",
        addr = "Адрес",
    )
}
