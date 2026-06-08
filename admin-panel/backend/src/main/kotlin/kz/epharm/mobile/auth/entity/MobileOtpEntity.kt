package kz.epharm.mobile.auth.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * Одноразовый код подтверждения телефона. Один номер = одна строка (phone — PK).
 * Сам код в БД не хранится — только SHA-256 hash. TTL/попытки обслуживает OtpService.
 */
@Entity
@Table(name = "mobile_otps")
class MobileOtpEntity(
    @Id
    @Column(name = "phone", nullable = false, length = 32)
    var phone: String = "",

    @Column(name = "code_hash", nullable = false)
    var codeHash: String = "",

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant = Instant.now(),

    @Column(name = "attempts", nullable = false)
    var attempts: Int = 0,

    @Column(name = "verified_at")
    var verifiedAt: Instant? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
) {
    fun isExpired(now: Instant): Boolean = !expiresAt.isAfter(now)
}
