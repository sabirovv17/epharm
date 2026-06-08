package kz.epharm.auth.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.domain.AdminUserStatus
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "admin_users")
class AdminUserEntity(
    @Id
    @Column(name = "id", nullable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "email", nullable = false)
    var email: String = "",

    @Column(name = "password_hash", nullable = false)
    var passwordHash: String = "",

    @Column(name = "name", nullable = false)
    var name: String = "",

    @Column(name = "role", nullable = false, length = 64)
    var roleRaw: String = AdminRole.BRAND_MANAGER.name,

    @Column(name = "company", nullable = false)
    var company: String = "",

    @Column(name = "status", nullable = false, length = 32)
    var statusRaw: String = AdminUserStatus.ACTIVE.toDbValue(),

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    var role: AdminRole
        get() = AdminRole.valueOf(roleRaw)
        set(value) { roleRaw = value.name }

    var status: AdminUserStatus
        get() = AdminUserStatus.fromDbValue(statusRaw)
        set(value) { statusRaw = value.toDbValue() }

    @PrePersist
    fun onCreate() {
        val now = Instant.now()
        if (createdAt == Instant.EPOCH) createdAt = now
        updatedAt = now
    }

    @PreUpdate
    fun onUpdate() {
        updatedAt = Instant.now()
    }
}
