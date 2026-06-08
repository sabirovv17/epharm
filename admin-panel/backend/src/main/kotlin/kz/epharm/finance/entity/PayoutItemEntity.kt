package kz.epharm.finance.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "payout_items")
class PayoutItemEntity(
    @Id
    @Column(name = "id", nullable = false, length = 64)
    var id: String = "",

    @Column(name = "batch_id", nullable = false, length = 64)
    var batchId: String = "",

    @Column(name = "pharmacist_id", nullable = false, length = 64)
    var pharmacistId: String = "",

    @Column(name = "pharmacist_name", nullable = false)
    var pharmacistName: String = "",

    @Column(name = "pharmacy", nullable = false)
    var pharmacy: String = "",

    @Column(name = "city", nullable = false)
    var city: String = "",

    @Column(name = "receipts", nullable = false)
    var receipts: Int = 0,

    @Column(name = "rules", nullable = false)
    var rules: Int = 0,

    @Column(name = "amount", nullable = false)
    var amount: Long = 0,

    @Column(name = "flag")
    var flag: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
) {
    @PrePersist
    fun onCreate() {
        if (createdAt == Instant.EPOCH) createdAt = Instant.now()
    }
}
