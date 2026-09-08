package kz.epharm.posm.repository

import kz.epharm.posm.entity.PosmPharmacistMappingEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface PosmPharmacistMappingRepository : JpaRepository<PosmPharmacistMappingEntity, UUID> {
    fun findByPharmacyIdAndExternalUserIdAndActiveTrue(
        pharmacyId: String,
        externalUserId: String,
    ): PosmPharmacistMappingEntity?

    fun findByPharmacyIdAndExternalUserId(
        pharmacyId: String,
        externalUserId: String,
    ): PosmPharmacistMappingEntity?

    fun findAllByActiveTrueOrderByPharmacyIdAscExternalUserIdAsc(): List<PosmPharmacistMappingEntity>
    fun findAllByOrderByPharmacyIdAscExternalUserIdAsc(): List<PosmPharmacistMappingEntity>
    fun findAllByPharmacyIdAndActiveTrueOrderByExternalUserIdAsc(
        pharmacyId: String,
    ): List<PosmPharmacistMappingEntity>

    fun findAllByPharmacyIdOrderByExternalUserIdAsc(pharmacyId: String): List<PosmPharmacistMappingEntity>
}
