package kz.epharm.cdp.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.Table
import java.time.Instant

/**
 * Профиль клиента программы лояльности (CDP, ТЗ §5.6). Создаётся на кассе по телефону.
 * phone — уникальный ключ поиска (нормализованный: только цифры и ведущий +).
 */
@Entity
@Table(name = "cdp_profiles")
class CdpProfileEntity(
    @Id
    @Column(name = "id", nullable = false, length = 64)
    var id: String = "",

    @Column(name = "phone", nullable = false, length = 32)
    var phone: String = "",

    @Column(name = "name")
    var name: String? = null,

    @Column(name = "tier", nullable = false, length = 32)
    var tier: String = "Bronze",

    @Column(name = "registered_by_pharmacist", length = 64)
    var registeredByPharmacist: String? = null,

    @Column(name = "registered_at_pharmacy", length = 64)
    var registeredAtPharmacy: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
) {
    @PrePersist
    fun onCreate() {
        if (createdAt == Instant.EPOCH) createdAt = Instant.now()
    }
}
