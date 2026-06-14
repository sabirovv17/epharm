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

    /** Кампании на тот же товар Medusa — для проверки 1:1 (товар не должен быть в двух живых акциях). */
    fun findAllByMedusaProductId(medusaProductId: String): List<PromoEntity>

    /** Все товарные акции (с привязкой к Medusa) — для ежедневного рефреша цены. */
    fun findAllByMedusaProductIdIsNotNull(): List<PromoEntity>
}
