package kz.epharm.posm

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kz.epharm.posm.service.DevicePresenceService
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.redis.core.DefaultTypedTuple
import org.springframework.data.redis.core.HashOperations
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ZSetOperations
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

/** Подсчёт подключённых касс (T4): пульсы, дедуп по аптеке + устройству, протухание по TTL. */
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
    fun `одно имя Windows в разных аптеках считается разными кассами`() {
        svc.heartbeat("KASSA1", "ph_a", t0)
        svc.heartbeat("KASSA1", "ph_b", t0.plusSeconds(5))

        val connected = svc.connected(t0.plusSeconds(10))
        assertEquals(2, connected.size)
        assertEquals(setOf("ph_a", "ph_b"), connected.mapNotNull { it.pharmacyId }.toSet())
        assertEquals(setOf("KASSA1"), connected.map { it.deviceId }.toSet())
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

    @Test
    fun `heartbeat persists and connected restores device from redis`() {
        val provider = mockk<ObjectProvider<StringRedisTemplate>>()
        val redis = mockk<StringRedisTemplate>()
        val zset = mockk<ZSetOperations<String, String>>()
        val hash = mockk<HashOperations<String, String, String>>()
        every { provider.getIfAvailable() } returns redis
        every { redis.opsForZSet() } returns zset
        every { redis.opsForHash<String, String>() } returns hash
        every { zset.add(any(), any(), any()) } returns true
        every { hash.put(any(), any(), any()) } returns Unit
        every { zset.rangeByScore(any(), any(), any()) } returns emptySet()
        every { zset.rangeByScoreWithScores(any(), any(), any()) } returns
            setOf(DefaultTypedTuple("kassa-redis", t0.toEpochMilli().toDouble()))
        every { hash.multiGet("epharm:posm:presence:pharmacy", any()) } returns listOf("ph_redis")
        every { hash.multiGet("epharm:posm:presence:device", any()) } returns listOf("kassa-redis")

        val writer = DevicePresenceService(ttlSeconds = 90, redisProvider = provider)
        writer.heartbeat("kassa-redis", "ph_redis", t0)
        val key = "ph_redis\u001Fkassa-redis"
        verify { zset.add("epharm:posm:presence:last-seen", key, t0.toEpochMilli().toDouble()) }
        verify { hash.put("epharm:posm:presence:pharmacy", key, "ph_redis") }
        verify { hash.put("epharm:posm:presence:device", key, "kassa-redis") }

        val afterRestart = DevicePresenceService(ttlSeconds = 90, redisProvider = provider)
        val restored = afterRestart.connected(t0.plusSeconds(10))
        assertEquals(1, restored.size)
        assertEquals("kassa-redis", restored.single().deviceId)
        assertEquals("ph_redis", restored.single().pharmacyId)
    }
}
