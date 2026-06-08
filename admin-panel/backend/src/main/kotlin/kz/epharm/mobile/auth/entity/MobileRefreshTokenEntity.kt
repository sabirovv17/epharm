package kz.epharm.mobile.auth.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Refresh-токен фармацевта. Raw отдаётся клиенту, в БД — SHA-256 hash.
 * Отдельная таблица от admin refresh_tokens (та FK на admin_users UUID).
 */
@Entity
@Table(name = "mobile_refresh_tokens")
class MobileRefreshTokenEntity(
    @Id
    @Column(name = "id", nullable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "pharmacist_id", nullable = false, length = 64)
    var pharmacistId: String = "",

    @Column(name = "token_hash", nullable = false)
    var tokenHash: String = "",

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant = Instant.now(),

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
) {
    fun isActive(now: Instant = Instant.now()): Boolean =
        revokedAt == null && expiresAt.isAfter(now)
}
