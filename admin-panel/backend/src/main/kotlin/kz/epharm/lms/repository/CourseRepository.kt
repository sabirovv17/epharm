package kz.epharm.lms.repository

import kz.epharm.lms.entity.CourseEntity
import kz.epharm.lms.entity.CourseLessonEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface CourseRepository : JpaRepository<CourseEntity, String> {
    fun findAllByOrderByUpdatedAtDesc(): List<CourseEntity>
    fun findAllByStatusRawOrderByUpdatedAtDesc(statusRaw: String): List<CourseEntity>
}

@Repository
interface CourseLessonRepository : JpaRepository<CourseLessonEntity, String> {
    fun findAllByCourseIdOrderByOrderAscCreatedAtAsc(courseId: String): List<CourseLessonEntity>
    fun findAllByCourseIdInOrderByCourseIdAscOrderAscCreatedAtAsc(courseIds: Collection<String>): List<CourseLessonEntity>
}
