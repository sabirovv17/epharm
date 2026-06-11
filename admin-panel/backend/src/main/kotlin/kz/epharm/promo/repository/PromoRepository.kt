package kz.epharm.promo.repository

import kz.epharm.promo.entity.PromoEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface PromoRepository : JpaRepository<PromoEntity, String> {
    fun findAllByOrderByUpdatedAtDesc(): List<PromoEntity>
    fun findAllByStatusRawOrderByUpdatedAtDesc(statusRaw: String): List<PromoEntity>

    /** Активные товарные акции для мобильной ленты (только с привязанным товаром Medusa). */
    fun findAllByStatusRawAndMedusaProductIdIsNotNullOrderByUpdatedAtDesc(statusRaw: String): List<PromoEntity>
}
