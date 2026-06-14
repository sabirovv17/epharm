package kz.epharm.posm

import kz.epharm.posm.service.DevicePresenceService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

/** Подсчёт подключённых касс (T4): пульсы, дедуп по deviceId, протухание по TTL. */
class DevicePresenceServiceTest {

    private val svc = DevicePresenceService(ttlSeconds = 90)
    private val t0: Instant = Instant.parse("2026-06-14T10:00:00Z")

    @Test
    fun `несколько касс онлайн, повторный пульс не плодит дубли`() {
        svc.heartbeat("kassa-1", "ph_a", t0)
        svc.heartbeat("kassa-1", "ph_a", t0.plusSeconds(10)) // тот же deviceId — обновление
        svc.heartbeat("kassa-2", "ph_b", t0)

        assertEquals(2, svc.count(t0.plusSeconds(20)))
    }

    @Test
    fun `протухший пульс не считается онлайн`() {
        svc.heartbeat("kassa-1", null, t0)
        assertEquals(1, svc.count(t0.plusSeconds(60)))   // в пределах TTL
        assertEquals(0, svc.count(t0.plusSeconds(120)))  // 120с > 90с TTL → офлайн
    }

    @Test
    fun `connected возвращает детали устройства`() {
        svc.heartbeat("kassa-7", "ph_z", t0)
        val list = svc.connected(t0.plusSeconds(5))
        assertEquals(1, list.size)
        assertEquals("kassa-7", list[0].deviceId)
        assertEquals("ph_z", list[0].pharmacyId)
    }
}
