package kz.epharm.posm

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kz.epharm.posm.service.DevicePresenceService
import kz.epharm.posm.service.StaffAuthStatus
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.redis.core.DefaultTypedTuple
import org.springframework.data.redis.core.HashOperations
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ZSetOperations
import org.springframework.jdbc.core.JdbcTemplate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.RowMapper
import java.sql.Timestamp
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
        svc.heartbeat("kassa-7", "ph_z", t0, monitorCount = 2, appVersion = "1.0.46.0")
        val list = svc.connected(t0.plusSeconds(5))
        assertEquals(1, list.size)
        assertEquals("kassa-7", list[0].deviceId)
        assertEquals("ph_z", list[0].pharmacyId)
        assertEquals(2, list[0].monitorCount)
        assertEquals("1.0.46.0", list[0].appVersion)
    }

    @Test
    fun `старый heartbeat сохраняет ранее известное количество мониторов`() {
        svc.heartbeat("kassa-8", "ph_z", t0, monitorCount = 2, appVersion = "1.0.46.0")
        svc.heartbeat("kassa-8", "ph_z", t0.plusSeconds(10))

        assertEquals(2, svc.connected(t0.plusSeconds(20)).single().monitorCount)
        assertEquals("1.0.46.0", svc.connected(t0.plusSeconds(20)).single().appVersion)
    }

    @Test
    fun `staff status is fixed diagnostic and omitted heartbeat clears stale auth claim`() {
        svc.heartbeat("staff-device", "ph_a", t0, staffAuthStatus = "login_visible")
        assertEquals("login_visible", svc.connected(t0.plusSeconds(1)).single().staffAuthStatus)
        svc.heartbeat("staff-device", "ph_a", t0.plusSeconds(2), staffAuthStatus = "authenticated")
        assertEquals("authenticated", svc.connected(t0.plusSeconds(3)).single().staffAuthStatus)
        svc.heartbeat("staff-device", "ph_a", t0.plusSeconds(4))
        assertEquals(null, svc.connected(t0.plusSeconds(5)).single().staffAuthStatus)
        svc.heartbeat("staff-device", "ph_a", t0.plusSeconds(6), staffAuthStatus = "IIN=000101500011 password=private")
        assertEquals(null, svc.connected(t0.plusSeconds(7)).single().staffAuthStatus)
        assertEquals(null, StaffAuthStatus.normalize("authenticated\nprivate"))
        assertEquals(null, StaffAuthStatus.normalize("unknown"))
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
        every { hash.multiGet("epharm:posm:presence:monitor-count", any()) } returns listOf("2")
        every { hash.multiGet("epharm:posm:presence:app-version", any()) } returns listOf("1.0.46.0")
        every { hash.multiGet("epharm:posm:presence:staff-auth-status", any()) } returns listOf("database_unavailable")

        val writer = DevicePresenceService(ttlSeconds = 90, redisProvider = provider)
        writer.heartbeat(
            "kassa-redis",
            "ph_redis",
            t0,
            monitorCount = 2,
            appVersion = "1.0.46.0",
            staffAuthStatus = "database_unavailable",
        )
        val key = "ph_redis\u001Fkassa-redis"
        verify { zset.add("epharm:posm:presence:last-seen", key, t0.toEpochMilli().toDouble()) }
        verify { hash.put("epharm:posm:presence:pharmacy", key, "ph_redis") }
        verify { hash.put("epharm:posm:presence:device", key, "kassa-redis") }
        verify { hash.put("epharm:posm:presence:monitor-count", key, "2") }
        verify { hash.put("epharm:posm:presence:app-version", key, "1.0.46.0") }
        verify { hash.put("epharm:posm:presence:staff-auth-status", key, "database_unavailable") }

        val afterRestart = DevicePresenceService(ttlSeconds = 90, redisProvider = provider)
        val restored = afterRestart.connected(t0.plusSeconds(10))
        assertEquals(1, restored.size)
        assertEquals("kassa-redis", restored.single().deviceId)
        assertEquals("ph_redis", restored.single().pharmacyId)
        assertEquals(2, restored.single().monitorCount)
        assertEquals("1.0.46.0", restored.single().appVersion)
        assertEquals("database_unavailable", restored.single().staffAuthStatus)
    }

    @Test
    fun `legacy heartbeat clears redis auth diagnostic across database merge and restart`() {
        val provider = mockk<ObjectProvider<StringRedisTemplate>>()
        val redis = mockk<StringRedisTemplate>()
        val zset = mockk<ZSetOperations<String, String>>(relaxed = true)
        val hash = mockk<HashOperations<String, String, String>>(relaxed = true)
        val jdbc = mockk<JdbcTemplate>()
        var staffStatus: String? = "authenticated"
        val key = "ph_merge\u001Fkassa-merge"
        every { provider.getIfAvailable() } returns redis
        every { redis.opsForZSet() } returns zset
        every { redis.opsForHash<String, String>() } returns hash
        every { zset.rangeByScoreWithScores(any(), any(), any()) } returns
            setOf(DefaultTypedTuple(key, t0.plusSeconds(10).toEpochMilli().toDouble()))
        every { hash.multiGet("epharm:posm:presence:pharmacy", any()) } returns listOf("ph_merge")
        every { hash.multiGet("epharm:posm:presence:device", any()) } returns listOf("kassa-merge")
        every { hash.multiGet("epharm:posm:presence:staff-auth-status", any()) } answers { listOf(staffStatus) }
        every { hash.delete("epharm:posm:presence:staff-auth-status", key) } answers { staffStatus = null; 1L }
        every { jdbc.query(any<String>(), any<RowMapper<DevicePresenceService.Presence>>(), any<Timestamp>()) } returns listOf(
            DevicePresenceService.Presence("kassa-merge", "ph_merge", t0.plusSeconds(15)),
        )
        val writer = DevicePresenceService(90, provider, jdbc)
        assertEquals("authenticated", writer.connected(t0.plusSeconds(16)).single().staffAuthStatus)
        writer.heartbeat("kassa-merge", "ph_merge", t0.plusSeconds(20))
        assertEquals(null, writer.connected(t0.plusSeconds(21)).single().staffAuthStatus)
        assertEquals(null, DevicePresenceService(90, provider, jdbc).connected(t0.plusSeconds(21)).single().staffAuthStatus)
        staffStatus = "iin=000101500011"
        assertEquals(null, DevicePresenceService(90, provider, jdbc).connected(t0.plusSeconds(21)).single().staffAuthStatus)
    }

    @Test
    fun `count reads only active redis keys without loading device metadata`() {
        val provider = mockk<ObjectProvider<StringRedisTemplate>>()
        val redis = mockk<StringRedisTemplate>()
        val zset = mockk<ZSetOperations<String, String>>()
        every { provider.getIfAvailable() } returns redis
        every { redis.opsForZSet() } returns zset
        every {
            zset.rangeByScore(
                "epharm:posm:presence:last-seen",
                t0.minusSeconds(90).toEpochMilli().toDouble(),
                Double.POSITIVE_INFINITY,
            )
        } returns setOf("ph_a\u001Fkassa-1", "ph_b\u001Fkassa-2")

        val count = DevicePresenceService(ttlSeconds = 90, redisProvider = provider).count(t0)

        assertEquals(2, count)
        verify(exactly = 0) { redis.opsForHash<String, String>() }
    }

    @Test
    fun `recent per-device database activity keeps register online without redis heartbeat`() {
        val jdbc = mockk<JdbcTemplate>()
        every {
            jdbc.query(
                any<String>(),
                any<RowMapper<DevicePresenceService.Presence>>(),
                any<Timestamp>(),
            )
        } returns listOf(
            DevicePresenceService.Presence(
                deviceId = "kassa-durable",
                pharmacyId = "ph_durable",
                lastSeen = t0.minusSeconds(5),
            ),
        )

        val service = DevicePresenceService(ttlSeconds = 90, jdbc = jdbc)

        assertEquals(1, service.count(t0))
        assertEquals("kassa-durable", service.connected(t0).single().deviceId)
    }

    @Test
    fun `redis and database activity for same register are counted once`() {
        val provider = mockk<ObjectProvider<StringRedisTemplate>>()
        val redis = mockk<StringRedisTemplate>()
        val zset = mockk<ZSetOperations<String, String>>()
        val jdbc = mockk<JdbcTemplate>()
        val key = "ph_a\u001Fkassa-1"
        every { provider.getIfAvailable() } returns redis
        every { redis.opsForZSet() } returns zset
        every { zset.rangeByScore(any(), any(), any()) } returns setOf(key)
        every {
            jdbc.query(
                any<String>(),
                any<RowMapper<DevicePresenceService.Presence>>(),
                any<Timestamp>(),
            )
        } returns listOf(
            DevicePresenceService.Presence(
                deviceId = "kassa-1",
                pharmacyId = "ph_a",
                lastSeen = t0.minusSeconds(5),
            ),
        )

        val service = DevicePresenceService(ttlSeconds = 90, redisProvider = provider, jdbc = jdbc)

        assertEquals(1, service.count(t0))
    }
}
