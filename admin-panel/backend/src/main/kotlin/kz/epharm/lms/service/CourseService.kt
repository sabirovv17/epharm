package kz.epharm.lms.service

import kz.epharm.lms.dto.CourseDto
import kz.epharm.lms.dto.CreateCourseRequest
import kz.epharm.lms.dto.UpdateCourseRequest
import kz.epharm.lms.entity.CourseEntity
import kz.epharm.lms.entity.CourseStatus
import kz.epharm.lms.repository.CourseRepository
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class CourseService(
    private val courseRepository: CourseRepository,
) {

    @Transactional(readOnly = true)
    fun list(status: CourseStatus? = null): List<CourseDto> {
        val rows = if (status != null) {
            courseRepository.findAllByStatusRawOrderByUpdatedAtDesc(status.name)
        } else {
            courseRepository.findAllByOrderByUpdatedAtDesc()
        }
        return rows.map(CourseDto::of)
    }

    @Transactional(readOnly = true)
    fun get(id: String): CourseDto = CourseDto.of(loadOrThrow(id))

    @Transactional
    fun create(req: CreateCourseRequest, createdBy: String): CourseDto {
        val entity = CourseEntity(
            id = "crs_${UUID.randomUUID().toString().substring(0, 8)}",
            title = req.title.trim(),
            category = req.category.trim(),
            lessons = req.lessons,
            durationMin = req.durationMin,
            bonus = req.bonus,
            createdBy = createdBy,
        ).also { it.status = req.status ?: CourseStatus.draft }
        return CourseDto.of(courseRepository.save(entity))
    }

    @Transactional
    fun update(id: String, req: UpdateCourseRequest): CourseDto {
        val entity = loadOrThrow(id)
        req.title?.let { entity.title = it.trim() }
        req.status?.let { entity.status = it }
        req.category?.let { entity.category = it.trim() }
        req.lessons?.let { entity.lessons = it }
        req.durationMin?.let { entity.durationMin = it }
        req.bonus?.let { entity.bonus = it }
        return CourseDto.of(courseRepository.save(entity))
    }

    @Transactional
    fun delete(id: String) {
        val entity = loadOrThrow(id)
        entity.status = CourseStatus.archived
        courseRepository.save(entity)
    }

    private fun loadOrThrow(id: String): CourseEntity =
        courseRepository.findById(id).orElseThrow {
            AppException(ErrorCode.NOT_FOUND, "Course $id not found", HttpStatus.NOT_FOUND)
        }
}
