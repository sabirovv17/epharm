package kz.epharm.pharmacists.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate

enum class PharmacistTier { Silver, Gold, Platinum }
enum class PharmacistStatus { active, pending, blocked }

@Entity
@Table(name = "pharmacists")
class PharmacistEntity(
    @Id
    @Column(name = "id", nullable = false, length = 64)
    var id: String = "",

    @Column(name = "name", nullable = false)
    var name: String = "",

    @Column(name = "iin", nullable = false, length = 12)
    var iin: String = "",

    @Column(name = "phone", nullable = false, length = 32)
    var phone: String = "",

    // Nullable: саморегистрированный pending-фармацевт ещё не привязан к аптеке (V019).
    // Активный фармацевт всегда имеет аптеку — её назначает админ при активации.
    @Column(name = "pharmacy_id", nullable = true, length = 64)
    var pharmacyId: String? = null,

    @Column(name = "pharmacy_name", nullable = true)
    var pharmacyName: String? = null,

    @Column(name = "city", nullable = false)
    var city: String = "",

    @Column(name = "tier", nullable = false, length = 16)
    var tierRaw: String = PharmacistTier.Silver.name,

    @Column(name = "receipts_30d", nullable = false)
    var receipts30d: Int = 0,

    @Column(name = "rules_accepted_30d", nullable = false)
    var rulesAccepted30d: Int = 0,

    @Column(name = "earned_30d", nullable = false)
    var earned30d: Long = 0,

    @Column(name = "balance", nullable = false)
    var balance: Long = 0,

    @Column(name = "courses_done", nullable = false)
    var coursesDone: Int = 0,

    @Column(name = "courses_total", nullable = false)
    var coursesTotal: Int = 0,

    @Column(name = "status", nullable = false, length = 32)
    var statusRaw: String = PharmacistStatus.pending.name,

    @Column(name = "joined_at", nullable = false)
    var joinedAt: LocalDate = LocalDate.now(),

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    var tier: PharmacistTier
        get() = PharmacistTier.valueOf(tierRaw)
        set(value) { tierRaw = value.name }

    var status: PharmacistStatus
        get() = PharmacistStatus.valueOf(statusRaw)
        set(value) { statusRaw = value.name }

    @PrePersist
    fun onCreate() {
        val now = Instant.now()
        if (createdAt == Instant.EPOCH) createdAt = now
        updatedAt = now
    }

    @PreUpdate
    fun onUpdate() { updatedAt = Instant.now() }
}
