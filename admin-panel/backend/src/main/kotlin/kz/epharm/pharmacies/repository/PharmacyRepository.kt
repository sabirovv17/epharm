package kz.epharm.pharmacies.repository

import kz.epharm.pharmacies.entity.PharmacyEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface PharmacyRepository : JpaRepository<PharmacyEntity, String> {
    fun findAllByOrderByNameAsc(): List<PharmacyEntity>
    fun findAllByActiveTrueOrderByNameAsc(): List<PharmacyEntity>
    fun findAllByGroupRawOrderByNameAsc(groupRaw: String): List<PharmacyEntity>
    fun findAllByChainIdOrderByNameAsc(chainId: String): List<PharmacyEntity>
    fun countByActiveTrue(): Long
    fun countByChainId(chainId: String): Long
}
