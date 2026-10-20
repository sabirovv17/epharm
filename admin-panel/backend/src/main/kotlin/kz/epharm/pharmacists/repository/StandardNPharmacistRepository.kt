package kz.epharm.pharmacists.repository

import kz.epharm.pharmacists.entity.StandardNPharmacistEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface StandardNPharmacistRepository : JpaRepository<StandardNPharmacistEntity, String> {
    fun findFirstByIinAndSourceStatusOrderBySourceProfileIdAsc(
        iin: String,
        sourceStatus: Int,
    ): StandardNPharmacistEntity?

    fun countBySourceStatus(sourceStatus: Int): Long
}
