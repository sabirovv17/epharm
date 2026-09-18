package kz.epharm.posm.service

import org.springframework.beans.factory.ObjectProvider
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Присутствие касс (T4): сколько POSM-устройств сейчас «онлайн».
 *
 * Подход — хелсчек раз в минуту: касса шлёт POST /api/posm/heartbeat каждые ~60с,
 * мы помним последний пульс по паре pharmacyId + deviceId. Имя компьютера Windows часто
 * повторяется между аптеками (например, KASSA1), поэтому deviceId сам по себе нельзя
 * использовать как ключ присутствия. «Онлайн» = пульс не старше TTL
 * (`app.posm.heartbeat-ttl-seconds`, по умолчанию 90с ≈ 1.5 интервала).
 *
 * Redis хранит shared/persistent last-seen, поэтому список не обнуляется при рестарте backend и
 * работает при нескольких инстансах. ConcurrentHashMap остаётся fail-safe, если Redis временно
 * недоступен или отключён в тестовом профиле.
 */
@Service
class DevicePresenceService(
    @Value("\${app.posm.heartbeat-ttl-seconds:90}") private val ttlSeconds: Long,
    private val redisProvider: ObjectProvider<StringRedisTemplate>? = null,
    private val jdbc: JdbcTemplate? = null,
) {
    private val seen = ConcurrentHashMap<String, Presence>()
    private val log = LoggerFactory.getLogger(DevicePresenceService::class.java)
    private val lastRedisWarningAt = AtomicLong(0)
    private val lastDatabaseWarningAt = AtomicLong(0)

    companion object {
        private const val LAST_SEEN_KEY = "epharm:posm:presence:last-seen"
        private const val PHARMACY_KEY = "epharm:posm:presence:pharmacy"
        private const val DEVICE_KEY = "epharm:posm:presence:device"
        private const val MONITOR_COUNT_KEY = "epharm:posm:presence:monitor-count"
        private const val APP_VERSION_KEY = "epharm:posm:presence:app-version"
        private const val REDIS_WARNING_INTERVAL_MS = 60_000L
        private const val STORAGE_SEPARATOR = "\u001F"

        private fun presenceKey(deviceId: String, pharmacyId: String?): String =
            pharmacyId?.let { "$it$STORAGE_SEPARATOR$deviceId" } ?: deviceId
    }

    data class Presence(
        val deviceId: String,
        val pharmacyId: String?,
        val lastSeen: Instant,
        /** null пока касса не обновилась до клиента, передающего топологию экранов. */
        val monitorCount: Int? = null,
        /** null пока касса не обновилась до клиента с телеметрией версии. */
        val appVersion: String? = null,
        internal val storageKey: String = presenceKey(deviceId, pharmacyId),
    )

    /** Зафиксировать пульс устройства. Логируем INFO только на ПОДКЛЮЧЕНИЕ (новый/после оффлайна),
     *  чтобы не спамить каждые 60с, но было видно «касса подключилась» на бэкенде. */
    fun heartbeat(
        deviceId: String,
        pharmacyId: String?,
        now: Instant = Instant.now(),
        monitorCount: Int? = null,
        appVersion: String? = null,
    ) {
        val normalizedPharmacy = pharmacyId?.takeIf { it.isNotBlank() }
        val key = presenceKey(deviceId, normalizedPharmacy)
        val prev = seen[key]
        val wasOffline = prev == null || prev.lastSeen.isBefore(now.minusSeconds(ttlSeconds))
        val effectiveMonitorCount = monitorCount ?: prev?.monitorCount
        val effectiveAppVersion = appVersion ?: prev?.appVersion
        seen[key] = Presence(
            deviceId = deviceId,
            pharmacyId = normalizedPharmacy,
            lastSeen = now,
            monitorCount = effectiveMonitorCount,
            appVersion = effectiveAppVersion,
            storageKey = key,
        )

        withRedis { redis ->
            redis.opsForZSet().add(LAST_SEEN_KEY, key, now.toEpochMilli().toDouble())
            redis.opsForHash<String, String>().put(PHARMACY_KEY, key, normalizedPharmacy ?: "")
            redis.opsForHash<String, String>().put(DEVICE_KEY, key, deviceId)
            if (monitorCount != null) {
                redis.opsForHash<String, String>().put(MONITOR_COUNT_KEY, key, monitorCount.toString())
            }
            if (appVersion != null) {
                redis.opsForHash<String, String>().put(APP_VERSION_KEY, key, appVersion)
            }
        }

        if (wasOffline) {
            log.info("POSM: касса ПОДКЛЮЧИЛАСЬ — deviceId={}, аптека={}", deviceId, pharmacyId ?: "—")
        }
    }

    /** Живые устройства (пульс не старше TTL). Заодно чистим протухшие записи (лог об отключении). */
    fun connected(now: Instant = Instant.now()): List<Presence> {
        val cutoff = now.minusSeconds(ttlSeconds)
        evictExpiredLocal(cutoff)

        val combined = HashMap<String, Presence>()
        fun merge(candidate: Presence) {
            val stored = combined[candidate.storageKey]
            if (stored == null) {
                combined[candidate.storageKey] = candidate
            } else {
                val latest = if (stored.lastSeen.isBefore(candidate.lastSeen)) candidate else stored
                combined[candidate.storageKey] = latest.copy(
                    monitorCount = candidate.monitorCount ?: stored.monitorCount,
                    appVersion = candidate.appVersion ?: stored.appVersion,
                )
            }
        }
        // fulfillment_devices.last_seen_at is refreshed by every successfully authenticated
        // per-device fulfillment poll. It is the durable safety net when a legacy POSM build
        // cannot deliver the dedicated Redis heartbeat after the shared fleet key is disabled.
        readDatabasePresence(cutoff).forEach(::merge)
        readRedisPresence(cutoff).forEach(::merge)
        seen.values.forEach(::merge)
        return combined.values.sortedWith(compareBy<Presence>({ it.pharmacyId ?: "" }, { it.deviceId }))
    }

    /**
     * Быстрый путь для live-счётчика: читаем только ключи активного ZSET. Метаданные устройств
     * и справочник аптек для одного числа не нужны. Локальные ключи добавляются в union, чтобы
     * сохранить корректный fallback во время кратковременной недоступности Redis.
     */
    fun count(now: Instant = Instant.now()): Int {
        val cutoff = now.minusSeconds(ttlSeconds)
        evictExpiredLocal(cutoff)
        return buildSet {
            addAll(readDatabasePresence(cutoff).map(Presence::storageKey))
            addAll(readRedisPresenceKeys(cutoff))
            addAll(seen.keys)
        }.size
    }

    private fun evictExpiredLocal(cutoff: Instant) {
        seen.entries.removeIf { entry ->
            val expired = entry.value.lastSeen.isBefore(cutoff)
            if (expired) {
                log.info(
                    "POSM: касса ОТКЛЮЧИЛАСЬ (нет пульса) — deviceId={}",
                    entry.value.deviceId,
                )
            }
            expired
        }
    }

    private fun readRedisPresenceKeys(cutoff: Instant): Set<String> {
        var result = emptySet<String>()
        withRedis { redis ->
            result = redis.opsForZSet().rangeByScore(
                LAST_SEEN_KEY,
                cutoff.toEpochMilli().toDouble(),
                Double.POSITIVE_INFINITY,
            ).orEmpty()
        }
        return result
    }

    private fun readRedisPresence(cutoff: Instant): List<Presence> {
        var result = emptyList<Presence>()
        withRedis { redis ->
            val zset = redis.opsForZSet()
            val hash = redis.opsForHash<String, String>()
            val expiredBefore = cutoff.toEpochMilli().toDouble() - 1.0
            val expiredIds = zset.rangeByScore(LAST_SEEN_KEY, Double.NEGATIVE_INFINITY, expiredBefore)
                .orEmpty()
                .toList()
            if (expiredIds.isNotEmpty()) {
                zset.removeRangeByScore(LAST_SEEN_KEY, Double.NEGATIVE_INFINITY, expiredBefore)
                hash.delete(PHARMACY_KEY, *expiredIds.toTypedArray())
                hash.delete(DEVICE_KEY, *expiredIds.toTypedArray())
                hash.delete(MONITOR_COUNT_KEY, *expiredIds.toTypedArray())
                hash.delete(APP_VERSION_KEY, *expiredIds.toTypedArray())
            }

            val tuples = zset.rangeByScoreWithScores(
                LAST_SEEN_KEY,
                cutoff.toEpochMilli().toDouble(),
                Double.POSITIVE_INFINITY,
            ).orEmpty()
            val values = tuples.mapNotNull { tuple ->
                val deviceId = tuple.value ?: return@mapNotNull null
                val score = tuple.score ?: return@mapNotNull null
                deviceId to score
            }
            val keys = values.map { it.first }
            val pharmacies = if (keys.isEmpty()) emptyList() else hash.multiGet(PHARMACY_KEY, keys).orEmpty()
            val deviceIds = if (keys.isEmpty()) emptyList() else hash.multiGet(DEVICE_KEY, keys).orEmpty()
            val monitorCounts = if (keys.isEmpty()) emptyList() else hash.multiGet(MONITOR_COUNT_KEY, keys).orEmpty()
            val appVersions = if (keys.isEmpty()) emptyList() else hash.multiGet(APP_VERSION_KEY, keys).orEmpty()
            result = values.mapIndexed { index, (key, score) ->
                Presence(
                    // Records created before the composite-key fix have no DEVICE_KEY entry.
                    // They naturally expire after the normal TTL and remain readable until then.
                    deviceId = deviceIds.getOrNull(index)?.takeIf { it.isNotBlank() } ?: key,
                    pharmacyId = pharmacies.getOrNull(index)?.takeIf { it.isNotBlank() },
                    lastSeen = Instant.ofEpochMilli(score.toLong()),
                    monitorCount = monitorCounts.getOrNull(index)?.toIntOrNull(),
                    appVersion = appVersions.getOrNull(index)?.takeIf { it.isNotBlank() },
                    storageKey = key,
                )
            }
        }
        return result
    }

    private fun readDatabasePresence(cutoff: Instant): List<Presence> {
        val template = jdbc ?: return emptyList()
        return try {
            template.query(
                """
                SELECT d.device_id, d.pharmacy_id, d.last_seen_at
                FROM fulfillment_devices d
                JOIN pharmacies p ON p.id = d.pharmacy_id AND p.active = true
                WHERE d.active = true AND d.last_seen_at >= ?
                """.trimIndent(),
                { rs, _ ->
                    Presence(
                        deviceId = rs.getString("device_id"),
                        pharmacyId = rs.getString("pharmacy_id"),
                        lastSeen = rs.getTimestamp("last_seen_at").toInstant(),
                    )
                },
                Timestamp.from(cutoff),
            )
        } catch (ex: Exception) {
            warnDatabase(ex)
            emptyList()
        }
    }

    private inline fun withRedis(block: (StringRedisTemplate) -> Unit) {
        val redis = try {
            redisProvider?.getIfAvailable()
        } catch (ex: Exception) {
            warnRedis(ex)
            null
        } ?: return

        try {
            block(redis)
        } catch (ex: Exception) {
            warnRedis(ex)
        }
    }

    private fun warnRedis(ex: Exception) {
        val now = System.currentTimeMillis()
        val previous = lastRedisWarningAt.get()
        if (now - previous >= REDIS_WARNING_INTERVAL_MS && lastRedisWarningAt.compareAndSet(previous, now)) {
            log.warn("POSM presence Redis temporarily unavailable; using in-memory fallback: {}", ex.message)
        }
    }

    private fun warnDatabase(ex: Exception) {
        val now = System.currentTimeMillis()
        val previous = lastDatabaseWarningAt.get()
        if (now - previous >= REDIS_WARNING_INTERVAL_MS && lastDatabaseWarningAt.compareAndSet(previous, now)) {
            log.warn("POSM durable device activity temporarily unavailable; using heartbeat stores: {}", ex.message)
        }
    }

}
