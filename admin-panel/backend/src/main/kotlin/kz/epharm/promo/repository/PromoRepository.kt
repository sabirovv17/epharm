package kz.epharm.promo.repository

import kz.epharm.promo.entity.PromoEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface PromoRepository : JpaRepository<PromoEntity, String> {
    fun findAllByOrderByUpdatedAtDesc(): List<PromoEntity>
    fun findAllByStatusRawOrderByUpdatedAtDesc(statusRaw: String): List<PromoEntity>
}
