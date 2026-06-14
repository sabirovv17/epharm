package kz.epharm.posm.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Присутствие касс (T4): сколько POSM-устройств сейчас «онлайн».
 *
 * Подход — хелсчек раз в минуту: касса шлёт POST /api/posm/heartbeat каждые ~60с,
 * мы помним последний пульс по deviceId. «Онлайн» = пульс не старше TTL
 * (`app.posm.heartbeat-ttl-seconds`, по умолчанию 90с ≈ 1.5 интервала).
 *
 * Хранилище — in-memory ConcurrentHashMap: данные эфемерны (восстанавливаются за минуту),
 * прод — один инстанс бэка. Это проще и надёжнее WebSocket для подсчёта подключений;
 * позже устройствам присвоим стабильные id (deviceId уже есть в контракте).
 */
@Service
class DevicePresenceService(
    @Value("\${app.posm.heartbeat-ttl-seconds:90}") private val ttlSeconds: Long,
) {
    private val seen = ConcurrentHashMap<String, Presence>()

    data class Presence(
        val deviceId: String,
        val pharmacyId: String?,
        val lastSeen: Instant,
    )

    /** Зафиксировать пульс устройства. */
    fun heartbeat(deviceId: String, pharmacyId: String?, now: Instant = Instant.now()) {
        seen[deviceId] = Presence(deviceId, pharmacyId?.takeIf { it.isNotBlank() }, now)
    }

    /** Живые устройства (пульс не старше TTL). Заодно чистим протухшие записи. */
    fun connected(now: Instant = Instant.now()): List<Presence> {
        val cutoff = now.minusSeconds(ttlSeconds)
        seen.entries.removeIf { it.value.lastSeen.isBefore(cutoff) }
        return seen.values.sortedBy { it.deviceId }
    }

    fun count(now: Instant = Instant.now()): Int = connected(now).size
}
