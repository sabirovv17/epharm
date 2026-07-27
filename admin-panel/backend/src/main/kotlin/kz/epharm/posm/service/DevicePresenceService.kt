package kz.epharm.posm.service

import org.springframework.beans.factory.ObjectProvider
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Присутствие касс (T4): сколько POSM-устройств сейчас «онлайн».
 *
 * Подход — хелсчек раз в минуту: касса шлёт POST /api/posm/heartbeat каждые ~60с,
 * мы помним последний пульс по deviceId. «Онлайн» = пульс не старше TTL
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
) {
    private val seen = ConcurrentHashMap<String, Presence>()
    private val log = LoggerFactory.getLogger(DevicePresenceService::class.java)
    private val lastRedisWarningAt = AtomicLong(0)

    companion object {
        private const val LAST_SEEN_KEY = "epharm:posm:presence:last-seen"
        private const val PHARMACY_KEY = "epharm:posm:presence:pharmacy"
        private const val REDIS_WARNING_INTERVAL_MS = 60_000L
    }

    data class Presence(
        val deviceId: String,
        val pharmacyId: String?,
        val lastSeen: Instant,
    )

    /** Зафиксировать пульс устройства. Логируем INFO только на ПОДКЛЮЧЕНИЕ (новый/после оффлайна),
     *  чтобы не спамить каждые 60с, но было видно «касса подключилась» на бэкенде. */
    fun heartbeat(deviceId: String, pharmacyId: String?, now: Instant = Instant.now()) {
        val prev = seen[deviceId]
        val wasOffline = prev == null || prev.lastSeen.isBefore(now.minusSeconds(ttlSeconds))
        val normalizedPharmacy = pharmacyId?.takeIf { it.isNotBlank() }
        seen[deviceId] = Presence(deviceId, normalizedPharmacy, now)

        withRedis { redis ->
            redis.opsForZSet().add(LAST_SEEN_KEY, deviceId, now.toEpochMilli().toDouble())
            redis.opsForHash<String, String>().put(PHARMACY_KEY, deviceId, normalizedPharmacy ?: "")
        }

        if (wasOffline) {
            log.info("POSM: касса ПОДКЛЮЧИЛАСЬ — deviceId={}, аптека={}", deviceId, pharmacyId ?: "—")
        }
    }

    /** Живые устройства (пульс не старше TTL). Заодно чистим протухшие записи (лог об отключении). */
    fun connected(now: Instant = Instant.now()): List<Presence> {
        val cutoff = now.minusSeconds(ttlSeconds)
        seen.entries.removeIf { e ->
            val expired = e.value.lastSeen.isBefore(cutoff)
            if (expired) log.info("POSM: касса ОТКЛЮЧИЛАСЬ (нет пульса) — deviceId={}", e.value.deviceId)
            expired
        }

        val combined = HashMap<String, Presence>()
        readRedisPresence(cutoff).forEach { combined[it.deviceId] = it }
        seen.values.forEach { local ->
            val stored = combined[local.deviceId]
            if (stored == null || stored.lastSeen.isBefore(local.lastSeen)) combined[local.deviceId] = local
        }
        return combined.values.sortedBy { it.deviceId }
    }

    fun count(now: Instant = Instant.now()): Int = connected(now).size

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
            val ids = values.map { it.first }
            val pharmacies = if (ids.isEmpty()) emptyList() else hash.multiGet(PHARMACY_KEY, ids).orEmpty()
            result = values.mapIndexed { index, (deviceId, score) ->
                Presence(
                    deviceId = deviceId,
                    pharmacyId = pharmacies.getOrNull(index)?.takeIf { it.isNotBlank() },
                    lastSeen = Instant.ofEpochMilli(score.toLong()),
                )
            }
        }
        return result
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
}
