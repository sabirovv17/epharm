package kz.epharm.pharmacies.repository

import kz.epharm.pharmacies.entity.ChainEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ChainRepository : JpaRepository<ChainEntity, String> {
    fun findAllByOrderByPointsDesc(): List<ChainEntity>
}
