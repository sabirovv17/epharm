package kz.epharm.lms.repository

import kz.epharm.lms.entity.CourseEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface CourseRepository : JpaRepository<CourseEntity, String> {
    fun findAllByOrderByUpdatedAtDesc(): List<CourseEntity>
    fun findAllByStatusRawOrderByUpdatedAtDesc(statusRaw: String): List<CourseEntity>
}
