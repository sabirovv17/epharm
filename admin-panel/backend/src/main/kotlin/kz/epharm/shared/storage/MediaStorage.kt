package kz.epharm.shared.storage

import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Абстракция хранилища медиа (видео/картинки слайдов indoor-экранов).
 * Реализации:
 *  - [S3MediaStorage] (profile != test) — реальный MinIO/S3.
 *  - [InMemoryMediaStorage] (profile test) — без сети, чтобы @SpringBootTest
 *    не зависел от поднятого MinIO.
 */
interface MediaStorage {
    /** Загружает байты, возвращает публичный URL для проигрывания в <video>/<img>. */
    fun upload(bytes: ByteArray, contentType: String, originalName: String): String

    /** Удаляет объект по ранее возвращённому URL (best-effort). */
    fun delete(url: String)
}

/**
 * Тестовая заглушка: хранит байты в памяти, отдаёт детерминированный fake-URL.
 * Сеть не трогается — integration-тесты screens работают без MinIO.
 */
@Component
@Profile("test")
class InMemoryMediaStorage : MediaStorage {
    private val store = ConcurrentHashMap<String, ByteArray>()

    override fun upload(bytes: ByteArray, contentType: String, originalName: String): String {
        val key = "screens/${UUID.randomUUID()}-$originalName"
        store[key] = bytes
        return "http://test-storage/$key"
    }

    override fun delete(url: String) {
        store.keys.firstOrNull { url.endsWith(it) }?.let { store.remove(it) }
    }
}
