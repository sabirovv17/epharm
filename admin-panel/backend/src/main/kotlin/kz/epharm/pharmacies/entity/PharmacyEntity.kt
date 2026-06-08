package kz.epharm.pharmacies.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant

@Entity
@Table(name = "pharmacies")
class PharmacyEntity(
    @Id
    @Column(name = "id", nullable = false, length = 64)
    var id: String = "",

    @Column(name = "name", nullable = false)
    var name: String = "",

    @Column(name = "chain_id", nullable = false, length = 64)
    var chainId: String = "",

    @Column(name = "chain_name", nullable = false)
    var chainName: String = "",

    @Column(name = "city", nullable = false)
    var city: String = "",

    @Column(name = "district", nullable = false)
    var district: String = "",

    @Column(name = "addr", nullable = false)
    var addr: String = "",

    @Column(name = "\"group\"", nullable = false, length = 32)
    var groupRaw: String = PharmacyGroup.pilot.name,

    @Column(name = "pharmacists", nullable = false)
    var pharmacists: Int = 0,

    @Column(name = "receipts_30d", nullable = false)
    var receipts30d: Int = 0,

    @Column(name = "gmv_30d", nullable = false)
    var gmv30d: Long = 0,

    @Column(name = "lift_pct", nullable = false, precision = 5, scale = 2)
    var liftPct: BigDecimal = BigDecimal.ZERO,

    @Column(name = "rules_accepted", nullable = false)
    var rulesAccepted: Int = 0,

    @Column(name = "active", nullable = false)
    var active: Boolean = true,

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
