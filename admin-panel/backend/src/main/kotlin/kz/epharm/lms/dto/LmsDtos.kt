package kz.epharm.lms.dto

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import kz.epharm.lms.entity.CourseEntity
import kz.epharm.lms.entity.CourseStatus
import java.time.Instant

data class CourseDto(
    val id: String,
    val title: String,
    val status: CourseStatus,
    val category: String,
    val lessons: Int,
    val durationMin: Int,
    val enrolled: Int,
    val completed: Int,
    val bonus: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(e: CourseEntity): CourseDto = CourseDto(
            id = e.id,
            title = e.title,
            status = e.status,
            category = e.category,
            lessons = e.lessons,
            durationMin = e.durationMin,
            enrolled = e.enrolled,
            completed = e.completed,
            bonus = e.bonus,
            createdAt = e.createdAt,
            updatedAt = e.updatedAt,
        )
    }
}

data class CreateCourseRequest(
    @field:NotBlank
    @field:Size(max = 255)
    val title: String,
    val status: CourseStatus? = CourseStatus.draft,
    @field:Size(max = 128)
    val category: String = "",
    @field:Min(0)
    val lessons: Int = 0,
    @field:Min(0)
    val durationMin: Int = 0,
    @field:Min(0)
    val bonus: Int = 0,
)

/**
 * Partial-update (PATCH). Метрики enrolled/completed не патчатся вручную (ETL).
 * Статус включая archived разрешён — у курсов нет dedicated /archive endpoint'а.
 */
data class UpdateCourseRequest(
    @field:Size(max = 255)
    val title: String? = null,
    val status: CourseStatus? = null,
    @field:Size(max = 128)
    val category: String? = null,
    @field:Min(0)
    val lessons: Int? = null,
    @field:Min(0)
    val durationMin: Int? = null,
    @field:Min(0)
    val bonus: Int? = null,
)
