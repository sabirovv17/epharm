package kz.epharm.promo.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.Instant

/**
 * Статус промо-кампании. Жизненный цикл:
 *   draft → active ⇄ paused → archived
 * Archived нельзя редактировать через PATCH (см. PromoService.update —
 * аналог RuleService Bug G fix).
 */
enum class PromoStatus { active, draft, paused, archived }

@Entity
@Table(name = "promos")
class PromoEntity(
    @Id
    @Column(name = "id", nullable = false, length = 64)
    var id: String = "",

    @Column(name = "title", nullable = false)
    var title: String = "",

    @Column(name = "status", nullable = false, length = 32)
    var statusRaw: String = PromoStatus.draft.name,

    @Column(name = "brand", nullable = false)
    var brand: String = "",

    @Column(name = "period", nullable = false)
    var period: String = "",

    @Column(name = "pharmacies", nullable = false)
    var pharmacies: Int = 0,

    @Column(name = "budget", nullable = false)
    var budget: Long = 0,

    @Column(name = "spent", nullable = false)
    var spent: Long = 0,

    @Column(name = "kpi", nullable = false)
    var kpi: String = "",

    @Column(name = "cover", nullable = false, length = 16)
    var cover: String = "",

    @Column(name = "created_by", nullable = false, length = 64)
    var createdBy: String = "",

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    var status: PromoStatus
        get() = PromoStatus.valueOf(statusRaw)
        set(value) { statusRaw = value.name }

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
