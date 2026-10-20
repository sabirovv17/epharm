package kz.epharm.lms

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kz.epharm.lms.dto.CourseDto
import kz.epharm.lms.dto.CreateCourseLessonRequest
import kz.epharm.lms.dto.CreateCourseRequest
import kz.epharm.lms.entity.CourseLessonKind
import kz.epharm.lms.entity.CourseStatus
import kz.epharm.lms.service.CourseExcelImportService
import kz.epharm.lms.service.CourseService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockMultipartFile
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class CourseExcelImportServiceTest {
    @Test
    fun `production template creates course lessons and test questions`() {
        val courseService = mockk<CourseService>()
        val courseRequest = slot<CreateCourseRequest>()
        val lessonRequests = mutableListOf<CreateCourseLessonRequest>()
        val emptyCourse = courseDto()
        every { courseService.create(capture(courseRequest), "admin-1") } returns emptyCourse
        every { courseService.createLesson("crs_excel", capture(lessonRequests)) } returns emptyCourse
        every { courseService.get("crs_excel") } returns courseDto(lessons = 2, durationMin = 13)

        val bytes = Files.readAllBytes(
            Path.of("../frontend/public/templates/epharm-course-import-template.xlsx"),
        )
        val file = MockMultipartFile(
            "file",
            "epharm-course-import-template.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            bytes,
        )

        val result = CourseExcelImportService(courseService).importCourse(file, "admin-1")

        assertThat(result.lessons).isEqualTo(2)
        assertThat(courseRequest.captured.title).isEqualTo("Безопасный отпуск лекарственных средств")
        assertThat(courseRequest.captured.status).isEqualTo(CourseStatus.draft)
        assertThat(lessonRequests.map { it.kind }).containsExactly(
            CourseLessonKind.text,
            CourseLessonKind.test,
        )
        assertThat(lessonRequests[1].quizQuestions).hasSize(2)
        assertThat(lessonRequests[1].quizQuestions[0].correctOption).isZero()
        assertThat(lessonRequests[1].quizQuestions[1].correctOption).isEqualTo(1)
        verify(exactly = 2) { courseService.createLesson("crs_excel", any()) }
    }

    private fun courseDto(lessons: Int = 0, durationMin: Int = 0) = CourseDto(
        id = "crs_excel",
        title = "Excel курс",
        description = "",
        status = CourseStatus.draft,
        category = "",
        lessons = lessons,
        durationMin = durationMin,
        enrolled = 0,
        completed = 0,
        bonus = 0,
        lessonItems = emptyList(),
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )
}
