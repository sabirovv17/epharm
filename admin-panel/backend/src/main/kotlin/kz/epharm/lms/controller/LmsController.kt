package kz.epharm.lms.controller

import jakarta.validation.Valid
import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.lms.dto.CourseDto
import kz.epharm.lms.dto.CreateCourseRequest
import kz.epharm.lms.dto.UpdateCourseRequest
import kz.epharm.lms.entity.CourseStatus
import kz.epharm.lms.service.CourseService
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

// LMS — каталог обучающих курсов (ТЗ §3.2 / §3.4). Этап 3.6: list + get + create.
@RestController
@RequestMapping("/api/admin/lms/courses")
class LmsController(private val courseService: CourseService) {

    @GetMapping
    fun list(@RequestParam(required = false) status: CourseStatus?): List<CourseDto> =
        courseService.list(status = status)

    @GetMapping("/{id}")
    fun get(@PathVariable id: String): CourseDto = courseService.get(id)

    @PostMapping
    fun create(
        @Valid @RequestBody req: CreateCourseRequest,
        @AuthenticationPrincipal principal: AdminPrincipal?,
    ): CourseDto = courseService.create(req, createdBy = requireUserId(principal))

    @PatchMapping("/{id}")
    fun update(@PathVariable id: String, @Valid @RequestBody req: UpdateCourseRequest): CourseDto =
        courseService.update(id, req)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: String) = courseService.delete(id)

    private fun requireUserId(principal: AdminPrincipal?): String =
        principal?.userId?.toString()
            ?: throw AppException(ErrorCode.UNAUTHORIZED, "Not authenticated", HttpStatus.UNAUTHORIZED)
}
