package kz.epharm.pharmacists.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "standardn_pharmacist_directory")
class StandardNPharmacistEntity(
    @Id
    @Column(name = "directory_key", nullable = false, length = 128)
    var directoryKey: String = "",

    @Column(name = "source_profile_id", nullable = false)
    var sourceProfileId: Long = 0,

    @Column(name = "source_user_id", nullable = false)
    var sourceUserId: Long = 0,

    @Column(name = "iin", nullable = false, length = 12)
    var iin: String = "",

    @Column(name = "full_name", nullable = false)
    var fullName: String = "",

    @Column(name = "source_status", nullable = false)
    var sourceStatus: Int = -1,

    @Column(name = "source_login")
    var sourceLogin: String? = null,

    @Column(name = "source_post")
    var sourcePost: String? = null,

    @Column(name = "source_department")
    var sourceDepartment: String? = null,

    @Column(name = "profile_name")
    var profileName: String? = null,

    @Column(name = "profile_city")
    var profileCity: String? = null,

    @Column(name = "profile_address")
    var profileAddress: String? = null,

    @Column(name = "imported_at", nullable = false)
    var importedAt: Instant = Instant.now(),
)
