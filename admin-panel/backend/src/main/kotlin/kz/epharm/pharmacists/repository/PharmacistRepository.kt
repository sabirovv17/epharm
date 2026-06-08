package kz.epharm.pharmacists.repository

import kz.epharm.pharmacists.entity.PharmacistEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface PharmacistRepository : JpaRepository<PharmacistEntity, String> {
    fun findAllByOrderByNameAsc(): List<PharmacistEntity>
    fun findAllByStatusRawOrderByNameAsc(statusRaw: String): List<PharmacistEntity>
    fun findAllByPharmacyIdOrderByNameAsc(pharmacyId: String): List<PharmacistEntity>
    fun findByIin(iin: String): PharmacistEntity?
    fun findByPhone(phone: String): PharmacistEntity?
    fun countByStatusRaw(statusRaw: String): Long
    fun countByPharmacyId(pharmacyId: String): Long
}
