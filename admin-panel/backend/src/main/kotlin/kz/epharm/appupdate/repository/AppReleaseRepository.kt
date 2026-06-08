package kz.epharm.appupdate.repository

import kz.epharm.appupdate.entity.AppReleaseEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface AppReleaseRepository : JpaRepository<AppReleaseEntity, String> {
    fun findFirstByPlatformAndIsCurrentTrue(platform: String): AppReleaseEntity?
    fun findAllByPlatformAndIsCurrentTrue(platform: String): List<AppReleaseEntity>
    fun findAllByOrderByCreatedAtDesc(): List<AppReleaseEntity>
}
