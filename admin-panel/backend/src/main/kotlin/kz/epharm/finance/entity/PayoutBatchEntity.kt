package kz.epharm.finance.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.Instant

enum class PayoutBatchStatus { pending, approved }

@Entity
@Table(name = "payout_batches")
class PayoutBatchEntity(
    @Id
    @Column(name = "id", nullable = false, length = 64)
    var id: String = "",

    @Column(name = "period", nullable = false)
    var period: String = "",

    @Column(name = "status", nullable = false, length = 32)
    var statusRaw: String = PayoutBatchStatus.pending.name,

    @Column(name = "pharmacists", nullable = false)
    var pharmacists: Int = 0,

    @Column(name = "amount", nullable = false)
    var amount: Long = 0,

    @Column(name = "items", nullable = false)
    var items: Int = 0,

    @Column(name = "reviewer_id", length = 64)
    var reviewerId: String? = null,

    @Column(name = "reviewer_name")
    var reviewerName: String? = null,

    @Column(name = "approved_at")
    var approvedAt: Instant? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    var status: PayoutBatchStatus
        get() = PayoutBatchStatus.valueOf(statusRaw)
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
