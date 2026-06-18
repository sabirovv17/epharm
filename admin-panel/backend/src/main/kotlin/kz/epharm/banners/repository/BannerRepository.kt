package kz.epharm.banners.repository

import kz.epharm.banners.entity.BannerEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface BannerRepository : JpaRepository<BannerEntity, String> {
    // Админка: весь список — по позиции, затем свежие сверху среди равных позиций.
    fun findAllByOrderByPositionAscCreatedAtDesc(): List<BannerEntity>

    // Мобильная лента: только активные, в порядке позиции.
    fun findAllByStatusRawOrderByPositionAsc(statusRaw: String): List<BannerEntity>
}
