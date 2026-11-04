package kz.epharm.lms.service

import kz.epharm.lms.entity.CourseStatus
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus

internal fun requireCourseContentForPublication(status: CourseStatus?, lessonCount: Int) {
    if (status == CourseStatus.published && lessonCount == 0) {
        throw AppException(
            ErrorCode.VALIDATION_FAILED,
            "Нельзя опубликовать курс без уроков. Добавьте хотя бы один урок",
            HttpStatus.BAD_REQUEST,
        )
    }
}
