package kz.epharm.medusa

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Маленький in-memory TTL-кэш для прокси каталога. Medusa-витрина меняется редко
 * (PIM обновляет товары пачками), а внешний сервер на голом HTTP без гарантий аптайма —
 * кэш снижает и нагрузку, и latency, и риск 502 при кратком сбое.
 *
 * Кешируем УЖЕ смаппленные мобильные DTO (а не сырьё Medusa). Ошибки (AppException из
 * клиента) пробрасываются и НЕ кешируются — следующий запрос попробует снова.
 *
 * `ttlSeconds <= 0` полностью отключает кэш (удобно для тестов и dev).
 * Один backend-контейнер (текущая прод-модель) → локального кэша достаточно; при
 * масштабировании на несколько инстансов вынести в Redis (он уже в стеке).
 */
@Component
class MedusaCatalogCache(
    @Value("\${app.medusa.cache-ttl-seconds:300}") private val ttlSeconds: Long,
) {
    private data class Entry(val expiresAt: Instant, val value: Any?)

    private val store = ConcurrentHashMap<String, Entry>()

    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String, loader: () -> T): T {
        if (ttlSeconds <= 0) return loader()
        val now = Instant.now()
        store[key]?.let { if (it.expiresAt.isAfter(now)) return it.value as T }
        val fresh = loader()
        store[key] = Entry(now.plusSeconds(ttlSeconds), fresh)
        return fresh
    }

    fun clear() = store.clear()
}
