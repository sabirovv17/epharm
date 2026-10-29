package kz.epharm.pharmacists.repository

import kz.epharm.pharmacists.entity.PharmacistEntity
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
interface PharmacistRepository : JpaRepository<PharmacistEntity, String> {
    fun findAllByOrderByNameAsc(): List<PharmacistEntity>
    fun findAllByStatusRawOrderByNameAsc(statusRaw: String): List<PharmacistEntity>
    fun findAllByStatusRawAndPharmacyIdOrderByNameAsc(statusRaw: String, pharmacyId: String): List<PharmacistEntity>
    fun findAllByPharmacyIdOrderByNameAsc(pharmacyId: String): List<PharmacistEntity>
    fun findAllByCityIgnoreCaseAndStatusRawOrderByNameAsc(city: String, statusRaw: String): List<PharmacistEntity>
    fun findAllByPharmacyIdAndNameIgnoreCaseAndStatusRaw(
        pharmacyId: String,
        name: String,
        statusRaw: String,
    ): List<PharmacistEntity>
    fun findByIin(iin: String): PharmacistEntity?
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PharmacistEntity p WHERE p.iin = :iin")
    fun findByIinForUpdate(@Param("iin") iin: String): PharmacistEntity?
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PharmacistEntity p WHERE p.id = :id")
    fun findByIdForUpdate(@Param("id") id: String): PharmacistEntity?
    fun findByPhone(phone: String): PharmacistEntity?
    fun countByStatusRaw(statusRaw: String): Long
    fun countByPharmacyId(pharmacyId: String): Long

    @Query("""
        SELECT p FROM PharmacistEntity p
        WHERE p.pharmacyId IN :pharmacyIds
          AND (:statusRaw IS NULL OR p.statusRaw = :statusRaw)
          AND (:pharmacyId IS NULL OR p.pharmacyId = :pharmacyId)
        ORDER BY p.name
    """)
    fun findForAdministration(
        @Param("pharmacyIds") pharmacyIds: Set<String>,
        @Param("statusRaw") statusRaw: String?,
        @Param("pharmacyId") pharmacyId: String?,
    ): List<PharmacistEntity>

    @Query("SELECT p FROM PharmacistEntity p WHERE p.id = :id AND p.pharmacyId IN :pharmacyIds")
    fun findInAdministrativeScope(
        @Param("id") id: String,
        @Param("pharmacyIds") pharmacyIds: Set<String>,
    ): Optional<PharmacistEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PharmacistEntity p WHERE p.id = :id AND p.pharmacyId IN :pharmacyIds")
    fun findInAdministrativeScopeForUpdate(
        @Param("id") id: String,
        @Param("pharmacyIds") pharmacyIds: Set<String>,
    ): Optional<PharmacistEntity>
}
