package kz.epharm.lms.controller

import jakarta.validation.Valid
import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.lms.dto.CourseDto
import kz.epharm.lms.dto.CourseLiveModerationRequest
import kz.epharm.lms.dto.CourseLiveReactionRequest
import kz.epharm.lms.dto.CourseLiveRoomDto
import kz.epharm.lms.dto.CourseLiveStatusRequest
import kz.epharm.lms.dto.CourseLiveVoteRequest
import kz.epharm.lms.dto.CreateCourseLiveMessageRequest
import kz.epharm.lms.dto.CreateCourseLessonRequest
import kz.epharm.lms.dto.CreateCourseRequest
import kz.epharm.lms.dto.ReorderCourseLessonsRequest
import kz.epharm.lms.dto.UpdateCourseLessonRequest
import kz.epharm.lms.dto.UpdateCourseRequest
import kz.epharm.lms.entity.CourseStatus
import kz.epharm.lms.entity.CourseLiveAuthorType
import kz.epharm.lms.service.CourseExcelImportService
import kz.epharm.lms.service.CourseService
import kz.epharm.lms.service.CourseLiveService
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

// LMS — каталог обучающих курсов (ТЗ §3.2 / §3.4). Этап 3.6: list + get + create.
@RestController
@RequestMapping("/api/admin/lms/courses")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','HQ_HEAD','TRAINING_MANAGER','REGIONAL_MANAGER','TRAINER')")
class LmsController(
    private val courseService: CourseService,
    private val courseExcelImportService: CourseExcelImportService,
    private val courseLiveService: CourseLiveService,
) {

    @GetMapping
    fun list(@RequestParam(required = false) status: CourseStatus?): List<CourseDto> =
        courseService.list(status = status)

    @GetMapping("/{id}")
    fun get(@PathVariable id: String): CourseDto = courseService.get(id)

    @PostMapping
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','TRAINING_MANAGER')")
    fun create(
        @Valid @RequestBody req: CreateCourseRequest,
        @AuthenticationPrincipal principal: AdminPrincipal?,
    ): CourseDto = courseService.create(req, createdBy = requireUserId(principal))

    @PostMapping("/import/excel", consumes = ["multipart/form-data"])
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','TRAINING_MANAGER')")
    fun importExcel(
        @RequestParam("file") file: MultipartFile,
        @AuthenticationPrincipal principal: AdminPrincipal?,
    ): CourseDto = courseExcelImportService.importCourse(file, createdBy = requireUserId(principal))

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','TRAINING_MANAGER')")
    fun update(@PathVariable id: String, @Valid @RequestBody req: UpdateCourseRequest): CourseDto =
        courseService.update(id, req)

    @PostMapping("/{id}/lessons")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','TRAINING_MANAGER')")
    fun createLesson(
        @PathVariable id: String,
        @Valid @RequestBody req: CreateCourseLessonRequest,
    ): CourseDto = courseService.createLesson(id, req)

    @PatchMapping("/{id}/lessons/{lessonId}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','TRAINING_MANAGER')")
    fun updateLesson(
        @PathVariable id: String,
        @PathVariable lessonId: String,
        @Valid @RequestBody req: UpdateCourseLessonRequest,
    ): CourseDto = courseService.updateLesson(id, lessonId, req)

    @DeleteMapping("/{id}/lessons/{lessonId}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','TRAINING_MANAGER')")
    fun deleteLesson(@PathVariable id: String, @PathVariable lessonId: String): CourseDto =
        courseService.deleteLesson(id, lessonId)

    @PutMapping("/{id}/lessons/reorder")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','TRAINING_MANAGER')")
    fun reorderLessons(
        @PathVariable id: String,
        @Valid @RequestBody req: ReorderCourseLessonsRequest,
    ): CourseDto = courseService.reorderLessons(id, req)

    @PostMapping("/{id}/lessons/{lessonId}/video", consumes = ["multipart/form-data"])
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','TRAINING_MANAGER')")
    fun uploadLessonVideo(
        @PathVariable id: String,
        @PathVariable lessonId: String,
        @RequestParam("file") file: MultipartFile,
    ): CourseDto = courseService.uploadLessonVideo(id, lessonId, file)

    @PostMapping("/{id}/lessons/{lessonId}/attachments", consumes = ["multipart/form-data"])
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','TRAINING_MANAGER')")
    fun uploadLessonAttachment(
        @PathVariable id: String,
        @PathVariable lessonId: String,
        @RequestParam("file") file: MultipartFile,
        @RequestParam(required = false, defaultValue = "") title: String,
    ): CourseDto = courseService.uploadLessonAttachment(id, lessonId, file, title)

    @DeleteMapping("/{id}/lessons/{lessonId}/attachments/{attachmentId}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','TRAINING_MANAGER')")
    fun deleteLessonAttachment(
        @PathVariable id: String,
        @PathVariable lessonId: String,
        @PathVariable attachmentId: String,
    ): CourseDto = courseService.deleteLessonAttachment(id, lessonId, attachmentId)

    @GetMapping("/{id}/lessons/{lessonId}/live")
    fun liveRoom(
        @PathVariable id: String,
        @PathVariable lessonId: String,
        @AuthenticationPrincipal principal: AdminPrincipal?,
    ): CourseLiveRoomDto {
        courseLiveService.requireCourseLesson(id, lessonId)
        return courseLiveService.room(lessonId, requirePrincipal(principal).userId.toString())
    }

    @PostMapping("/{id}/lessons/{lessonId}/live/messages")
    fun liveMessage(
        @PathVariable id: String,
        @PathVariable lessonId: String,
        @Valid @RequestBody req: CreateCourseLiveMessageRequest,
        @AuthenticationPrincipal principal: AdminPrincipal?,
    ): CourseLiveRoomDto {
        courseLiveService.requireCourseLesson(id, lessonId)
        val actor = requirePrincipal(principal)
        return courseLiveService.postMessage(
            lessonId,
            actor.userId.toString(),
            actor.name,
            CourseLiveAuthorType.admin,
            req.kind,
            req.body,
        )
    }

    @PostMapping("/{id}/lessons/{lessonId}/live/polls/{pollId}/vote")
    fun liveVote(
        @PathVariable id: String,
        @PathVariable lessonId: String,
        @PathVariable pollId: String,
        @Valid @RequestBody req: CourseLiveVoteRequest,
        @AuthenticationPrincipal principal: AdminPrincipal?,
    ): CourseLiveRoomDto {
        courseLiveService.requireCourseLesson(id, lessonId)
        return courseLiveService.vote(lessonId, pollId, req.optionId, requirePrincipal(principal).userId.toString())
    }

    @PostMapping("/{id}/lessons/{lessonId}/live/reactions")
    fun liveReaction(
        @PathVariable id: String,
        @PathVariable lessonId: String,
        @Valid @RequestBody req: CourseLiveReactionRequest,
        @AuthenticationPrincipal principal: AdminPrincipal?,
    ): CourseLiveRoomDto {
        courseLiveService.requireCourseLesson(id, lessonId)
        return courseLiveService.react(lessonId, requirePrincipal(principal).userId.toString(), req.reaction)
    }

    @PatchMapping("/{id}/lessons/{lessonId}/live/status")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','TRAINING_MANAGER')")
    fun liveStatus(
        @PathVariable id: String,
        @PathVariable lessonId: String,
        @Valid @RequestBody req: CourseLiveStatusRequest,
        @AuthenticationPrincipal principal: AdminPrincipal?,
    ): CourseLiveRoomDto = courseLiveService.changeStatus(
        id,
        lessonId,
        req.status,
        requirePrincipal(principal).userId.toString(),
    )

    @PatchMapping("/{id}/lessons/{lessonId}/live/messages/{messageId}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','TRAINING_MANAGER')")
    fun moderateLiveMessage(
        @PathVariable id: String,
        @PathVariable lessonId: String,
        @PathVariable messageId: UUID,
        @RequestBody req: CourseLiveModerationRequest,
        @AuthenticationPrincipal principal: AdminPrincipal?,
    ): CourseLiveRoomDto = courseLiveService.moderate(
        id,
        lessonId,
        messageId,
        req.pinned,
        req.hidden,
        requirePrincipal(principal).userId.toString(),
    )

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','TRAINING_MANAGER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: String) = courseService.delete(id)

    private fun requireUserId(principal: AdminPrincipal?): String =
        principal?.userId?.toString()
            ?: throw AppException(ErrorCode.UNAUTHORIZED, "Not authenticated", HttpStatus.UNAUTHORIZED)

    private fun requirePrincipal(principal: AdminPrincipal?): AdminPrincipal = principal
        ?: throw AppException(ErrorCode.UNAUTHORIZED, "Not authenticated", HttpStatus.UNAUTHORIZED)
}
