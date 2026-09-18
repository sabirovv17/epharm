package kz.epharm.medusa

import org.springframework.beans.factory.annotation.Value
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Маленький in-memory TTL-кэш для прокси каталога. Medusa-витрина меняется редко
 * (PIM обновляет товары пачками), а внешний сервер на голом HTTP без гарантий аптайма —
 * кэш снижает и нагрузку, и latency, и риск 502 при кратком сбое.
 *
 * Кешируем УЖЕ смаппленные мобильные DTO (а не сырьё Medusa). Ошибки и пустые
 * аварийные ответы не записываются вместо последнего успешного значения.
 *
 * После TTL сначала пробуем обновиться из Medusa. При временной ошибке последний
 * успешный снимок остаётся доступен ещё `staleIfErrorSeconds`: создание акции и
 * каталог не падают из-за короткого сетевого сбоя, но при исправной Medusa данные
 * автоматически освежаются каждые `ttlSeconds`.
 *
 * `ttlSeconds <= 0` полностью отключает кэш (удобно для тестов и dev).
 * Один backend-контейнер (текущая прод-модель) → локального кэша достаточно; при
 * масштабировании на несколько инстансов вынести в Redis (он уже в стеке).
 */
@Component
class MedusaCatalogCache(
    @Value("\${app.medusa.cache-ttl-seconds:300}") private val ttlSeconds: Long,
    @Value("\${app.medusa.cache-stale-if-error-seconds:3600}") private val staleIfErrorSeconds: Long = 3600,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private data class Entry(val freshUntil: Instant, val staleUntil: Instant, val value: Any?)

    private val store = ConcurrentHashMap<String, Entry>()

    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String, loader: () -> T): T {
        if (ttlSeconds <= 0) return loader()
        val now = Instant.now()
        val previous = store[key]
        if (previous?.freshUntil?.isAfter(now) == true) return previous.value as T

        return try {
            val fresh = loader()
            val freshUntil = Instant.now().plusSeconds(ttlSeconds)
            store[key] = Entry(
                freshUntil = freshUntil,
                staleUntil = freshUntil.plusSeconds(staleIfErrorSeconds.coerceAtLeast(0)),
                value = fresh,
            )
            fresh
        } catch (e: Exception) {
            if (previous?.staleUntil?.isAfter(now) == true) {
                log.warn("Medusa refresh failed for cache key {}; serving last known good value: {}", key, e.message)
                previous.value as T
            } else {
                if (previous != null) store.remove(key, previous)
                throw e
            }
        }
    }

    fun clear() = store.clear()
}
