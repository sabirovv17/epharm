package kz.epharm.pharmacies.repository

import kz.epharm.pharmacies.entity.PharmacyEntity
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
interface PharmacyRepository : JpaRepository<PharmacyEntity, String> {
    fun findAllByOrderByNameAsc(): List<PharmacyEntity>
    fun findAllByActiveTrueOrderByNameAsc(): List<PharmacyEntity>
    fun findAllByGroupRawOrderByNameAsc(groupRaw: String): List<PharmacyEntity>
    fun findAllByGroupRawAndChainIdOrderByNameAsc(groupRaw: String, chainId: String): List<PharmacyEntity>
    fun findAllByChainIdOrderByNameAsc(chainId: String): List<PharmacyEntity>
    fun countByActiveTrue(): Long
    fun countByChainId(chainId: String): Long

    @Query("""
        SELECT p FROM PharmacyEntity p
        WHERE p.id IN :pharmacyIds
          AND (:groupRaw IS NULL OR p.groupRaw = :groupRaw)
          AND (:chainId IS NULL OR p.chainId = :chainId)
        ORDER BY p.name
    """)
    fun findForAdministration(
        @Param("pharmacyIds") pharmacyIds: Set<String>,
        @Param("groupRaw") groupRaw: String?,
        @Param("chainId") chainId: String?,
    ): List<PharmacyEntity>

    @Query("SELECT p FROM PharmacyEntity p WHERE p.id = :id AND p.id IN :pharmacyIds")
    fun findInAdministrativeScope(
        @Param("id") id: String,
        @Param("pharmacyIds") pharmacyIds: Set<String>,
    ): Optional<PharmacyEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PharmacyEntity p WHERE p.id = :id AND p.id IN :pharmacyIds")
    fun findInAdministrativeScopeForUpdate(
        @Param("id") id: String,
        @Param("pharmacyIds") pharmacyIds: Set<String>,
    ): Optional<PharmacyEntity>
}
