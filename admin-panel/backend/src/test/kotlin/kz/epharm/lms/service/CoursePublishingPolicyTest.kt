package kz.epharm.lms.service

import kz.epharm.lms.entity.CourseStatus
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class CoursePublishingPolicyTest {

    @Test
    fun `published course requires at least one lesson`() {
        assertThatThrownBy { requireCourseContentForPublication(CourseStatus.published, 0) }
            .isInstanceOfSatisfying(AppException::class.java) { error ->
                assertThat(error.code).isEqualTo(ErrorCode.VALIDATION_FAILED)
                assertThat(error.status).isEqualTo(HttpStatus.BAD_REQUEST)
                assertThat(error.message).contains("Нельзя опубликовать курс без уроков")
            }
    }

    @Test
    fun `draft and non-empty published course are accepted`() {
        assertThatCode { requireCourseContentForPublication(CourseStatus.draft, 0) }
            .doesNotThrowAnyException()
        assertThatCode { requireCourseContentForPublication(CourseStatus.published, 1) }
            .doesNotThrowAnyException()
    }
}
