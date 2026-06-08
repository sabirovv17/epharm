package kz.epharm.auth.service

import kz.epharm.auth.entity.RefreshTokenEntity
import kz.epharm.auth.repository.RefreshTokenRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/**
 * Refresh-токены: UUID raw на клиенте, SHA-256 хеш в БД.
 *
 * При выпуске возвращаем raw + entity. Raw отдаём клиенту, entity сохраняем в БД.
 * При refresh клиент присылает raw → хешируем → ищем активный токен → если найден,
 * issue новой пары + revoke старого (rotation pattern).
 */
@Service
class RefreshTokenService(
    private val refreshTokenRepository: RefreshTokenRepository,
    @Value("\${app.jwt.refresh-ttl-days:30}") private val refreshTtlDays: Long,
) {
    val refreshTtl: Duration = Duration.ofDays(refreshTtlDays)

    /**
     * Возвращает (rawToken, savedEntity). Raw отдаём клиенту, в БД лежит только хеш.
     */
    @Transactional
    fun issue(userId: UUID, now: Instant = Instant.now()): IssuedRefresh {
        val raw = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")
        val entity = RefreshTokenEntity(
            id = UUID.randomUUID(),
            userId = userId,
            tokenHash = hash(raw),
            expiresAt = now.plus(refreshTtl),
            createdAt = now,
        )
        refreshTokenRepository.save(entity)
        return IssuedRefresh(raw = raw, expiresAt = entity.expiresAt)
    }

    /**
     * Находит активный refresh по raw-токену. Если найден — revoke + возвращает userId
     * для последующего issue новой пары. Если нет — null.
     */
    @Transactional
    fun rotate(rawToken: String, now: Instant = Instant.now()): UUID? {
        val entity = refreshTokenRepository.findByTokenHash(hash(rawToken)).orElse(null) ?: return null
        if (!entity.isActive(now)) return null
        entity.revokedAt = now
        refreshTokenRepository.save(entity)
        return entity.userId
    }

    @Transactional
    fun revokeAllForUser(userId: UUID) {
        refreshTokenRepository.revokeAllForUser(userId)
    }

    @Transactional
    fun cleanupExpired() {
        refreshTokenRepository.deleteExpired()
    }

    companion object {
        private val HEX = HexFormat.of()

        /** SHA-256 hex hash. */
        fun hash(rawToken: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            return HEX.formatHex(md.digest(rawToken.toByteArray(StandardCharsets.UTF_8)))
        }
    }

    data class IssuedRefresh(val raw: String, val expiresAt: Instant)
}
