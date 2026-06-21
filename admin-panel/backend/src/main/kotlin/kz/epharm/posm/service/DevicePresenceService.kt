package kz.epharm.posm.service

import org.slf4j.LoggerFactory
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
    private val log = LoggerFactory.getLogger(DevicePresenceService::class.java)

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
        seen[deviceId] = Presence(deviceId, pharmacyId?.takeIf { it.isNotBlank() }, now)
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
        return seen.values.sortedBy { it.deviceId }
    }

    fun count(now: Instant = Instant.now()): Int = connected(now).size
}
