package kz.epharm.posm.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "posm_pharmacist_mappings")
class PosmPharmacistMappingEntity(
    @Id
    var id: UUID = UUID.randomUUID(),

    @Column(name = "pharmacy_id", nullable = false, length = 64)
    var pharmacyId: String = "",

    @Column(name = "external_user_id", nullable = false, length = 128)
    var externalUserId: String = "",

    @Column(name = "external_user_name", length = 255)
    var externalUserName: String? = null,

    @Column(name = "pharmacist_id", nullable = false, length = 64)
    var pharmacistId: String = "",

    @Column(nullable = false)
    var active: Boolean = true,

    @Column(name = "created_by")
    var createdBy: UUID? = null,

    @Column(name = "updated_by")
    var updatedBy: UUID? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null,
)
