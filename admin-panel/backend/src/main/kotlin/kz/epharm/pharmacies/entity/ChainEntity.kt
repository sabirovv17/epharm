package kz.epharm.pharmacies.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.Instant

enum class PharmacyGroup { pilot, control, rolled }

@Entity
@Table(name = "chains")
class ChainEntity(
    @Id
    @Column(name = "id", nullable = false, length = 64)
    var id: String = "",

    @Column(name = "name", nullable = false)
    var name: String = "",

    @Column(name = "color", nullable = false, length = 16)
    var color: String = "",

    @Column(name = "points", nullable = false)
    var points: Int = 0,

    // "group" — reserved SQL keyword, маркируем кавычками в schema.
    @Column(name = "\"group\"", nullable = false, length = 32)
    var groupRaw: String = PharmacyGroup.pilot.name,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    var group: PharmacyGroup
        get() = PharmacyGroup.valueOf(groupRaw)
        set(value) { groupRaw = value.name }

    @PrePersist
    fun onCreate() {
        val now = Instant.now()
        if (createdAt == Instant.EPOCH) createdAt = now
        updatedAt = now
    }

    @PreUpdate
    fun onUpdate() { updatedAt = Instant.now() }
}
