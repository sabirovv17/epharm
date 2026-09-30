package kz.epharm.lms.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import kz.epharm.lms.entity.CourseEntity
import kz.epharm.lms.entity.CourseLessonAttachmentEntity
import kz.epharm.lms.entity.CourseLessonEntity
import kz.epharm.lms.entity.CourseLessonKind
import kz.epharm.lms.entity.CourseQuizQuestion
import kz.epharm.lms.entity.CourseStatus
import java.time.Instant

data class CourseLessonDto(
    val id: String,
    val title: String,
    val description: String,
    val content: String,
    val kind: CourseLessonKind,
    val videoUrl: String?,
    val externalUrl: String?,
    val required: Boolean,
    val minimumWatchPct: Int?,
    val quizQuestions: List<CourseQuizQuestionDto>,
    val quizPassingScore: Int,
    val attachments: List<CourseLessonAttachmentDto>,
    val durationMin: Int,
    val order: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val progressPct: Int? = null,
    val lastPositionSeconds: Int? = null,
    val startedAt: Instant? = null,
    val completedAt: Instant? = null,
    val quizScore: Int? = null,
    val quizAttempts: Int = 0,
) {
    companion object {
        fun of(
            entity: CourseLessonEntity,
            attachments: List<CourseLessonAttachmentEntity> = emptyList(),
            includeQuizAnswers: Boolean = true,
        ): CourseLessonDto = CourseLessonDto(
            id = entity.id,
            title = entity.title,
            description = entity.description,
            content = entity.content,
            kind = entity.kind,
            videoUrl = entity.videoUrl,
            externalUrl = entity.externalUrl,
            required = entity.required,
            minimumWatchPct = entity.minimumWatchPct,
            quizQuestions = entity.quizQuestions.map {
                CourseQuizQuestionDto.of(it, includeAnswer = includeQuizAnswers)
            },
            quizPassingScore = entity.quizPassingScore,
            attachments = attachments.map(CourseLessonAttachmentDto::of),
            durationMin = entity.durationMin,
            order = entity.order,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
        )
    }
}

data class CourseQuizQuestionDto(
    val id: String,
    val prompt: String,
    val options: List<String>,
    val correctOption: Int?,
    val explanation: String,
) {
    companion object {
        fun of(question: CourseQuizQuestion, includeAnswer: Boolean): CourseQuizQuestionDto =
            CourseQuizQuestionDto(
                id = question.id,
                prompt = question.prompt,
                options = question.options,
                correctOption = question.correctOption.takeIf { includeAnswer },
                explanation = question.explanation.takeIf { includeAnswer }.orEmpty(),
            )
    }
}

data class CourseLessonAttachmentDto(
    val id: String,
    val title: String,
    val fileName: String,
    val contentType: String,
    val mediaUrl: String,
    val sizeBytes: Long,
    val createdAt: Instant,
) {
    val kind: String
        get() = when {
            contentType.startsWith("image/") -> "image"
            contentType.startsWith("video/") -> "video"
            contentType.startsWith("audio/") -> "audio"
            else -> "document"
        }

    companion object {
        fun of(entity: CourseLessonAttachmentEntity): CourseLessonAttachmentDto =
            CourseLessonAttachmentDto(
                id = entity.id,
                title = entity.title,
                fileName = entity.fileName,
                contentType = entity.contentType,
                mediaUrl = entity.mediaUrl,
                sizeBytes = entity.sizeBytes,
                createdAt = entity.createdAt,
            )
    }
}

/** Compact course payload embedded into a pharmacist's online-course stage. */
data class CourseContentDto(
    val id: String,
    val title: String,
    val description: String,
    val durationMin: Int,
    val lessons: List<CourseLessonDto>,
) {
    companion object {
        fun of(
            entity: CourseEntity,
            lessons: List<CourseLessonEntity>,
            attachmentsByLesson: Map<String, List<CourseLessonAttachmentEntity>> = emptyMap(),
        ): CourseContentDto = CourseContentDto(
            id = entity.id,
            title = entity.title,
            description = entity.description,
            durationMin = if (lessons.isEmpty()) entity.durationMin else lessons.sumOf { it.durationMin },
            lessons = lessons.map {
                CourseLessonDto.of(
                    it,
                    attachmentsByLesson[it.id].orEmpty(),
                    includeQuizAnswers = false,
                )
            },
        )
    }
}

data class CourseDto(
    val id: String,
    val title: String,
    val description: String,
    val status: CourseStatus,
    val category: String,
    val lessons: Int,
    val durationMin: Int,
    val enrolled: Int,
    val completed: Int,
    val bonus: Int,
    val lessonItems: List<CourseLessonDto>,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(
            e: CourseEntity,
            lessons: List<CourseLessonEntity> = emptyList(),
            attachmentsByLesson: Map<String, List<CourseLessonAttachmentEntity>> = emptyMap(),
        ): CourseDto = CourseDto(
            id = e.id,
            title = e.title,
            description = e.description,
            status = e.status,
            category = e.category,
            lessons = e.lessons,
            durationMin = e.durationMin,
            enrolled = e.enrolled,
            completed = e.completed,
            bonus = e.bonus,
            lessonItems = lessons.map { CourseLessonDto.of(it, attachmentsByLesson[it.id].orEmpty()) },
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
    @field:Size(max = 10_000)
    val description: String = "",
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
    @field:Size(max = 10_000)
    val description: String? = null,
    @field:Min(0)
    val lessons: Int? = null,
    @field:Min(0)
    val durationMin: Int? = null,
    @field:Min(0)
    val bonus: Int? = null,
)

data class CreateCourseLessonRequest(
    @field:NotBlank
    @field:Size(max = 255)
    val title: String,
    @field:Size(max = 1000)
    val description: String = "",
    @field:Size(max = 10_000)
    val content: String = "",
    val kind: CourseLessonKind = CourseLessonKind.text,
    @field:Size(max = 2000)
    val externalUrl: String? = null,
    val required: Boolean = true,
    @field:Min(0) @field:Max(100)
    val minimumWatchPct: Int? = null,
    @field:Valid
    @field:Size(max = 50)
    val quizQuestions: List<CourseQuizQuestionRequest> = emptyList(),
    @field:Min(1) @field:Max(100)
    val quizPassingScore: Int = 80,
    @field:Min(0)
    val durationMin: Int = 0,
)

data class UpdateCourseLessonRequest(
    @field:Size(max = 255)
    val title: String? = null,
    @field:Size(max = 1000)
    val description: String? = null,
    @field:Size(max = 10_000)
    val content: String? = null,
    val kind: CourseLessonKind? = null,
    @field:Size(max = 2000)
    val externalUrl: String? = null,
    val clearExternalUrl: Boolean = false,
    val required: Boolean? = null,
    @field:Min(0) @field:Max(100)
    val minimumWatchPct: Int? = null,
    val clearMinimumWatchPct: Boolean = false,
    @field:Valid
    @field:Size(max = 50)
    val quizQuestions: List<CourseQuizQuestionRequest>? = null,
    @field:Min(1) @field:Max(100)
    val quizPassingScore: Int? = null,
    val clearQuiz: Boolean = false,
    @field:Min(0)
    val durationMin: Int? = null,
    val clearVideo: Boolean = false,
)

data class CourseQuizQuestionRequest(
    @field:Size(max = 64)
    val id: String? = null,
    @field:NotBlank
    @field:Size(max = 1000)
    val prompt: String,
    @field:Size(min = 2, max = 8)
    val options: List<String>,
    @field:Min(0)
    val correctOption: Int,
    @field:Size(max = 2000)
    val explanation: String = "",
)

data class ReorderCourseLessonsRequest(
    @field:NotEmpty
    val lessonIds: List<@NotBlank String>,
)
