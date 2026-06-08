package kz.epharm.mobile.auth.service

import kz.epharm.auth.service.RefreshTokenService
import kz.epharm.mobile.auth.entity.MobileRefreshTokenEntity
import kz.epharm.mobile.auth.repository.MobileRefreshTokenRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Refresh-токены фармацевтов. Зеркало admin RefreshTokenService, но subject — строковый
 * pharmacistId. Хеширование переиспользуем (RefreshTokenService.hash — SHA-256).
 */
@Service
class MobileRefreshTokenService(
    private val repository: MobileRefreshTokenRepository,
    @Value("\${app.jwt.refresh-ttl-days:30}") private val refreshTtlDays: Long,
) {
    val refreshTtl: Duration = Duration.ofDays(refreshTtlDays)

    /** Возвращает (rawToken, expiresAt). Raw отдаём клиенту, в БД — только хеш. */
    @Transactional
    fun issue(pharmacistId: String, now: Instant = Instant.now()): IssuedRefresh {
        val raw = UUID.randomUUID().toString().replace("-", "") +
            UUID.randomUUID().toString().replace("-", "")
        val entity = MobileRefreshTokenEntity(
            id = UUID.randomUUID(),
            pharmacistId = pharmacistId,
            tokenHash = RefreshTokenService.hash(raw),
            expiresAt = now.plus(refreshTtl),
            createdAt = now,
        )
        repository.save(entity)
        return IssuedRefresh(raw = raw, expiresAt = entity.expiresAt)
    }

    /** Находит активный refresh, revoke + возвращает pharmacistId. null — если невалиден. */
    @Transactional
    fun rotate(rawToken: String, now: Instant = Instant.now()): String? {
        val entity = repository.findByTokenHash(RefreshTokenService.hash(rawToken)).orElse(null) ?: return null
        if (!entity.isActive(now)) return null
        entity.revokedAt = now
        repository.save(entity)
        return entity.pharmacistId
    }

    @Transactional
    fun revokeAllForPharmacist(pharmacistId: String) {
        repository.revokeAllForPharmacist(pharmacistId)
    }

    data class IssuedRefresh(val raw: String, val expiresAt: Instant)
}
